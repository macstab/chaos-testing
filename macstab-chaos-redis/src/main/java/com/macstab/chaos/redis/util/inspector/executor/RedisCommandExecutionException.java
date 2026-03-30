/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.executor;

/**
 * Thrown when a {@link RedisCommandExecutor} fails to execute a Redis command.
 *
 * <p>Wraps underlying exceptions (shell exec failures, Lettuce exceptions, non-zero exit codes)
 * behind a single unchecked exception type, keeping inspector tool APIs clean.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class RedisCommandExecutionException extends RuntimeException {

  /**
   * Creates an exception with a descriptive message.
   *
   * @param message description of what failed
   */
  public RedisCommandExecutionException(final String message) {
    super(message);
  }

  /**
   * Creates an exception wrapping an underlying cause.
   *
   * @param message description of what failed
   * @param cause underlying exception
   */
  public RedisCommandExecutionException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
