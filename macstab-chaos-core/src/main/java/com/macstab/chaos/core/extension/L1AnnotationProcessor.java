/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.opentest4j.TestAbortedException;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;

import lombok.extern.slf4j.Slf4j;

/**
 * Walks an {@link AnnotatedElement} (test class, field, or test method) for L1 chaos annotations —
 * those carrying the {@link ChaosL1} meta-annotation — resolves each annotation's translator via
 * the FQN encoded in the meta-annotation, and applies the translator to every matching container.
 *
 * <p><strong>Scopes</strong>
 *
 * <ul>
 *   <li><em>Class-scope</em> ({@link #applyClassLevel}): annotations on the test class, applied
 *       once at {@code beforeAll}.
 *   <li><em>Field-scope</em> ({@link #applyFieldLevel}): annotations on fields of the test class,
 *       applied once at {@code beforeAll}. When {@code id()} is empty the field name is used as the
 *       implicit container id — the annotation co-locates with the container field declaration.
 *   <li><em>Method-scope</em> ({@link #applyMethodLevelWithSuspension}): annotations on
 *       {@code @Test} methods. A method-scope rule for the same annotation type and container as an
 *       active class/field rule <em>overrides</em> (temporarily suspends) the class/field rule.
 *       After the method exits the suspended rule is re-applied via {@link #reapply}.
 * </ul>
 *
 * <p><strong>Repeatable annotations</strong> — both single and repeated uses of the same L1
 * annotation (via the nested {@code @interface Repeatable} container) are unwrapped and each
 * instance is applied independently.
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

  // ==================== Public entry points ====================

  /**
   * Apply every L1 annotation declared on {@code testClass} to its matching containers. Called from
   * {@code ChaosTestingExtension.beforeAll} after all containers have been started and their
   * connection info created.
   *
   * @param testClass test class to scan
   * @param containers all containers started for this test class
   * @param report cumulative report (mutated)
   * @return handles for cleanup in {@code afterAll}
   */
  public static List<AppliedL1> applyClassLevel(
      final Class<?> testClass,
      final List<ContainerHandle> containers,
      final ChaosApplicationReport report) {

    Objects.requireNonNull(testClass, "testClass must not be null");
    Objects.requireNonNull(containers, "containers must not be null");
    Objects.requireNonNull(report, "report must not be null");

    return applyElementAnnotations(
        testClass, testClass, null, containers, report, ChaosApplicationReport.Scope.CLASS);
  }

  /**
   * Apply every L1 annotation declared on fields of {@code testClass} to their matching containers.
   * Called from {@code ChaosTestingExtension.beforeAll} after all containers have been started.
   *
   * <p>When a field annotation's {@code id()} is empty the <em>field name</em> is used as the
   * container id filter — the annotation is implicitly bound to the container declared in that same
   * field. When {@code id()} is non-empty it takes precedence.
   *
   * @param testClass test class whose fields are scanned
   * @param containers all containers started for this test class
   * @param report cumulative report (mutated)
   * @return handles for cleanup in {@code afterAll}
   */
  /**
   * Apply every L1 annotation declared on fields of {@code testClass}, permanently displacing any
   * conflicting class-level rule for the same annotation type and container. Priority: field > class.
   *
   * <p>Conflicting entries in {@code classHandles} are removed in-place and their rules removed
   * from the container — field rules win for the entire test-class lifetime. The result contains
   * only the new field-level handles; the caller's {@code classHandles} list is already trimmed.
   *
   * <p>Id resolution when a field annotation's {@code id()} is empty:
   * <ol>
   *   <li>If the same field also carries a container annotation (one of
   *       {@code containerAnnotationTypes}), use that container's {@code id()} value (which may be
   *       {@code ""}, meaning every container). This lets an L1 sit beside its container
   *       annotation without repeating the id string.
   *   <li>Otherwise fall back to the field's own name as the id filter.
   * </ol>
   * When {@code id()} is non-empty on the L1 annotation it takes precedence unconditionally.
   *
   * @param testClass test class whose fields are scanned
   * @param containers all containers started for this test class
   * @param containerAnnotationTypes annotation types handled by known {@code ChaosPlugin}s
   * @param classHandles mutable list of class-scope handles; conflicting entries are extracted
   *     and their rules removed from containers
   * @param report cumulative report (mutated)
   * @return field-level handles for cleanup in {@code afterAll}
   */
  public static List<AppliedL1> applyFieldLevel(
      final Class<?> testClass,
      final List<ContainerHandle> containers,
      final Set<Class<? extends Annotation>> containerAnnotationTypes,
      final List<AppliedL1> classHandles,
      final ChaosApplicationReport report) {

    Objects.requireNonNull(testClass, "testClass must not be null");
    Objects.requireNonNull(containers, "containers must not be null");
    Objects.requireNonNull(containerAnnotationTypes, "containerAnnotationTypes must not be null");
    Objects.requireNonNull(classHandles, "classHandles must not be null");
    Objects.requireNonNull(report, "report must not be null");

    final List<AppliedL1> applied = new ArrayList<>();
    for (final Field field : testClass.getDeclaredFields()) {
      final String idHint = resolveFieldIdHint(field, containerAnnotationTypes);

      for (final Annotation annotation : expandAnnotations(field.getAnnotations())) {
        final ChaosL1 meta = annotation.annotationType().getAnnotation(ChaosL1.class);
        if (meta == null) {
          continue;
        }

        String idFilter = extractStringAttribute(annotation, "id");
        if (idFilter.isEmpty()) {
          idFilter = idHint;
        }

        final List<ContainerHandle> matching =
            filterByContainerId(containers, idFilter, annotation, testClass);
        if (matching.isEmpty()) {
          throw noContainerError(annotation, testClass, idFilter, containers);
        }

        final L1Translator<Annotation> translator = resolveTranslator(meta, annotation, testClass);
        final OnMissingEnv onMissingEnv = extractOnMissingEnvAttribute(annotation);

        for (final ContainerHandle ch : matching) {
          // Permanently displace any conflicting class-level rule (field has higher priority)
          displaceClassRule(annotation.annotationType(), ch.container(), classHandles, testClass);

          try {
            final Object handle = translator.apply(ch.container(), annotation);
            applied.add(new AppliedL1(annotation, ch.container(), translator, handle));
            report.recordApplied(annotation, ChaosApplicationReport.Scope.FIELD);

          } catch (final IllegalArgumentException e) {
            throw devError(annotation, testClass, "invalid attribute: " + e.getMessage(), e);

          } catch (final LibchaosNotPreparedException | ChaosUnsupportedOperationException e) {
            routeEnvUnavailable(
                annotation, testClass, ChaosApplicationReport.Scope.FIELD, onMissingEnv, e,
                report);
            return applied;
          }
        }
      }
    }
    return applied;
  }

  /**
   * Removes from {@code classHandles} any rule for {@code annotationType} on {@code container}
   * and calls {@code translator.remove} to deactivate it. Field rules permanently win over class
   * rules — no restoration in {@code afterAll}.
   */
  private static void displaceClassRule(
      final Class<? extends Annotation> annotationType,
      final GenericContainer<?> container,
      final List<AppliedL1> classHandles,
      final Class<?> testClass) {

    final List<AppliedL1> displaced = extractConflicts(annotationType, container, classHandles);
    for (final AppliedL1 d : displaced) {
      try {
        d.translator().remove(d.container(), d.handle());
        log.debug(
            "Field-level @{} permanently displaced class-level rule on container",
            annotationType.getSimpleName());
      } catch (final Exception e) {
        log.warn(
            "Failed to remove class-level @{} displaced by field-level override on {}",
            annotationType.getSimpleName(),
            testClass.getSimpleName(),
            e);
      }
    }
  }

  /**
   * Resolves the id hint for field-level L1 annotations whose own {@code id()} is empty.
   * Prefers the co-located container annotation's id; falls back to the field name.
   */
  private static String resolveFieldIdHint(
      final Field field, final Set<Class<? extends Annotation>> containerAnnotationTypes) {

    for (final Annotation a : field.getAnnotations()) {
      if (containerAnnotationTypes.contains(a.annotationType())) {
        return extractStringAttribute(a, "id"); // may be "" — caller handles that correctly
      }
    }
    return field.getName();
  }

  /**
   * Apply every L1 annotation declared on {@code testMethod} to its matching containers, suspending
   * any conflicting persistent (class/field-scope) rules. A rule conflict is defined as the same
   * annotation type applied to the same container.
   *
   * <p>Conflicting rules are removed from {@code persistentHandles} (in-place mutation) and
   * returned in {@link MethodLevelResult#suspended()} so that
   * {@link #reapply(List, ChaosApplicationReport)} can restore them in {@code afterEach}.
   *
   * @param testMethod the {@code @Test}-annotated method
   * @param containers all containers started for the enclosing test class
   * @param persistentHandles mutable list of currently-active class/field handles (modified
   *     in-place: conflicting entries are extracted)
   * @param report cumulative report (mutated)
   * @return (methodHandles, suspended) — both lists stored in the extension context store
   */
  public static MethodLevelResult applyMethodLevelWithSuspension(
      final Method testMethod,
      final List<ContainerHandle> containers,
      final List<AppliedL1> persistentHandles,
      final ChaosApplicationReport report) {

    Objects.requireNonNull(testMethod, "testMethod must not be null");
    Objects.requireNonNull(containers, "containers must not be null");
    Objects.requireNonNull(persistentHandles, "persistentHandles must not be null");
    Objects.requireNonNull(report, "report must not be null");

    final Class<?> testClass = testMethod.getDeclaringClass();
    final List<AppliedL1> methodHandles = new ArrayList<>();
    final List<AppliedL1> suspended = new ArrayList<>();

    for (final Annotation annotation : expandAnnotations(testMethod.getAnnotations())) {
      final ChaosL1 meta = annotation.annotationType().getAnnotation(ChaosL1.class);
      if (meta == null) {
        continue;
      }

      final L1Translator<Annotation> translator = resolveTranslator(meta, annotation, testClass);
      final String idFilter = extractStringAttribute(annotation, "id");
      final OnMissingEnv onMissingEnv = extractOnMissingEnvAttribute(annotation);

      final List<ContainerHandle> matching =
          filterByContainerId(containers, idFilter, annotation, testClass);

      if (matching.isEmpty()) {
        throw noContainerError(annotation, testClass, idFilter, containers);
      }

      for (final ContainerHandle ch : matching) {
        // Suspend any conflicting persistent rule for this annotation type + container
        final List<AppliedL1> conflicts =
            extractConflicts(annotation.annotationType(), ch.container(), persistentHandles);
        for (final AppliedL1 conflict : conflicts) {
          try {
            conflict.translator().remove(conflict.container(), conflict.handle());
            suspended.add(conflict);
            log.debug(
                "Suspended persistent @{} on container {} for method {}#{}",
                conflict.annotation().annotationType().getSimpleName(),
                ch.id(),
                testClass.getSimpleName(),
                testMethod.getName());
          } catch (final Exception e) {
            log.warn(
                "Failed to suspend persistent L1 @{} on container {} — method override may not apply cleanly",
                conflict.annotation().annotationType().getSimpleName(),
                ch.id(),
                e);
          }
        }

        // Apply method rule
        try {
          final Object handle = translator.apply(ch.container(), annotation);
          methodHandles.add(new AppliedL1(annotation, ch.container(), translator, handle));
          report.recordApplied(annotation, ChaosApplicationReport.Scope.METHOD);

        } catch (final IllegalArgumentException e) {
          throw devError(annotation, testClass, "invalid attribute: " + e.getMessage(), e);

        } catch (final LibchaosNotPreparedException | ChaosUnsupportedOperationException e) {
          routeEnvUnavailable(
              annotation, testClass, ChaosApplicationReport.Scope.METHOD, onMissingEnv, e, report);
          return new MethodLevelResult(methodHandles, suspended);
        }
      }
    }

    return new MethodLevelResult(methodHandles, suspended);
  }

  /**
   * Re-applies previously-suspended L1 rules after a method-scope override has been cleaned up.
   * Called in {@code afterEach} after {@link #removeAll} removes the method handles.
   *
   * <p>Each suspended rule is re-applied via its original translator and annotation. The resulting
   * new handles should be added back to the persistent handle list so that {@code afterAll} cleans
   * them up correctly.
   *
   * @param suspended handles that were suspended in {@link #applyMethodLevelWithSuspension}
   * @return new handles for the re-applied rules (add back to the persistent list)
   */
  public static List<AppliedL1> reapply(final List<AppliedL1> suspended) {
    Objects.requireNonNull(suspended, "suspended must not be null");

    final List<AppliedL1> restored = new ArrayList<>();
    for (final AppliedL1 s : suspended) {
      try {
        final Object handle = s.translator().apply(s.container(), s.annotation());
        restored.add(new AppliedL1(s.annotation(), s.container(), s.translator(), handle));
        log.debug(
            "Restored suspended @{}", s.annotation().annotationType().getSimpleName());
      } catch (final Exception e) {
        log.warn(
            "Failed to restore suspended L1 @{} after method exit — container chaos state may be inconsistent",
            s.annotation().annotationType().getSimpleName(),
            e);
      }
    }
    return restored;
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
   * Whether removal of {@code applied} should trigger a fallback container-wide reset.
   */
  public static boolean shouldFallbackToReset(final boolean allOk) {
    return !allOk;
  }

  // ==================== Internal: per-element scan ====================

  /**
   * Scans {@code element} for L1 annotations and applies each to matching containers.
   *
   * @param fieldNameHint when non-null (field scope), used as id fallback when {@code id()} is empty
   */
  private static List<AppliedL1> applyElementAnnotations(
      final AnnotatedElement element,
      final Class<?> testClass,
      final String fieldNameHint,
      final List<ContainerHandle> containers,
      final ChaosApplicationReport report,
      final ChaosApplicationReport.Scope scope) {

    final List<AppliedL1> applied = new ArrayList<>();

    for (final Annotation annotation : expandAnnotations(element.getAnnotations())) {
      final ChaosL1 meta = annotation.annotationType().getAnnotation(ChaosL1.class);
      if (meta == null) {
        continue;
      }

      String idFilter = extractStringAttribute(annotation, "id");
      if (idFilter.isEmpty() && fieldNameHint != null) {
        idFilter = fieldNameHint;
      }

      applied.addAll(applyOne(meta, annotation, idFilter, containers, report, scope, testClass));
    }

    return applied;
  }

  /**
   * Expands repeatable container annotations so each individual L1 annotation is processed.
   *
   * <p>When two or more identical L1 annotations appear on the same element Java wraps them in the
   * nested {@code @interface Repeatable} container. That container doesn't carry {@link ChaosL1} —
   * only the inner annotations do. This method unpacks the container and returns the individual
   * annotations in order.
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
          if (componentType.isAnnotationPresent(ChaosL1.class)) {
            // This is a repeatable container — unwrap it
            final Annotation[] inner = (Annotation[]) valueMethod.invoke(candidate);
            for (final Annotation a : inner) {
              result.add(a);
            }
            continue;
          }
        }
      } catch (final NoSuchMethodException ignored) {
        // Not a container annotation
      } catch (final ReflectiveOperationException e) {
        log.warn("Could not inspect annotation {}", candidate.annotationType().getSimpleName(), e);
      }
      result.add(candidate);
    }
    return result;
  }

  private static List<AppliedL1> applyOne(
      final ChaosL1 meta,
      final Annotation annotation,
      final String idFilter,
      final List<ContainerHandle> containers,
      final ChaosApplicationReport report,
      final ChaosApplicationReport.Scope scope,
      final Class<?> testClass) {

    final L1Translator<Annotation> translator = resolveTranslator(meta, annotation, testClass);
    final OnMissingEnv onMissingEnv = extractOnMissingEnvAttribute(annotation);

    final List<ContainerHandle> matching =
        filterByContainerId(containers, idFilter, annotation, testClass);

    if (matching.isEmpty()) {
      throw noContainerError(annotation, testClass, idFilter, containers);
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
        return applied;
      }
    }
    return applied;
  }

  // ==================== Conflict detection (for method-level suspension) ====================

  /**
   * Extracts all entries from {@code persistentHandles} that match the given annotation type and
   * container. Matched entries are removed from the list in-place (caller is responsible for
   * removing the underlying rules from the container before discarding the handles).
   */
  private static List<AppliedL1> extractConflicts(
      final Class<? extends Annotation> annotationType,
      final GenericContainer<?> container,
      final List<AppliedL1> persistentHandles) {

    final List<AppliedL1> conflicts = new ArrayList<>();
    final Iterator<AppliedL1> it = persistentHandles.iterator();
    while (it.hasNext()) {
      final AppliedL1 h = it.next();
      if (h.annotation().annotationType().equals(annotationType) && h.container() == container) {
        conflicts.add(h);
        it.remove();
      }
    }
    return conflicts;
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

  private static OnMissingEnv extractOnMissingEnvAttribute(final Annotation annotation) {
    try {
      final Method m = annotation.annotationType().getMethod("onMissingEnv");
      final Object v = m.invoke(annotation);
      return v instanceof OnMissingEnv om ? om : OnMissingEnv.ERROR;
    } catch (final NoSuchMethodException e) {
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
      return containers;
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

  private static ExtensionConfigurationException noContainerError(
      final Annotation annotation,
      final Class<?> testClass,
      final String idFilter,
      final List<ContainerHandle> containers) {

    return devError(
        annotation,
        testClass,
        String.format(
            "no container matched id=\"%s\" (available ids: %s). "
                + "Ensure the L1 annotation's id() matches a container annotation's id().",
            idFilter, availableIds(containers)));
  }

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
   * Result of {@link #applyMethodLevelWithSuspension}: method-scope handles and the persistent
   * handles that were suspended (temporarily removed) to give way to the method overrides.
   *
   * @param methodHandles rules applied for this method; removed in {@code afterEach}
   * @param suspended persistent rules that were removed so the method rules could override them;
   *     must be {@link #reapply re-applied} in {@code afterEach} and added back to the persistent
   *     handle list
   */
  public record MethodLevelResult(List<AppliedL1> methodHandles, List<AppliedL1> suspended) {}

  /**
   * Internal projection of a started container plus its id, decoupling the processor from
   * {@code ChaosTestingExtension}'s private {@code ContainerInstance}.
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
