/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.testcontainers.containers.GenericContainer;

import lombok.extern.slf4j.Slf4j;

/**
 * Walks an annotated element (test class or test method) for L3 incident scenario annotations —
 * those carrying the {@link ChaosL3} meta-annotation — resolves each annotation's composer via the
 * FQN encoded in the meta-annotation, and applies the composer to every matching container.
 *
 * <p>L3 incident annotations are the production-incident vocabulary tier: a single
 * {@code @IncidentChaosRedisFailoverStorm} fires connection, DNS, and time rules simultaneously,
 * replicating the compound failure modes seen in real Redis Sentinel failover events.
 *
 * <p>Processing rules mirror {@link L2AnnotationProcessor} exactly:
 * <ul>
 *   <li>Repeatable L3 annotations are unwrapped and each applied independently.
 *   <li>The {@code id()} attribute filters containers before passing to the composer.
 *   <li>Composer instances are cached in a {@link ConcurrentHashMap} keyed by FQN.
 *   <li>Failures in {@code apply()} always throw {@code ExtensionConfigurationException}.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class L3AnnotationProcessor {

  private L3AnnotationProcessor() {
    throw new UnsupportedOperationException("Utility class");
  }

  private static final ConcurrentMap<String, L3Composer<Annotation>> COMPOSER_CACHE =
      new ConcurrentHashMap<>();

  // ==================== Public entry points ====================

  /**
   * Apply every L3 annotation declared on {@code testClass} to its matching containers.
   * Called from {@code ChaosTestingExtension.beforeAll} after L2 annotations have been applied.
   *
   * @param testClass test class to scan
   * @param containers all containers started for this test class
   * @param report cumulative report (mutated)
   * @return handles for cleanup in {@code afterAll}
   */
  public static List<AppliedL3> applyClassLevel(
      final Class<?> testClass,
      final List<L1AnnotationProcessor.ContainerHandle> containers,
      final ChaosApplicationReport report) {

    Objects.requireNonNull(testClass, "testClass must not be null");
    Objects.requireNonNull(containers, "containers must not be null");
    Objects.requireNonNull(report, "report must not be null");

    return applyAnnotations(
        testClass.getAnnotations(), testClass, containers, report, ChaosApplicationReport.Scope.CLASS);
  }

  /**
   * Apply every L3 annotation declared on {@code testMethod} to its matching containers.
   * Called from {@code ChaosTestingExtension.beforeEach}.
   *
   * @param testMethod the {@code @Test}-annotated method
   * @param containers all containers started for the enclosing test class
   * @param report cumulative report (mutated)
   * @return method-scope handles for cleanup in {@code afterEach}
   */
  public static List<AppliedL3> applyMethodLevel(
      final Method testMethod,
      final List<L1AnnotationProcessor.ContainerHandle> containers,
      final ChaosApplicationReport report) {

    Objects.requireNonNull(testMethod, "testMethod must not be null");
    Objects.requireNonNull(containers, "containers must not be null");
    Objects.requireNonNull(report, "report must not be null");

    return applyAnnotations(
        testMethod.getAnnotations(),
        testMethod.getDeclaringClass(),
        containers,
        report,
        ChaosApplicationReport.Scope.METHOD);
  }

  /**
   * Best-effort cleanup of previously-applied L3 incident scenarios.
   * Each {@link L3Composer#removeAll} call is wrapped in try/catch so a single failure
   * does not block cleanup of the remaining handles.
   *
   * @param applied handles to remove
   * @return {@code true} if every removal succeeded; {@code false} if any threw
   */
  public static boolean removeAll(final List<AppliedL3> applied) {
    Objects.requireNonNull(applied, "applied must not be null");

    boolean allOk = true;
    for (final AppliedL3 a : applied) {
      try {
        a.composer().removeAll(a.container(), a.handles());
      } catch (final Exception e) {
        allOk = false;
        log.warn(
            "L3 cleanup failed for @{}", a.annotation().annotationType().getSimpleName(), e);
      }
    }
    return allOk;
  }

  // ==================== Internal: annotation scan ====================

  private static List<AppliedL3> applyAnnotations(
      final Annotation[] rawAnnotations,
      final Class<?> testClass,
      final List<L1AnnotationProcessor.ContainerHandle> containers,
      final ChaosApplicationReport report,
      final ChaosApplicationReport.Scope scope) {

    final List<AppliedL3> applied = new ArrayList<>();

    for (final Annotation annotation : expandAnnotations(rawAnnotations)) {
      final ChaosL3 meta = annotation.annotationType().getAnnotation(ChaosL3.class);
      if (meta == null) {
        continue;
      }

      final String idFilter = extractStringAttribute(annotation, "id");
      final List<L1AnnotationProcessor.ContainerHandle> matching =
          filterByContainerId(containers, idFilter);

      if (matching.isEmpty() && !containers.isEmpty()) {
        throw new ExtensionConfigurationException(
            String.format(
                "@%s on %s: no container matched id=\"%s\" (available ids: %s). "
                    + "Ensure the L3 annotation's id() matches a container annotation's id().",
                annotation.annotationType().getSimpleName(),
                testClass.getSimpleName(),
                idFilter,
                availableIds(containers)));
      }

      final L3Composer<Annotation> composer = resolveComposer(meta, annotation, testClass);

      for (final L1AnnotationProcessor.ContainerHandle ch : matching) {
        try {
          final List<Object> handles = composer.apply(ch.container(), annotation);
          final List<String> description = composer.describe(annotation);
          applied.add(
              new AppliedL3(
                  annotation, ch.container(), composer, handles, meta.severity(), description));
          report.recordL3Applied(annotation, scope);
          log.debug(
              "Applied L3 @{} (severity={}) to container {} — {}",
              annotation.annotationType().getSimpleName(),
              meta.severity(),
              ch.id(),
              description.isEmpty() ? "(no description)" : description.get(0));

        } catch (final IllegalArgumentException e) {
          throw new ExtensionConfigurationException(
              String.format(
                  "@%s on %s: invalid attribute — %s",
                  annotation.annotationType().getSimpleName(),
                  testClass.getSimpleName(),
                  e.getMessage()),
              e);

        } catch (final Exception e) {
          throw new ExtensionConfigurationException(
              String.format(
                  "@%s on %s: composer apply() failed — %s. "
                      + "Ensure the required chaos modules are on the classpath.",
                  annotation.annotationType().getSimpleName(),
                  testClass.getSimpleName(),
                  e.getMessage()),
              e);
        }
      }
    }

    return applied;
  }

  @SuppressWarnings("unchecked")
  private static List<Annotation> expandAnnotations(final Annotation[] raw) {
    final List<Annotation> result = new ArrayList<>(raw.length);
    for (final Annotation candidate : raw) {
      try {
        final Method valueMethod = candidate.annotationType().getMethod("value");
        final Class<?> returnType = valueMethod.getReturnType();
        if (returnType.isArray() && returnType.getComponentType().isAnnotation()) {
          final Class<? extends Annotation> componentType =
              (Class<? extends Annotation>) returnType.getComponentType();
          if (componentType.isAnnotationPresent(ChaosL3.class)) {
            final Annotation[] inner = (Annotation[]) valueMethod.invoke(candidate);
            for (final Annotation a : inner) {
              result.add(a);
            }
            continue;
          }
        }
      } catch (final NoSuchMethodException ignored) {
        // not a container annotation
      } catch (final ReflectiveOperationException e) {
        log.warn("Could not inspect annotation {}", candidate.annotationType().getSimpleName(), e);
      }
      result.add(candidate);
    }
    return result;
  }

  // ==================== Composer resolution ====================

  @SuppressWarnings("unchecked")
  private static L3Composer<Annotation> resolveComposer(
      final ChaosL3 meta, final Annotation annotation, final Class<?> testClass) {

    return COMPOSER_CACHE.computeIfAbsent(
        meta.composer(),
        fqn -> {
          try {
            final Class<?> composerClass = Class.forName(fqn);
            final Object instance = composerClass.getDeclaredConstructor().newInstance();
            return (L3Composer<Annotation>) instance;

          } catch (final ClassNotFoundException e) {
            throw new ExtensionConfigurationException(
                String.format(
                    "L3 composer class '%s' not found for @%s on %s. "
                        + "The required chaos L3 testpack module is missing from the classpath. "
                        + "Add the corresponding testImplementation dependency.",
                    fqn,
                    annotation.annotationType().getSimpleName(),
                    testClass.getSimpleName()),
                e);

          } catch (final ReflectiveOperationException e) {
            throw new ExtensionConfigurationException(
                String.format(
                    "Failed to instantiate L3 composer '%s' for @%s on %s. "
                        + "Composer must have a public no-arg constructor.",
                    fqn,
                    annotation.annotationType().getSimpleName(),
                    testClass.getSimpleName()),
                e);
          }
        });
  }

  // ==================== Attribute extraction ====================

  private static String extractStringAttribute(final Annotation annotation, final String name) {
    try {
      final Method m = annotation.annotationType().getMethod(name);
      final Object v = m.invoke(annotation);
      return v == null ? "" : v.toString();
    } catch (final NoSuchMethodException e) {
      return "";
    } catch (final ReflectiveOperationException e) {
      throw new ExtensionConfigurationException(
          "Failed to read attribute '" + name + "' from " + annotation, e);
    }
  }

  // ==================== Container filtering ====================

  private static List<L1AnnotationProcessor.ContainerHandle> filterByContainerId(
      final List<L1AnnotationProcessor.ContainerHandle> containers, final String idFilter) {

    if (idFilter.isEmpty()) {
      return containers;
    }
    final List<L1AnnotationProcessor.ContainerHandle> matching = new ArrayList<>();
    for (final L1AnnotationProcessor.ContainerHandle ch : containers) {
      if (idFilter.equals(ch.id())) {
        matching.add(ch);
      }
    }
    return matching;
  }

  private static String availableIds(
      final List<L1AnnotationProcessor.ContainerHandle> containers) {
    if (containers.isEmpty()) {
      return "(none — no containers started)";
    }
    final List<String> ids = new ArrayList<>();
    for (final L1AnnotationProcessor.ContainerHandle ch : containers) {
      ids.add("\"" + ch.id() + "\"");
    }
    return String.join(", ", ids);
  }

  // ==================== Public data carriers ====================

  /**
   * Bundle of (annotation, container, composer, handles, severity, description) needed to undo a
   * previously-applied L3 incident scenario. Stored in the {@code ExtensionContext.Store} between
   * apply and remove.
   *
   * @param annotation the L3 annotation instance that was applied
   * @param container the container the rules landed on
   * @param composer the composer that applied the rules (for the matching {@code removeAll} call)
   * @param handles opaque handles returned by {@code composer.apply}
   * @param severity severity classification from the {@link ChaosL3} meta-annotation
   * @param description human-readable description lines from {@code composer.describe}
   */
  public record AppliedL3(
      Annotation annotation,
      GenericContainer<?> container,
      L3Composer<Annotation> composer,
      List<Object> handles,
      Severity severity,
      List<String> description) {

    /** Validating canonical constructor. */
    public AppliedL3 {
      Objects.requireNonNull(annotation, "annotation must not be null");
      Objects.requireNonNull(container, "container must not be null");
      Objects.requireNonNull(composer, "composer must not be null");
      Objects.requireNonNull(handles, "handles must not be null");
      Objects.requireNonNull(severity, "severity must not be null");
      Objects.requireNonNull(description, "description must not be null");
    }
  }
}
