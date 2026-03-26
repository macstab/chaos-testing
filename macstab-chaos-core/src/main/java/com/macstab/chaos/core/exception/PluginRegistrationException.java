/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

/**
 * Thrown when container plugin registration or discovery fails.
 *
 * <p><strong>Common Causes:</strong>
 *
 * <ul>
 *   <li>No {@code META-INF/services/com.macstab.chaos.core.extension.ChaosPlugin} entry
 *   <li>Plugin class not on classpath
 *   <li>Plugin constructor not public no-args
 *   <li>Duplicate plugin registrations for same annotation type
 *   <li>Plugin initialization throws exception
 * </ul>
 *
 * <p><strong>Example (Missing Plugin):</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * class RedisTest { ... }
 * // Throws: "No plugin registered for @RedisStandalone. Did you add META-INF/services entry?"
 * }</pre>
 *
 * <p><strong>Example (Duplicate Plugin):</strong>
 *
 * <pre>{@code
 * // Two plugins claim @RedisStandalone
 * // Throws: "Duplicate plugin registration for @RedisStandalone: RedisPluginA, RedisPluginB"
 * }</pre>
 *
 * <p><strong>Resolution:</strong>
 *
 * <ol>
 *   <li>Verify {@code META-INF/services/...ChaosPlugin} exists
 *   <li>Verify plugin class fully-qualified name matches file content
 *   <li>Verify plugin module is on classpath ({@code implementation(project(":module"))})
 *   <li>Check plugin constructor is public no-args
 *   <li>Check plugin initialization logs for errors
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class PluginRegistrationException extends RuntimeException {

  /**
   * Constructs exception with detailed message.
   *
   * @param message error message (operator-friendly, includes resolution hints)
   */
  public PluginRegistrationException(final String message) {
    super(message);
  }

  /**
   * Constructs exception with message and root cause.
   *
   * @param message error message
   * @param cause root cause (e.g., ServiceLoader failure, constructor exception)
   */
  public PluginRegistrationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
