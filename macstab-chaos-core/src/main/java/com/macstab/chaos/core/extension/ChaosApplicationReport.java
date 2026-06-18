/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Per-test record of which L1 and L2 chaos annotations were applied vs skipped.
 *
 * <p>One report is built per test class. Class-scope and field-scope L1s are recorded once at
 * {@code beforeAll}; method-scope L1s are appended at each {@code beforeEach}. L2 scenarios are
 * recorded at the same lifecycle points. After-cleanup observers can pull the report from the
 * {@code ExtensionContext.Store} to log a summary, attach to test reports, or assert (in framework
 * integration tests) that the expected rules were applied.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ChaosApplicationReport {

  private final List<AppliedRule> applied = new ArrayList<>();
  private final List<SkippedRule> skipped = new ArrayList<>();
  private final List<AppliedL2Rule> l2Applied = new ArrayList<>();
  private final List<AppliedL3Rule> l3Applied = new ArrayList<>();

  /** Creates an empty report. Populated by {@code ChaosTestingExtension} during lifecycle. */
  public ChaosApplicationReport() {}

  /**
   * Records a successful application of an L1 annotation.
   *
   * @param annotation the L1 annotation that was applied (never {@code null})
   * @param scope class-scope or method-scope marker for diagnostic clarity
   */
  public void recordApplied(final Annotation annotation, final Scope scope) {
    Objects.requireNonNull(annotation, "annotation must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    applied.add(new AppliedRule(annotation, scope));
  }

  /**
   * Records an L1 annotation that was skipped because the environment couldn't honour it and the
   * annotation opted into {@link OnMissingEnv#ABORT}.
   *
   * @param annotation the L1 annotation that was skipped (never {@code null})
   * @param scope class-scope or method-scope marker
   * @param reason printable reason (the underlying exception's message)
   */
  public void recordSkipped(final Annotation annotation, final Scope scope, final String reason) {
    Objects.requireNonNull(annotation, "annotation must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    skipped.add(new SkippedRule(annotation, scope, reason));
  }

  /**
   * Records a successful application of an L2 scenario annotation.
   *
   * @param annotation the L2 annotation that was applied (never {@code null})
   * @param scope class-scope or method-scope marker
   */
  public void recordL2Applied(final Annotation annotation, final Scope scope) {
    Objects.requireNonNull(annotation, "annotation must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    l2Applied.add(new AppliedL2Rule(annotation, scope));
  }

  /**
   * Records a successful application of an L3 incident scenario annotation.
   *
   * @param annotation the L3 annotation that was applied (never {@code null})
   * @param scope class-scope or method-scope marker
   */
  public void recordL3Applied(final Annotation annotation, final Scope scope) {
    Objects.requireNonNull(annotation, "annotation must not be null");
    Objects.requireNonNull(scope, "scope must not be null");
    l3Applied.add(new AppliedL3Rule(annotation, scope));
  }

  /**
   * @return immutable view of applied L1 rules in the order they were applied
   */
  public List<AppliedRule> applied() {
    return Collections.unmodifiableList(applied);
  }

  /**
   * @return immutable view of skipped L1 rules with reasons
   */
  public List<SkippedRule> skipped() {
    return Collections.unmodifiableList(skipped);
  }

  /**
   * @return immutable view of applied L2 scenarios in the order they were applied
   */
  public List<AppliedL2Rule> l2Applied() {
    return Collections.unmodifiableList(l2Applied);
  }

  /**
   * @return immutable view of applied L3 incident scenarios in the order they were applied
   */
  public List<AppliedL3Rule> l3Applied() {
    return Collections.unmodifiableList(l3Applied);
  }

  /**
   * @return one-line summary suitable for logging at info level
   */
  public String summary() {
    return String.format(
        "L1 Applied: %d | L1 Skipped (OnMissingEnv=ABORT): %d | L2 Applied: %d | L3 Applied: %d",
        applied.size(), skipped.size(), l2Applied.size(), l3Applied.size());
  }

  /** Distinguishes class-level L1s (applied once per test class) from method-level (per test). */
  public enum Scope {
    /** L1 annotation declared on the test class — applied once at {@code beforeAll}. */
    CLASS,
    /**
     * L1 annotation declared on a field of the test class — applied once at {@code beforeAll}; the
     * field name is used as the implicit container id when {@code id()} is empty.
     */
    FIELD,
    /**
     * L1 annotation declared on a {@code @Test} method — applied per invocation at {@code
     * beforeEach}.
     */
    METHOD
  }

  /**
   * @param annotation the applied L1 annotation
   * @param scope class- or method-scope
   */
  public record AppliedRule(Annotation annotation, Scope scope) {}

  /**
   * @param annotation the skipped L1 annotation
   * @param scope class- or method-scope
   * @param reason printable reason taken from the environment-unavailability exception's message
   */
  public record SkippedRule(Annotation annotation, Scope scope, String reason) {}

  /**
   * @param annotation the applied L2 scenario annotation
   * @param scope class- or method-scope
   */
  public record AppliedL2Rule(Annotation annotation, Scope scope) {}

  /**
   * @param annotation the applied L3 incident scenario annotation
   * @param scope class- or method-scope
   */
  public record AppliedL3Rule(Annotation annotation, Scope scope) {}
}
