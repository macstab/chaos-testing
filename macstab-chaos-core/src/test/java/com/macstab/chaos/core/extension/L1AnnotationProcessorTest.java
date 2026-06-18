/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.opentest4j.TestAbortedException;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;

@DisplayName("L1AnnotationProcessor")
class L1AnnotationProcessorTest {

  // ==================== Test fixtures ====================

  /** Translator FQN base for the test-fixture translators below. */
  private static final String FQN_BASE = L1AnnotationProcessorTest.class.getName() + "$";

  /** Test fixture: L1 annotation that delegates to {@link SuccessTranslator}. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
  @ChaosL1(
      translator = "com.macstab.chaos.core.extension.L1AnnotationProcessorTest$SuccessTranslator")
  public @interface FixtureSuccess {
    String id() default "";

    OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
  }

  /** Test fixture: L1 annotation whose translator throws {@link IllegalArgumentException}. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @ChaosL1(
      translator = "com.macstab.chaos.core.extension.L1AnnotationProcessorTest$DevErrorTranslator")
  public @interface FixtureDevError {
    String id() default "";

    OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
  }

  /**
   * Test fixture: L1 annotation whose translator throws {@link LibchaosNotPreparedException}.
   * OnMissingEnv defaults to ERROR; tests can declare a method-local subclass to flip to ABORT.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @ChaosL1(
      translator =
          "com.macstab.chaos.core.extension.L1AnnotationProcessorTest$LibchaosMissingTranslator")
  public @interface FixtureEnvMissingError {
    String id() default "";

    OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
  }

  /** Same translator, default OnMissingEnv=ABORT. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @ChaosL1(
      translator =
          "com.macstab.chaos.core.extension.L1AnnotationProcessorTest$LibchaosMissingTranslator")
  public @interface FixtureEnvMissingAbort {
    String id() default "";

    OnMissingEnv onMissingEnv() default OnMissingEnv.ABORT;
  }

  /** Test fixture: L1 annotation whose translator's FQN doesn't resolve. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @ChaosL1(translator = "com.macstab.chaos.does.not.exist.NoSuchTranslator")
  public @interface FixtureMissingTranslator {
    String id() default "";

    OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
  }

  /** Test fixture: plain annotation without {@link ChaosL1} — must be ignored. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  public @interface FixtureNonL1 {}

  public static final class SuccessTranslator implements L1Translator<Annotation> {
    public static final AtomicInteger APPLY_COUNT = new AtomicInteger();
    public static final AtomicInteger REMOVE_COUNT = new AtomicInteger();

    public SuccessTranslator() {}

    @Override
    public Object apply(final GenericContainer<?> container, final Annotation annotation) {
      APPLY_COUNT.incrementAndGet();
      return "handle-" + APPLY_COUNT.get();
    }

    @Override
    public void remove(final GenericContainer<?> container, final Object handle) {
      REMOVE_COUNT.incrementAndGet();
    }
  }

  public static final class DevErrorTranslator implements L1Translator<Annotation> {
    public DevErrorTranslator() {}

    @Override
    public Object apply(final GenericContainer<?> container, final Annotation annotation) {
      throw new IllegalArgumentException("probability must be in (0.0, 1.0]");
    }

    @Override
    public void remove(final GenericContainer<?> container, final Object handle) {}
  }

  public static final class LibchaosMissingTranslator implements L1Translator<Annotation> {
    public LibchaosMissingTranslator() {}

    @Override
    public Object apply(final GenericContainer<?> container, final Annotation annotation) {
      // (libShortName, dockerImageName) constructor — message will contain "libchaos-mem"
      throw new LibchaosNotPreparedException("mem", "alpine:latest");
    }

    @Override
    public void remove(final GenericContainer<?> container, final Object handle) {}
  }

  public static final class RemoveThrowingTranslator implements L1Translator<Annotation> {
    public static final AtomicInteger APPLY_COUNT = new AtomicInteger();

    public RemoveThrowingTranslator() {}

    @Override
    public Object apply(final GenericContainer<?> container, final Annotation annotation) {
      APPLY_COUNT.incrementAndGet();
      return "handle";
    }

    @Override
    public void remove(final GenericContainer<?> container, final Object handle) {
      throw new RuntimeException("cleanup intentionally fails");
    }
  }

  // ==================== Sample-bearing classes ====================

  @FixtureSuccess
  static class WithSuccess {}

  @FixtureSuccess(id = "db1")
  static class WithSuccessIdDb1 {}

  @FixtureDevError
  static class WithDevError {}

  @FixtureEnvMissingError
  static class WithEnvMissingError {}

  @FixtureEnvMissingAbort
  static class WithEnvMissingAbort {}

  @FixtureMissingTranslator
  static class WithMissingTranslator {}

  @FixtureNonL1
  static class WithNonL1 {}

  // ==================== Helpers ====================

  private static L1AnnotationProcessor.ContainerHandle handle(final String id) {
    final GenericContainer<?> container = mock(GenericContainer.class);
    return new L1AnnotationProcessor.ContainerHandle(
        container, id, MockChaosPlugin.MockContainer.class);
  }

  private static ChaosApplicationReport newReport() {
    return new ChaosApplicationReport();
  }

  // ==================== Happy path ====================

  @Nested
  @DisplayName("happy path — class-level")
  class HappyPathClass {

    @Test
    @DisplayName("applies one annotation to one container; report records APPLIED")
    void applies() {
      SuccessTranslator.APPLY_COUNT.set(0);
      SuccessTranslator.REMOVE_COUNT.set(0);

      final ChaosApplicationReport report = newReport();
      final List<L1AnnotationProcessor.AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithSuccess.class, List.of(handle("default")), report);

      assertThat(applied).hasSize(1);
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(1);
      assertThat(report.applied()).hasSize(1);
      assertThat(report.skipped()).isEmpty();
    }

    @Test
    @DisplayName("annotation without @ChaosL1 meta is silently ignored")
    void nonL1Ignored() {
      final ChaosApplicationReport report = newReport();
      final List<L1AnnotationProcessor.AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithNonL1.class, List.of(handle("default")), report);

      assertThat(applied).isEmpty();
      assertThat(report.applied()).isEmpty();
    }
  }

  // ==================== Container id filtering ====================

  @Nested
  @DisplayName("container id filtering")
  class IdFiltering {

    @Test
    @DisplayName("id=\"\" applies to every container")
    void emptyIdAppliesToAll() {
      SuccessTranslator.APPLY_COUNT.set(0);

      final ChaosApplicationReport report = newReport();
      L1AnnotationProcessor.applyClassLevel(
          WithSuccess.class, List.of(handle("db1"), handle("db2")), report);

      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(2);
      assertThat(report.applied()).hasSize(2);
    }

    @Test
    @DisplayName("id=\"db1\" applies only to the matching container")
    void specificIdAppliesToOne() {
      SuccessTranslator.APPLY_COUNT.set(0);

      final ChaosApplicationReport report = newReport();
      L1AnnotationProcessor.applyClassLevel(
          WithSuccessIdDb1.class, List.of(handle("db1"), handle("db2")), report);

      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(1);
      assertThat(report.applied()).hasSize(1);
    }

    @Test
    @DisplayName("id with no match → ExtensionConfigurationException listing available ids")
    void idMismatchFailsLoud() {
      assertThatThrownBy(
              () ->
                  L1AnnotationProcessor.applyClassLevel(
                      WithSuccessIdDb1.class,
                      List.of(handle("primary"), handle("replica")),
                      newReport()))
          .isInstanceOf(ExtensionConfigurationException.class)
          .hasMessageContaining("@FixtureSuccess")
          .hasMessageContaining("no container matched id=\"db1\"")
          .hasMessageContaining("\"primary\"")
          .hasMessageContaining("\"replica\"");
    }
  }

  // ==================== Developer-error path ====================

  @Nested
  @DisplayName("developer errors — always hard fail")
  class DevErrors {

    @Test
    @DisplayName(
        "translator IllegalArgumentException → ExtensionConfigurationException with context")
    void devErrorWrapped() {
      assertThatThrownBy(
              () ->
                  L1AnnotationProcessor.applyClassLevel(
                      WithDevError.class, List.of(handle("default")), newReport()))
          .isInstanceOf(ExtensionConfigurationException.class)
          .hasMessageContaining("@FixtureDevError")
          .hasMessageContaining("WithDevError")
          .hasMessageContaining("invalid attribute")
          .hasMessageContaining("probability must be in");
    }

    @Test
    @DisplayName(
        "translator class not on classpath → ExtensionConfigurationException with build hint")
    void translatorClassMissing() {
      assertThatThrownBy(
              () ->
                  L1AnnotationProcessor.applyClassLevel(
                      WithMissingTranslator.class, List.of(handle("default")), newReport()))
          .isInstanceOf(ExtensionConfigurationException.class)
          .hasMessageContaining("L1 translator class")
          .hasMessageContaining("not found")
          .hasMessageContaining("testImplementation");
    }
  }

  // ==================== Environment-unavailability routing ====================

  @Nested
  @DisplayName("OnMissingEnv routing")
  class EnvRouting {

    @Test
    @DisplayName("ERROR + libchaos missing → ExtensionConfigurationException (RED)")
    void errorRouting() {
      assertThatThrownBy(
              () ->
                  L1AnnotationProcessor.applyClassLevel(
                      WithEnvMissingError.class, List.of(handle("default")), newReport()))
          .isInstanceOf(ExtensionConfigurationException.class)
          .hasMessageContaining("environment cannot honour this primitive")
          .hasMessageContaining("libchaos-mem was not prepared");
    }

    @Test
    @DisplayName("ABORT + libchaos missing → TestAbortedException (YELLOW); report records SKIPPED")
    void abortRouting() {
      final ChaosApplicationReport report = newReport();
      assertThatThrownBy(
              () ->
                  L1AnnotationProcessor.applyClassLevel(
                      WithEnvMissingAbort.class, List.of(handle("default")), report))
          .isInstanceOf(TestAbortedException.class)
          .hasMessageContaining("environment cannot honour this primitive");

      assertThat(report.skipped()).hasSize(1);
      assertThat(report.skipped().get(0).reason()).contains("libchaos-mem was not prepared");
      assertThat(report.applied()).isEmpty();
    }

    @Test
    @DisplayName("ABORT also honours ChaosUnsupportedOperationException")
    void abortRoutingForUnsupportedOp() {
      // Re-using the env-missing fixture with a translator override that throws ChaosUnsupportedOp.
      // Done via an inline test-class with its own translator.
      assertThatThrownBy(
              () ->
                  L1AnnotationProcessor.applyClassLevel(
                      WithUnsupportedAbort.class, List.of(handle("default")), newReport()))
          .isInstanceOf(TestAbortedException.class);
    }
  }

  // Inline annotation/translator for ChaosUnsupportedOperationException coverage.
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @ChaosL1(
      translator =
          "com.macstab.chaos.core.extension.L1AnnotationProcessorTest$UnsupportedOpTranslator")
  public @interface FixtureUnsupportedAbort {
    String id() default "";

    OnMissingEnv onMissingEnv() default OnMissingEnv.ABORT;
  }

  public static final class UnsupportedOpTranslator implements L1Translator<Annotation> {
    public UnsupportedOpTranslator() {}

    @Override
    public Object apply(final GenericContainer<?> container, final Annotation annotation) {
      throw new ChaosUnsupportedOperationException(
          "Toxiproxy backend doesn't support libchaos-net verb");
    }

    @Override
    public void remove(final GenericContainer<?> container, final Object handle) {}
  }

  @FixtureUnsupportedAbort
  static class WithUnsupportedAbort {}

  // ==================== Cleanup behaviour ====================

  @Nested
  @DisplayName("removeAll")
  class Cleanup {

    @Test
    @DisplayName("calls remove for every handle; returns true on full success")
    void cleanRemove() {
      SuccessTranslator.APPLY_COUNT.set(0);
      SuccessTranslator.REMOVE_COUNT.set(0);

      final ChaosApplicationReport report = newReport();
      final List<L1AnnotationProcessor.AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithSuccess.class, List.of(handle("a"), handle("b")), report);

      final boolean allOk = L1AnnotationProcessor.removeAll(applied);

      assertThat(allOk).isTrue();
      assertThat(SuccessTranslator.REMOVE_COUNT).hasValue(2);
    }

    @Test
    @DisplayName("swallows remove() exceptions; returns false signalling reset fallback")
    void removeThrowingSignalsReset() {
      RemoveThrowingTranslator.APPLY_COUNT.set(0);

      final ChaosApplicationReport report = newReport();
      final List<L1AnnotationProcessor.AppliedL1> applied =
          L1AnnotationProcessor.applyClassLevel(
              WithRemoveThrowing.class, List.of(handle("default")), report);

      final boolean allOk = L1AnnotationProcessor.removeAll(applied);

      assertThat(allOk).isFalse();
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @ChaosL1(
      translator =
          "com.macstab.chaos.core.extension.L1AnnotationProcessorTest$RemoveThrowingTranslator")
  public @interface FixtureRemoveThrowing {
    String id() default "";

    OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
  }

  @FixtureRemoveThrowing
  static class WithRemoveThrowing {}

  // ==================== Field-scope fixture classes ====================

  /** Field annotated with @FixtureSuccess — no co-located container annotation. */
  static class WithFieldAnnotation {
    @FixtureSuccess Object someField;
  }

  /** Field annotated with @FixtureSuccess AND a fake container annotation co-located. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  @interface FakeContainer {
    String id() default "";
  }

  static class WithColocatedContainerAnnotation {
    @FakeContainer(id = "primary")
    @FixtureSuccess
    Object primary;

    @FakeContainer(id = "replica")
    @FixtureSuccess
    Object replica;
  }

  static class WithColocatedNoIdContainer {
    @FakeContainer @FixtureSuccess Object theContainer;
  }

  /** Only the primary field has @FixtureSuccess — the replica class rule must survive. */
  static class WithOnlyPrimaryFieldAnnotation {
    @FakeContainer(id = "primary")
    @FixtureSuccess
    Object primary;

    @FakeContainer(id = "replica")
    Object replica;
  }

  // ==================== Method-level scope ====================

  @Nested
  @DisplayName("method-level scope")
  class MethodLevel {

    @FixtureSuccess
    void fixtureMethod() {}

    @Test
    @DisplayName("walks annotations on the @Test method, applies, records as METHOD scope")
    void appliesMethodLevel() throws Exception {
      SuccessTranslator.APPLY_COUNT.set(0);

      final ChaosApplicationReport report = newReport();
      final List<L1AnnotationProcessor.AppliedL1> persistent = new ArrayList<>();
      final L1AnnotationProcessor.MethodLevelResult result =
          L1AnnotationProcessor.applyMethodLevelWithSuspension(
              MethodLevel.class.getDeclaredMethod("fixtureMethod"),
              List.of(handle("default")),
              persistent,
              report);

      assertThat(result.methodHandles()).hasSize(1);
      assertThat(result.suspended()).isEmpty();
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(1);
      assertThat(report.applied()).hasSize(1);
      assertThat(report.applied().get(0).scope()).isEqualTo(ChaosApplicationReport.Scope.METHOD);
    }

    @FixtureSuccess
    void conflictMethod() {}

    @Test
    @DisplayName(
        "method annotation conflicts with persistent rule: suspends persistent, applies method rule")
    void methodSuspendsConflictingPersistentRule() throws Exception {
      SuccessTranslator.APPLY_COUNT.set(0);
      SuccessTranslator.REMOVE_COUNT.set(0);

      final L1AnnotationProcessor.ContainerHandle ch = handle("default");
      final ChaosApplicationReport classReport = newReport();

      // Build a class-level persistent handle for the same annotation type + container
      final List<L1AnnotationProcessor.AppliedL1> persistent =
          new ArrayList<>(
              L1AnnotationProcessor.applyClassLevel(WithSuccess.class, List.of(ch), classReport));
      assertThat(persistent).hasSize(1);
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(1);

      // Now apply method level — same annotation type, same container → suspend class rule
      final ChaosApplicationReport methodReport = newReport();
      final L1AnnotationProcessor.MethodLevelResult result =
          L1AnnotationProcessor.applyMethodLevelWithSuspension(
              MethodLevel.class.getDeclaredMethod("conflictMethod"),
              List.of(ch),
              persistent,
              methodReport);

      // Persistent rule was removed from the list and from the container
      assertThat(persistent).isEmpty();
      assertThat(SuccessTranslator.REMOVE_COUNT).hasValue(1);

      // Method rule was applied
      assertThat(result.methodHandles()).hasSize(1);
      assertThat(result.suspended()).hasSize(1);
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(2);
    }

    @Test
    @DisplayName("reapply restores suspended handles and returns new AppliedL1 entries")
    void reapplyRestoresSuspendedHandles() throws Exception {
      SuccessTranslator.APPLY_COUNT.set(0);
      SuccessTranslator.REMOVE_COUNT.set(0);

      final L1AnnotationProcessor.ContainerHandle ch = handle("default");
      final ChaosApplicationReport report = newReport();

      // Create a persistent handle to suspend
      final List<L1AnnotationProcessor.AppliedL1> persistent =
          new ArrayList<>(
              L1AnnotationProcessor.applyClassLevel(WithSuccess.class, List.of(ch), report));

      // Suspend it via method-level conflict
      final L1AnnotationProcessor.MethodLevelResult result =
          L1AnnotationProcessor.applyMethodLevelWithSuspension(
              MethodLevel.class.getDeclaredMethod("conflictMethod"),
              List.of(ch),
              persistent,
              newReport());

      assertThat(result.suspended()).hasSize(1);
      final int applyBeforeReapply = SuccessTranslator.APPLY_COUNT.get();

      // Re-apply the suspended handles (simulates afterEach restoration)
      final List<L1AnnotationProcessor.AppliedL1> restored =
          L1AnnotationProcessor.reapply(result.suspended());

      assertThat(restored).hasSize(1);
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(applyBeforeReapply + 1);
    }
  }

  // ==================== Field-level scope ====================

  @Nested
  @DisplayName("field-level scope")
  class FieldLevel {

    @Test
    @DisplayName(
        "applies annotation on a field using field name as id fallback when no container annotation")
    void fieldNameFallbackAsId() {
      SuccessTranslator.APPLY_COUNT.set(0);

      final L1AnnotationProcessor.ContainerHandle ch = handle("someField");
      final ChaosApplicationReport report = newReport();
      final List<L1AnnotationProcessor.AppliedL1> classHandles = new ArrayList<>();

      final List<L1AnnotationProcessor.AppliedL1> applied =
          L1AnnotationProcessor.applyFieldLevel(
              WithFieldAnnotation.class, List.of(ch), Set.of(), classHandles, report);

      assertThat(applied).hasSize(1);
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(1);
      assertThat(report.applied()).hasSize(1);
      assertThat(report.applied().get(0).scope()).isEqualTo(ChaosApplicationReport.Scope.FIELD);
    }

    @Test
    @DisplayName("uses co-located container annotation id, not field name")
    void colocatedContainerIdTakesPriority() {
      SuccessTranslator.APPLY_COUNT.set(0);

      final L1AnnotationProcessor.ContainerHandle primary = handle("primary");
      final L1AnnotationProcessor.ContainerHandle replica = handle("replica");
      final ChaosApplicationReport report = newReport();
      final List<L1AnnotationProcessor.AppliedL1> classHandles = new ArrayList<>();

      final List<L1AnnotationProcessor.AppliedL1> applied =
          L1AnnotationProcessor.applyFieldLevel(
              WithColocatedContainerAnnotation.class,
              List.of(primary, replica),
              Set.of(FakeContainer.class),
              classHandles,
              report);

      // One rule per field → one per container (primary, replica)
      assertThat(applied).hasSize(2);
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(2);
    }

    @Test
    @DisplayName("field rule permanently displaces conflicting class-level rule")
    void fieldDisplacesClassRule() {
      SuccessTranslator.APPLY_COUNT.set(0);
      SuccessTranslator.REMOVE_COUNT.set(0);

      final L1AnnotationProcessor.ContainerHandle primary = handle("primary");
      final L1AnnotationProcessor.ContainerHandle replica = handle("replica");
      final ChaosApplicationReport classReport = newReport();

      // Class-level: same annotation type applies to both containers
      final List<L1AnnotationProcessor.AppliedL1> classHandles =
          new ArrayList<>(
              L1AnnotationProcessor.applyClassLevel(
                  WithSuccess.class, List.of(primary, replica), classReport));
      assertThat(classHandles).hasSize(2); // class rule on both containers
      assertThat(SuccessTranslator.APPLY_COUNT).hasValue(2);

      // Field-level: only "primary" field has @FixtureSuccess → displaces class rule for primary
      // only
      final List<L1AnnotationProcessor.AppliedL1> fieldHandles =
          L1AnnotationProcessor.applyFieldLevel(
              WithOnlyPrimaryFieldAnnotation.class,
              List.of(primary, replica),
              Set.of(FakeContainer.class),
              classHandles, // mutable — primary class rule will be removed
              classReport);

      // Class rule for "primary" was removed; "replica" class rule survives
      assertThat(classHandles).hasSize(1);
      assertThat(classHandles.get(0).container()).isSameAs(replica.container());
      assertThat(SuccessTranslator.REMOVE_COUNT).hasValue(1); // only primary class rule displaced

      // Field rule applied for primary only
      assertThat(fieldHandles).hasSize(1);
    }
  }
}
