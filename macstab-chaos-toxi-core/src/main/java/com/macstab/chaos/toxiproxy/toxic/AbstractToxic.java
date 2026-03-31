/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import java.util.Objects;

import lombok.NonNull;

/**
 * Abstract base for all {@link ToxicConfig} implementations.
 *
 * <p>Centralizes the three fields shared by every toxic ({@link #name}, {@link #toxicity}),
 * the common validation logic, and the {@link AbstractBuilder} scaffold — eliminating the
 * structural duplication that would otherwise exist across all six concrete implementations.
 *
 * <h2>Responsibilities</h2>
 *
 * <ul>
 *   <li>Stores and validates {@code name} and {@code toxicity}.
 *   <li>Provides {@link #name()} and {@link #toxicity()} implementations.
 *   <li>Provides {@link AbstractBuilder} with {@code name()} and {@code toxicity()} setter methods
 *       so subclass builders only add their own domain fields.
 * </ul>
 *
 * <h2>Subclass Contract</h2>
 *
 * <p>Each concrete subclass must:
 * <ol>
 *   <li>Implement {@link ToxicConfig#type()} — return the Toxiproxy type string.
 *   <li>Implement {@link ToxicConfig#toJson()} — return the <strong>attributes-only</strong>
 *       JSON object (e.g., {@code {"latency":100,"jitter":0}}). The outer envelope
 *       ({@code name}, {@code type}, {@code toxicity}) is built by the API client.
 *   <li>Validate any domain-specific fields in the subclass constructor.
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public abstract sealed class AbstractToxic implements ToxicConfig
    permits LatencyToxic, TimeoutToxic, BandwidthToxic, SlowCloseToxic, LimitDataToxic, DownToxic {

  private final String name;
  private final double toxicity;

  /**
   * Constructs an abstract toxic from a builder.
   *
   * @param builder the subclass builder (must not be null)
   * @throws NullPointerException if builder or builder.name is null
   * @throws IllegalArgumentException if toxicity is outside [0.0, 1.0]
   */
  protected AbstractToxic(@NonNull final AbstractBuilder<?> builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.toxicity = builder.toxicity;
    validateToxicity(toxicity);
  }

  @Override
  public final String name() {
    return name;
  }

  @Override
  public final double toxicity() {
    return toxicity;
  }

  // ==================== Shared Validation ====================

  /**
   * Validate toxicity probability is within [0.0, 1.0].
   *
   * @param toxicity value to validate
   * @throws IllegalArgumentException if out of range
   */
  protected static void validateToxicity(final double toxicity) {
    if (toxicity < 0.0 || toxicity > 1.0) {
      throw new IllegalArgumentException(
          String.format("toxicity must be in [0.0, 1.0], got: %.2f", toxicity));
    }
  }

  /**
   * Validate a non-negative integer field.
   *
   * @param value the value to validate
   * @param fieldName the field name (for error messages)
   * @throws IllegalArgumentException if negative
   */
  protected static void validateNonNegative(final int value, final String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " must be >= 0, got: " + value);
    }
  }

  /**
   * Validate a non-negative long field.
   *
   * @param value the value to validate
   * @param fieldName the field name (for error messages)
   * @throws IllegalArgumentException if negative
   */
  protected static void validateNonNegative(final long value, final String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " must be >= 0, got: " + value);
    }
  }

  /**
   * Validate a strictly positive integer field.
   *
   * @param value the value to validate
   * @param fieldName the field name (for error messages)
   * @throws IllegalArgumentException if not positive
   */
  protected static void validatePositive(final int value, final String fieldName) {
    if (value <= 0) {
      throw new IllegalArgumentException(fieldName + " must be > 0, got: " + value);
    }
  }

  // ==================== Abstract Builder ====================

  /**
   * Abstract builder scaffold shared by all toxic builders.
   *
   * <p>Provides {@code name} and {@code toxicity} setters with consistent defaults:
   * {@code toxicity=1.0} (all connections). Subclass builders call {@code super()} implicitly
   * and only add their own domain-specific fields and setters.
   *
   * @param <B> the concrete builder type (self-referential for fluent API)
   */
  @SuppressWarnings("unchecked")
  public abstract static class AbstractBuilder<B extends AbstractBuilder<B>> {

    private String name;
    private double toxicity = 1.0;

    /** Default constructor — sets {@code toxicity=1.0}. */
    protected AbstractBuilder() {}

    /**
     * Set the unique name for this toxic within its proxy.
     *
     * <p>Required. Each toxic on the same proxy must have a unique name.
     *
     * @param name unique toxic name (must not be null)
     * @return this builder
     */
    public final B name(@NonNull final String name) {
      this.name = name;
      return (B) this;
    }

    /**
     * Set the fraction of connections this toxic applies to.
     *
     * <p>Default: {@code 1.0} (all connections). Range: [0.0, 1.0].
     * Use values below {@code 1.0} to simulate intermittent failures.
     *
     * @param toxicity connection fraction (0.0 = none, 1.0 = all)
     * @return this builder
     * @throws IllegalArgumentException if outside [0.0, 1.0]
     */
    public final B toxicity(final double toxicity) {
      this.toxicity = toxicity;
      return (B) this;
    }
  }
}
