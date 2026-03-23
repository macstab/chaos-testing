/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

/**
 * Handle for running pattern execution.
 *
 * <p>Allows stopping and awaiting pattern completion.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface PatternExecution {

  /**
   * Stop pattern execution immediately.
   *
   * <p>Does not block (cancels background thread).
   */
  void stop();

  /**
   * Wait for pattern execution to complete.
   *
   * <p>Blocks until pattern finishes or is stopped.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  void await() throws InterruptedException;

  /**
   * Wait for pattern execution to complete (unchecked version).
   *
   * <p>Wraps InterruptedException in RuntimeException for convenience.
   */
  default void awaitUninterruptibly() {
    try {
      await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Pattern execution interrupted", e);
    }
  }

  /**
   * Check if pattern is still running.
   *
   * @return {@code true} if running, {@code false} if completed/stopped
   */
  boolean isRunning();
}
