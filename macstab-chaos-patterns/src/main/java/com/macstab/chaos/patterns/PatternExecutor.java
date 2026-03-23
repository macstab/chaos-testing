/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background executor for chaos patterns.
 *
 * <p>Executes patterns in background thread pool, non-blocking.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class PatternExecutor {
  private static final ScheduledExecutorService EXECUTOR =
      Executors.newScheduledThreadPool(
          Runtime.getRuntime().availableProcessors(),
          r -> {
            final Thread t = new Thread(r, "chaos-pattern-executor");
            t.setDaemon(true);
            return t;
          });

  private PatternExecutor() {
    // Utility class
  }

  /**
   * Execute pattern in background.
   *
   * @param pattern pattern to execute
   * @param operation operation to apply
   * @param totalDuration pattern duration
   * @param sampleInterval sampling interval
   * @param <T> value type
   * @return execution handle
   */
  public static <T> PatternExecution execute(
      final ChaosPattern<T> pattern,
      final ValueConsumer<T> operation,
      final Duration totalDuration,
      final Duration sampleInterval) {

    Objects.requireNonNull(pattern, "pattern must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(totalDuration, "totalDuration must not be null");
    Objects.requireNonNull(sampleInterval, "sampleInterval must not be null");

    final AtomicBoolean stopped = new AtomicBoolean(false);
    final long startTimeNanos = System.nanoTime();

    final CompletableFuture<Void> future =
        CompletableFuture.runAsync(
            () -> {
              log.info("Starting pattern execution: duration={}", totalDuration);

              pattern
                  .generate(totalDuration, sampleInterval)
                  .forEach(
                      timedValue -> {
                        if (stopped.get()) {
                          log.debug("Pattern execution stopped");
                          return;
                        }

                        // Sleep until timestamp
                        final long targetNanos = startTimeNanos + timedValue.timestamp().toNanos();
                        final long nowNanos = System.nanoTime();
                        final long sleepNanos = targetNanos - nowNanos;

                        if (sleepNanos > 0) {
                          try {
                            TimeUnit.NANOSECONDS.sleep(sleepNanos);
                          } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Pattern execution interrupted");
                            return;
                          }
                        }

                        // Apply operation
                        try {
                          operation.accept(timedValue.value());
                          log.debug("Applied pattern value: {}", timedValue.value());
                        } catch (final Exception e) {
                          log.error(
                              "Pattern operation failed at {}: {}",
                              timedValue.timestamp(),
                              e.getMessage(),
                              e);
                          throw new RuntimeException("Pattern operation failed", e);
                        }
                      });

              log.info("Pattern execution completed");
            },
            EXECUTOR);

    return new PatternExecutionImpl(future, stopped);
  }

  private static final class PatternExecutionImpl implements PatternExecution {
    private final CompletableFuture<Void> future;
    private final AtomicBoolean stopped;

    PatternExecutionImpl(final CompletableFuture<Void> future, final AtomicBoolean stopped) {
      this.future = future;
      this.stopped = stopped;
    }

    @Override
    public void stop() {
      stopped.set(true);
      future.cancel(true);
    }

    @Override
    public void await() throws InterruptedException {
      try {
        future.join();
      } catch (final Exception e) {
        if (e.getCause() instanceof InterruptedException) {
          throw (InterruptedException) e.getCause();
        }
        throw e;
      }
    }

    @Override
    public boolean isRunning() {
      return !future.isDone() && !stopped.get();
    }
  }
}
