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
 * Walks an annotated element (test class or test method) for L2 chaos scenario annotations —
 * those carrying the {@link ChaosL2} meta-annotation — resolves each annotation's composer via the
 * FQN encoded in the meta-annotation, and applies the composer to every matching container.
 *
 * <p>L2 scenario annotations are the industry-vocabulary tier: a single {@code
 * @CompositeChaosNxDomain} is equivalent to building and applying a hand-crafted {@code DnsRule}
 * via {@code AdvancedDnsChaos}, but with documented defaults, structured Javadoc, and automatic
 * lifecycle management.
 *
 * <p><strong>Error policy.</strong> L2 annotations always hard-fail ({@code
 * ExtensionConfigurationException}) when the environment cannot honour the scenario — there is no
 * {@code OnMissingEnv} opt-out at the L2 tier. A test class that declares a scenario is asserting
 * that the scenario is runnable; if it cannot be, the test configuration is wrong.
 *
 * <p><strong>Repeatable annotations.</strong> L2 scenario annotations may be marked {@code
 * @Repeatable}. Both single and repeated uses are unwrapped and each applied independently.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class L2AnnotationProcessor {

  private L2AnnotationProcessor() {
    throw new UnsupportedOperationException("Utility class");
  }

  private static final ConcurrentMap<String, L2Composer<Annotation>> COMPOSER_CACHE =
      new ConcurrentHashMap<>();

  // ==================== Public entry points ====================

  /**
   * Apply every L2 annotation declared on {@code testClass} to its matching containers. Called from
   * {@code ChaosTestingExtension.beforeAll} after all containers have been started and L1
   * annotations applied.
   *
   * @param testClass test class to scan
   * @param containers all containers started for this test class
   * @param report cumulative report (mutated)
   * @return handles for cleanup in {@code afterAll}
   */
  public static List<AppliedL2> applyClassLevel(
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
   * Apply every L2 annotation declared on {@code testMethod} to its matching containers. Called
   * from {@code ChaosTestingExtension.beforeEach}.
   *
   * @param testMethod the {@code @Test}-annotated method
   * @param containers all containers started for the enclosing test class
   * @param report cumulative report (mutated)
   * @return method-scope handles for cleanup in {@code afterEach}
   */
  public static List<AppliedL2> applyMethodLevel(
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
   * Best-effort cleanup of previously-applied L2 scenarios. Each {@link L2Composer#removeAll} call
   * is wrapped in try/catch so a single failure does not block cleanup of the remaining handles.
   *
   * @param applied handles to remove
   * @return {@code true} if every removal succeeded; {@code false} if any threw
   */
  public static boolean removeAll(final List<AppliedL2> applied) {
    Objects.requireNonNull(applied, "applied must not be null");

    boolean allOk = true;
    for (final AppliedL2 a : applied) {
      try {
        a.composer().removeAll(a.container(), a.handles());
      } catch (final Exception e) {
        allOk = false;
        log.warn(
            "L2 cleanup failed for @{}", a.annotation().annotationType().getSimpleName(), e);
      }
    }
    return allOk;
  }

  // ==================== Internal: annotation scan ====================

  private static List<AppliedL2> applyAnnotations(
      final Annotation[] rawAnnotations,
      final Class<?> testClass,
      final List<L1AnnotationProcessor.ContainerHandle> containers,
      final ChaosApplicationReport report,
      final ChaosApplicationReport.Scope scope) {

    final List<AppliedL2> applied = new ArrayList<>();

    for (final Annotation annotation : expandAnnotations(rawAnnotations)) {
      final ChaosL2 meta = annotation.annotationType().getAnnotation(ChaosL2.class);
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
                    + "Ensure the L2 annotation's id() matches a container annotation's id().",
                annotation.annotationType().getSimpleName(),
                testClass.getSimpleName(),
                idFilter,
                availableIds(containers)));
      }

      final L2Composer<Annotation> composer = resolveComposer(meta, annotation, testClass);

      for (final L1AnnotationProcessor.ContainerHandle ch : matching) {
        try {
          final List<Object> handles = composer.apply(ch.container(), annotation);
          final List<String> description = composer.describe(annotation);
          applied.add(
              new AppliedL2(
                  annotation, ch.container(), composer, handles, meta.severity(), description));
          report.recordL2Applied(annotation, scope);
          log.debug(
              "Applied L2 @{} (severity={}) to container {} — {}",
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
                      + "Ensure the required chaos module is prepared (@SyscallLevelChaos / @JvmAgentChaos) "
                      + "and the module is on the classpath.",
                  annotation.annotationType().getSimpleName(),
                  testClass.getSimpleName(),
                  e.getMessage()),
              e);
        }
      }
    }

    return applied;
  }

  /**
   * Expands repeatable container annotations so each individual L2 annotation is processed
   * independently (mirrors the same unwrapping logic in {@link L1AnnotationProcessor}).
   */
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
          if (componentType.isAnnotationPresent(ChaosL2.class)) {
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
  private static L2Composer<Annotation> resolveComposer(
      final ChaosL2 meta, final Annotation annotation, final Class<?> testClass) {

    return COMPOSER_CACHE.computeIfAbsent(
        meta.composer(),
        fqn -> {
          try {
            final Class<?> composerClass = Class.forName(fqn);
            final Object instance = composerClass.getDeclaredConstructor().newInstance();
            return (L2Composer<Annotation>) instance;

          } catch (final ClassNotFoundException e) {
            throw new ExtensionConfigurationException(
                String.format(
                    "L2 composer class '%s' not found for @%s on %s. "
                        + "The required chaos testpack module is missing from the classpath. "
                        + "Add the corresponding testImplementation dependency.",
                    fqn,
                    annotation.annotationType().getSimpleName(),
                    testClass.getSimpleName()),
                e);

          } catch (final ReflectiveOperationException e) {
            throw new ExtensionConfigurationException(
                String.format(
                    "Failed to instantiate L2 composer '%s' for @%s on %s. "
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
   * previously-applied L2 scenario. Stored in the {@code ExtensionContext.Store} between apply and
   * remove.
   *
   * @param annotation the L2 annotation instance that was applied
   * @param container the container the rules landed on
   * @param composer the composer that applied the rules (for the matching {@code removeAll} call)
   * @param handles opaque handles returned by {@code composer.apply}
   * @param severity severity classification from the {@link ChaosL2} meta-annotation
   * @param description human-readable description lines from {@code composer.describe}
   */
  public record AppliedL2(
      Annotation annotation,
      GenericContainer<?> container,
      L2Composer<Annotation> composer,
      List<Object> handles,
      Severity severity,
      List<String> description) {

    /** Validating canonical constructor. */
    public AppliedL2 {
      Objects.requireNonNull(annotation, "annotation must not be null");
      Objects.requireNonNull(container, "container must not be null");
      Objects.requireNonNull(composer, "composer must not be null");
      Objects.requireNonNull(handles, "handles must not be null");
      Objects.requireNonNull(severity, "severity must not be null");
      Objects.requireNonNull(description, "description must not be null");
    }
  }
}
