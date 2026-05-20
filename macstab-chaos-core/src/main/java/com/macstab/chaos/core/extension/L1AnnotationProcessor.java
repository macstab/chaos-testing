/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.opentest4j.TestAbortedException;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;

import lombok.extern.slf4j.Slf4j;

/**
 * Walks an {@link AnnotatedElement} (test class or test method) for L1 chaos annotations — those
 * carrying the {@link ChaosL1} meta-annotation — resolves each annotation's translator via the FQN
 * encoded in the meta-annotation, and applies the translator to every matching container.
 *
 * <p>Lives outside {@code ChaosTestingExtension} so the extension stays focused on the existing
 * container lifecycle and so this code is independently unit-testable.
 *
 * <p><strong>Error routing.</strong>
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} from a translator (developer error: invalid attribute
 *       combination, bad probability, etc.) is wrapped in {@link ExtensionConfigurationException}
 *       with annotation source context — always RED.
 *   <li>{@link LibchaosNotPreparedException} / {@link ChaosUnsupportedOperationException} from a
 *       translator (environment unavailability: libchaos {@code .so} missing, current backend can't
 *       honour the requested verb) is routed through the annotation's {@link OnMissingEnv}
 *       attribute — ERROR raises {@link ExtensionConfigurationException} (RED), ABORT raises {@link
 *       TestAbortedException} (YELLOW).
 *   <li>{@link ClassNotFoundException} on translator resolution (developer error: required chaos
 *       module not on classpath) is wrapped with a build-snippet hint — always RED.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class L1AnnotationProcessor {

  private L1AnnotationProcessor() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Cache of resolved translators keyed by FQN — translator instances are stateless per contract.
   */
  private static final ConcurrentMap<String, L1Translator<Annotation>> TRANSLATOR_CACHE =
      new ConcurrentHashMap<>();

  /**
   * Apply every L1 annotation declared on {@code testClass} to its matching containers. Called from
   * {@code ChaosTestingExtension.beforeAll} after all containers have been started and their
   * connection info created.
   *
   * @param testClass test class to scan
   * @param containers all containers started for this test class
   * @param report cumulative report (mutated)
   * @return handles for class-scope cleanup in {@code afterAll}
   */
  public static List<AppliedL1> applyClassLevel(
      final Class<?> testClass,
      final List<ContainerHandle> containers,
      final ChaosApplicationReport report) {

    Objects.requireNonNull(testClass, "testClass must not be null");
    Objects.requireNonNull(containers, "containers must not be null");
    Objects.requireNonNull(report, "report must not be null");

    return applyElementAnnotations(
        testClass, testClass, containers, report, ChaosApplicationReport.Scope.CLASS);
  }

  /**
   * Apply every L1 annotation declared on {@code testMethod} to its matching containers. Called
   * from {@code ChaosTestingExtension.beforeEach} for each test invocation.
   *
   * @param testMethod the {@code @Test}-annotated method
   * @param containers all containers started for the enclosing test class
   * @param report cumulative report (mutated)
   * @return handles for method-scope cleanup in {@code afterEach}
   */
  public static List<AppliedL1> applyMethodLevel(
      final Method testMethod,
      final List<ContainerHandle> containers,
      final ChaosApplicationReport report) {

    Objects.requireNonNull(testMethod, "testMethod must not be null");
    Objects.requireNonNull(containers, "containers must not be null");
    Objects.requireNonNull(report, "report must not be null");

    return applyElementAnnotations(
        testMethod,
        testMethod.getDeclaringClass(),
        containers,
        report,
        ChaosApplicationReport.Scope.METHOD);
  }

  /**
   * Best-effort cleanup of previously-applied L1s. Each {@link L1Translator#remove} call is wrapped
   * in try/catch; on failure the caller may invoke a container-wide reset as a safety net (see
   * {@link #shouldFallbackToReset}).
   *
   * @param applied handles to remove
   * @return {@code true} if every removal succeeded; {@code false} if any threw
   */
  public static boolean removeAll(final List<AppliedL1> applied) {
    Objects.requireNonNull(applied, "applied must not be null");

    boolean allOk = true;
    for (final AppliedL1 a : applied) {
      try {
        a.translator().remove(a.container(), a.handle());
      } catch (final Exception e) {
        allOk = false;
        log.warn(
            "L1 cleanup failed for @{} — falling back to container.reset() if available",
            a.annotation().annotationType().getSimpleName(),
            e);
      }
    }
    return allOk;
  }

  /**
   * Whether removal of {@code applied} should trigger a fallback container-wide reset. Currently
   * {@code true} whenever any handle failed to remove cleanly; callers wire this to their
   * module-specific {@code Composite<X>Chaos.reset()} entry points.
   */
  public static boolean shouldFallbackToReset(final boolean allOk) {
    return !allOk;
  }

  // ==================== Internal: per-element scan + per-annotation apply ====================

  private static List<AppliedL1> applyElementAnnotations(
      final AnnotatedElement element,
      final Class<?> testClass,
      final List<ContainerHandle> containers,
      final ChaosApplicationReport report,
      final ChaosApplicationReport.Scope scope) {

    final List<AppliedL1> applied = new ArrayList<>();

    for (final Annotation annotation : element.getAnnotations()) {
      final ChaosL1 meta = annotation.annotationType().getAnnotation(ChaosL1.class);
      if (meta == null) {
        continue;
      }
      applied.addAll(applyOne(meta, annotation, containers, report, scope, testClass));
    }

    return applied;
  }

  private static List<AppliedL1> applyOne(
      final ChaosL1 meta,
      final Annotation annotation,
      final List<ContainerHandle> containers,
      final ChaosApplicationReport report,
      final ChaosApplicationReport.Scope scope,
      final Class<?> testClass) {

    final L1Translator<Annotation> translator = resolveTranslator(meta, annotation, testClass);
    final String idFilter = extractStringAttribute(annotation, "id");
    final OnMissingEnv onMissingEnv = extractOnMissingEnvAttribute(annotation);

    final List<ContainerHandle> matching =
        filterByContainerId(containers, idFilter, annotation, testClass);

    if (matching.isEmpty()) {
      throw devError(
          annotation,
          testClass,
          String.format(
              "no container matched id=\"%s\" (available ids: %s). "
                  + "Ensure the L1 annotation's id() matches a container annotation's id().",
              idFilter, availableIds(containers)));
    }

    final List<AppliedL1> applied = new ArrayList<>();
    for (final ContainerHandle ch : matching) {
      try {
        final Object handle = translator.apply(ch.container(), annotation);
        applied.add(new AppliedL1(annotation, ch.container(), translator, handle));
        report.recordApplied(annotation, scope);

      } catch (final IllegalArgumentException e) {
        throw devError(annotation, testClass, "invalid attribute: " + e.getMessage(), e);

      } catch (final LibchaosNotPreparedException | ChaosUnsupportedOperationException e) {
        routeEnvUnavailable(annotation, testClass, scope, onMissingEnv, e, report);
        // routeEnvUnavailable always throws; for completeness:
        return applied;
      }
    }
    return applied;
  }

  // ==================== Translator resolution ====================

  @SuppressWarnings("unchecked")
  private static L1Translator<Annotation> resolveTranslator(
      final ChaosL1 meta, final Annotation annotation, final Class<?> testClass) {

    return TRANSLATOR_CACHE.computeIfAbsent(
        meta.translator(),
        fqn -> {
          try {
            final Class<?> translatorClass = Class.forName(fqn);
            final Object instance = translatorClass.getDeclaredConstructor().newInstance();
            return (L1Translator<Annotation>) instance;

          } catch (final ClassNotFoundException e) {
            throw new ExtensionConfigurationException(
                String.format(
                    "L1 translator class '%s' not found for @%s on %s. "
                        + "The required chaos module is missing from the classpath. "
                        + "Add the corresponding testImplementation dependency.",
                    fqn, annotation.annotationType().getSimpleName(), testClass.getSimpleName()),
                e);

          } catch (final ReflectiveOperationException e) {
            throw new ExtensionConfigurationException(
                String.format(
                    "Failed to instantiate L1 translator '%s' for @%s on %s. "
                        + "Translator must have a public no-arg constructor.",
                    fqn, annotation.annotationType().getSimpleName(), testClass.getSimpleName()),
                e);
          }
        });
  }

  // ==================== Attribute extraction (reflective — no compile-time annotation dep)
  // ====================

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

  private static OnMissingEnv extractOnMissingEnvAttribute(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("onMissingEnv");
      final Object v = m.invoke(annotation);
      return v instanceof OnMissingEnv om ? om : OnMissingEnv.ERROR;
    } catch (final NoSuchMethodException e) {
      // Annotation predates the OnMissingEnv attribute — safe default
      return OnMissingEnv.ERROR;
    } catch (final ReflectiveOperationException e) {
      throw new ExtensionConfigurationException(
          "Failed to read onMissingEnv() from " + annotation, e);
    }
  }

  // ==================== Container filtering ====================

  private static List<ContainerHandle> filterByContainerId(
      final List<ContainerHandle> containers,
      final String idFilter,
      final Annotation annotation,
      final Class<?> testClass) {

    if (idFilter.isEmpty()) {
      return containers; // empty id = apply to every container
    }

    final List<ContainerHandle> matching = new ArrayList<>();
    for (final ContainerHandle ch : containers) {
      if (idFilter.equals(ch.id())) {
        matching.add(ch);
      }
    }
    return matching;
  }

  private static String availableIds(final List<ContainerHandle> containers) {
    if (containers.isEmpty()) {
      return "(none — no containers started)";
    }
    final List<String> ids = new ArrayList<>();
    for (final ContainerHandle ch : containers) {
      ids.add("\"" + ch.id() + "\"");
    }
    return String.join(", ", ids);
  }

  // ==================== Error helpers ====================

  private static ExtensionConfigurationException devError(
      final Annotation annotation, final Class<?> testClass, final String detail) {
    return devError(annotation, testClass, detail, null);
  }

  private static ExtensionConfigurationException devError(
      final Annotation annotation,
      final Class<?> testClass,
      final String detail,
      final Throwable cause) {

    final String message =
        String.format(
            "@%s on %s: %s",
            annotation.annotationType().getSimpleName(), testClass.getSimpleName(), detail);
    return cause == null
        ? new ExtensionConfigurationException(message)
        : new ExtensionConfigurationException(message, cause);
  }

  private static void routeEnvUnavailable(
      final Annotation annotation,
      final Class<?> testClass,
      final ChaosApplicationReport.Scope scope,
      final OnMissingEnv onMissingEnv,
      final Exception cause,
      final ChaosApplicationReport report) {

    final String diagnosis =
        String.format(
            "@%s on %s: environment cannot honour this primitive — %s",
            annotation.annotationType().getSimpleName(),
            testClass.getSimpleName(),
            cause.getMessage());

    switch (onMissingEnv) {
      case ERROR -> throw new ExtensionConfigurationException(diagnosis, cause);
      case ABORT -> {
        report.recordSkipped(annotation, scope, cause.getMessage());
        throw new TestAbortedException(diagnosis, cause);
      }
    }
  }

  // ==================== Public data carriers ====================

  /**
   * Internal projection of a started container plus its id, decoupling the processor from {@code
   * ChaosTestingExtension}'s private {@code ContainerInstance}.
   *
   * @param container the running container
   * @param id container id from its source annotation's {@code id()} attribute, or {@code
   *     "default"}
   * @param sourceAnnotationType annotation class that registered this container (diagnostic only)
   */
  public record ContainerHandle(
      GenericContainer<?> container, String id, Class<? extends Annotation> sourceAnnotationType) {

    /** Validating canonical constructor. */
    public ContainerHandle {
      Objects.requireNonNull(container, "container must not be null");
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(sourceAnnotationType, "sourceAnnotationType must not be null");
    }
  }

  /**
   * Bundle of (annotation, container, translator, handle) needed to undo a previously-applied L1.
   * Stored in the {@code ExtensionContext.Store} between apply and remove.
   *
   * @param annotation the L1 annotation instance that was applied
   * @param container the container the rule landed on
   * @param translator the translator that applied it (for the matching {@code remove} call)
   * @param handle opaque handle returned by {@code translator.apply}
   */
  public record AppliedL1(
      Annotation annotation,
      GenericContainer<?> container,
      L1Translator<Annotation> translator,
      Object handle) {

    /** Validating canonical constructor. */
    public AppliedL1 {
      Objects.requireNonNull(annotation, "annotation must not be null");
      Objects.requireNonNull(container, "container must not be null");
      Objects.requireNonNull(translator, "translator must not be null");
      Objects.requireNonNull(handle, "handle must not be null");
    }
  }
}
