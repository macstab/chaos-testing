/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import java.util.Objects;

import lombok.NonNull;

/**
 * Abstract sealed base for all {@link ToxicConfig} implementations, providing shared state, shared
 * validation, and a reusable builder scaffold via the Template Method pattern.
 *
 * <h2>Why This Class Exists: Eliminating Structural Duplication</h2>
 *
 * <p>All six concrete toxic implementations ({@link LatencyToxic}, {@link TimeoutToxic}, {@link
 * BandwidthToxic}, {@link SlowCloseToxic}, {@link LimitDataToxic}, {@link DownToxic}) share an
 * identical structural skeleton: two common fields ({@code name}, {@code toxicity}), the same
 * validation logic for those fields, the same builder pattern, and the same {@link ToxicConfig}
 * method implementations for {@link ToxicConfig#name()} and {@link ToxicConfig#toxicity()}. Without
 * this base class, each of the six implementations would repeat ~80 lines of structurally identical
 * code. The result: O(1) changes to shared logic (e.g., toxicity range validation) instead of O(6).
 *
 * <h2>Pattern: Template Method (GoF)</h2>
 *
 * <p>This class embodies the Template Method pattern. The invariant structure (field storage,
 * common validation, common builder setters) is defined here. The type-specific behavior — {@link
 * ToxicConfig#type()} and {@link ToxicConfig#toJson()} — is delegated to concrete subclasses as
 * abstract methods. Neither {@code type()} nor {@code toJson()} has a sensible default at this
 * level, so they remain abstract.
 *
 * <h2>Sealed Chain: Two-Level Hierarchy</h2>
 *
 * <p>The sealed chain is:
 *
 * <pre>
 * ToxicConfig (sealed, permits AbstractToxic)
 *   └── AbstractToxic (sealed, permits 6 concrete classes)
 *         ├── LatencyToxic
 *         ├── TimeoutToxic
 *         ├── BandwidthToxic
 *         ├── SlowCloseToxic
 *         ├── LimitDataToxic
 *         └── DownToxic
 * </pre>
 *
 * <p>This two-level sealed chain means: (1) only {@code AbstractToxic} may implement {@code
 * ToxicConfig}, and (2) only the six named classes may extend {@code AbstractToxic}. The compiler
 * enforces both constraints. External modules cannot add new toxic types without modifying this
 * module's source — an intentional framework integrity guarantee.
 *
 * <h2>Builder Scaffold: AbstractBuilder</h2>
 *
 * <p>The self-referential generic {@link AbstractBuilder}{@code <B extends AbstractBuilder<B>>}
 * provides {@code name()} and {@code toxicity()} setters that return the concrete builder type
 * ({@code B}), enabling fluent method chaining without unchecked casts in the calling code. Each
 * concrete class defines its own builder that extends {@code AbstractBuilder} and adds
 * type-specific setters. The concrete builder's {@code build()} method calls {@code
 * super(builder)}, which validates common fields and populates them.
 *
 * <h2>Immutability</h2>
 *
 * <p>All fields in this class and all concrete subclasses are {@code final}. Once constructed via a
 * builder, a toxic configuration is immutable. This is intentional: toxic configurations are value
 * objects that represent a desired fault state, not mutable entities. Immutability makes them safe
 * to share across threads and to reuse across multiple proxy operations.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances are unconditionally thread-safe due to full immutability. The builder itself is NOT
 * thread-safe — it must not be shared across threads during construction (standard Builder pattern
 * usage).
 *
 * <h2>Validation Lifecycle</h2>
 *
 * <p>Common-field validation (null check on {@code name}, range check on {@code toxicity}) runs in
 * the {@link AbstractToxic#AbstractToxic(AbstractBuilder)} constructor, which is called by each
 * concrete class's constructor via {@code super(builder)}. Type-specific validation (e.g., {@code
 * rateKbps > 0} for {@link BandwidthToxic}) runs in the concrete class's constructor, after {@code
 * super(builder)} returns. This ordering ensures all invariants are checked before the object is
 * fully constructed.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ToxicConfig for the sealed interface contract
 * @see LatencyToxic for a concrete implementation example
 */
public abstract sealed class AbstractToxic implements ToxicConfig
    permits LatencyToxic, TimeoutToxic, BandwidthToxic, SlowCloseToxic, LimitDataToxic, DownToxic {

  private final String name;
  private final double toxicity;

  /**
   * Constructs common toxic state from the provided builder snapshot.
   *
   * <p>Called from each concrete subclass constructor via {@code super(builder)} immediately before
   * any type-specific field initialization. Validates {@code name} (not null) and {@code toxicity}
   * (in [0.0, 1.0]) and stores them in final fields. Any validation failure throws before the
   * object is half-constructed, ensuring instances are always fully valid.
   *
   * @param builder the subclass builder carrying the common field values; must not be null
   * @throws NullPointerException if builder or builder.name is null
   * @throws IllegalArgumentException if toxicity is outside [0.0, 1.0]
   */
  protected AbstractToxic(@NonNull final AbstractBuilder<?> builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.toxicity = builder.toxicity;
    validateToxicity(toxicity);
  }

  /**
   * Returns the unique name of this toxic within its proxy.
   *
   * <p>Final: the name is a core identity field and must not be overrideable by subclasses. It is
   * set once at construction and never changes.
   *
   * @return toxic name; never null, never blank
   */
  @Override
  public final String name() {
    return name;
  }

  /**
   * Returns the toxicity probability.
   *
   * <p>Final: toxicity semantics are uniform across all toxic types and must not be redefined.
   *
   * @return toxicity in [0.0, 1.0]; never outside this range
   */
  @Override
  public final double toxicity() {
    return toxicity;
  }

  // ==================== Shared Validation Helpers ====================

  /**
   * Validates that {@code toxicity} is within the valid range [0.0, 1.0].
   *
   * <p>Called by this class's constructor for the common {@code toxicity} field. Exposed as {@code
   * protected static} so concrete subclasses could call it for secondary toxicity-like fields if
   * needed, though no current implementation requires this.
   *
   * @param toxicity value to validate
   * @throws IllegalArgumentException if {@code toxicity < 0.0 || toxicity > 1.0}
   */
  protected static void validateToxicity(final double toxicity) {
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  /**
   * Validates that an {@code int} field is non-negative (≥ 0).
   *
   * <p>Used by concrete subclasses for fields like {@code latencyMs}, {@code timeoutMs}, {@code
   * delayMs} — values where zero is meaningful (no delay) but negative is nonsensical.
   *
   * @param value the value to validate
   * @param fieldName the field name, included in the exception message for diagnostics
   * @throws IllegalArgumentException if {@code value < 0}
   */
  protected static void validateNonNegative(final int value, final String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " must be >= 0, got: " + value);
    }
  }

  /**
   * Validates that a {@code long} field is non-negative (≥ 0).
   *
   * <p>Used by {@link LimitDataToxic} for the {@code bytes} field, where zero has the specific
   * meaning "close the connection immediately on establishment" and negative is invalid.
   *
   * @param value the value to validate
   * @param fieldName the field name, included in the exception message for diagnostics
   * @throws IllegalArgumentException if {@code value < 0}
   */
  protected static void validateNonNegative(final long value, final String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " must be >= 0, got: " + value);
    }
  }

  /**
   * Validates that an {@code int} field is strictly positive (&gt; 0).
   *
   * <p>Used by {@link BandwidthToxic} for {@code rateKbps}, where zero would mean "infinite
   * throttle" (no data throughput) — a semantically different state that should be represented
   * explicitly if needed, not inferred from zero.
   *
   * @param value the value to validate
   * @param fieldName the field name, included in the exception message for diagnostics
   * @throws IllegalArgumentException if {@code value <= 0}
   */
  protected static void validatePositive(final int value, final String fieldName) {
    if (value <= 0) {
      throw new IllegalArgumentException(fieldName + " must be > 0, got: " + value);
    }
  }

  // ==================== Abstract Builder Scaffold ====================

  /**
   * Abstract builder scaffold shared by all concrete toxic builders.
   *
   * <p>Provides the common fields ({@code name}, {@code toxicity}) with defaults ({@code toxicity =
   * 1.0}) and fluent setter methods that return the concrete builder type ({@code B}) via the
   * self-referential generic bound.
   *
   * <h2>Self-Referential Generic ({@code B extends AbstractBuilder<B>})</h2>
   *
   * <p>Without the self-referential generic, {@code name()} and {@code toxicity()} would return
   * {@code AbstractBuilder}, forcing concrete builder callers to chain through intermediate casts:
   *
   * <pre>
   * // Without self-referential generic — broken fluent API
   * LatencyToxic.builder()
   *     .name("x")         // returns AbstractBuilder, not Builder
   *     .latencyMs(100)    // compiler error: AbstractBuilder has no latencyMs()
   * </pre>
   *
   * With the self-referential generic, {@code name()} and {@code toxicity()} are declared to return
   * {@code B} (the concrete type), preserving the fluent chain:
   *
   * <pre>
   * LatencyToxic.builder()
   *     .name("x")         // returns LatencyToxic.Builder
   *     .latencyMs(100)    // compiles
   *     .build();
   * </pre>
   *
   * <p>The {@code @SuppressWarnings("unchecked")} on the cast {@code (B) this} is required because
   * the Java type system cannot prove {@code this} is of type {@code B} at compile time (the
   * self-referential generic is not reified at runtime). This is a standard Java Builder pattern
   * technique and the cast is safe provided subclasses correctly declare {@code Builder extends
   * AbstractBuilder<Builder>}.
   *
   * <h3>Thread Safety</h3>
   *
   * <p>Builder instances must not be shared across threads. They carry mutable state during
   * construction.
   *
   * @param <B> the concrete builder type; must extend {@code AbstractBuilder<B>}
   */
  @SuppressWarnings("unchecked")
  public abstract static class AbstractBuilder<B extends AbstractBuilder<B>> {

    private String name;
    private double toxicity = 1.0;

    /** Default constructor; sets {@code toxicity = 1.0} (all connections affected). */
    protected AbstractBuilder() {}

    /**
     * Sets the unique name for this toxic within its proxy.
     *
     * <p>Required field — not calling this before {@code build()} will cause {@link
     * AbstractToxic#AbstractToxic(AbstractBuilder)} to throw {@link NullPointerException}.
     *
     * <p>Name must not contain shell metacharacters or JSON special characters. Restrict to {@code
     * [a-zA-Z0-9_-]}.
     *
     * @param name toxic name; must not be null
     * @return this builder (concrete type {@code B}), enabling fluent chaining
     */
    public final B name(@NonNull final String name) {
      this.name = name;
      return (B) this;
    }

    /**
     * Sets the fraction of connections this toxic applies to.
     *
     * <p>Default is {@code 1.0} (all connections affected). See {@link ToxicConfig} class-level
     * Javadoc for statistical implications of sub-unity toxicity in tests with few connections.
     *
     * @param toxicity connection fraction in [0.0, 1.0]
     * @return this builder (concrete type {@code B})
     * @throws IllegalArgumentException if toxicity is outside [0.0, 1.0] — validation deferred to
     *     {@link AbstractToxic} constructor
     */
    public final B toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return (B) this;
    }
  }
}
