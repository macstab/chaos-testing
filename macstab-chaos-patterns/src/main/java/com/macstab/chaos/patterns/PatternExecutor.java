/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.patterns;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * Background executor for chaos patterns.
 *
 * <p>Schedules each {@link TimedValue} as a discrete callback on a shared {@link
 * ScheduledExecutorService} — no thread sleeps between samples. One pool serves many concurrent
 * patterns; idle patterns do not occupy threads.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class PatternExecutor {

  private static final ScheduledExecutorService DEFAULT_EXECUTOR =
      Executors.newScheduledThreadPool(
          Math.max(2, Runtime.getRuntime().availableProcessors()),
          r -> {
            final Thread t = new Thread(r, "chaos-pattern-executor");
            t.setDaemon(true);
            return t;
          });

  private PatternExecutor() {
    // Utility class
  }

  /**
   * Execute pattern in background with the {@link FailurePolicy#ABORT default} failure policy.
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
    return execute(pattern, operation, totalDuration, sampleInterval, FailurePolicy.ABORT);
  }

  /**
   * Execute pattern in background with an explicit failure policy.
   *
   * @param pattern pattern to execute
   * @param operation operation to apply
   * @param totalDuration pattern duration
   * @param sampleInterval sampling interval
   * @param failurePolicy abort/continue behaviour on {@link ValueConsumer} exceptions
   * @param <T> value type
   * @return execution handle
   */
  public static <T> PatternExecution execute(
      final ChaosPattern<T> pattern,
      final ValueConsumer<T> operation,
      final Duration totalDuration,
      final Duration sampleInterval,
      final FailurePolicy failurePolicy) {
    return execute(
        pattern, operation, totalDuration, sampleInterval, failurePolicy, DEFAULT_EXECUTOR);
  }

  /**
   * Execute pattern on a caller-supplied executor — useful for test isolation or when scheduling
   * many patterns without sharing the shared pool.
   *
   * @param pattern pattern to execute
   * @param operation operation to apply
   * @param totalDuration pattern duration
   * @param sampleInterval sampling interval
   * @param failurePolicy abort/continue behaviour on {@link ValueConsumer} exceptions
   * @param executor scheduled executor (caller-owned; not shut down here)
   * @param <T> value type
   * @return execution handle
   */
  public static <T> PatternExecution execute(
      final ChaosPattern<T> pattern,
      final ValueConsumer<T> operation,
      final Duration totalDuration,
      final Duration sampleInterval,
      final FailurePolicy failurePolicy,
      final ScheduledExecutorService executor) {

    Objects.requireNonNull(pattern, "pattern must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(totalDuration, "totalDuration must not be null");
    Objects.requireNonNull(sampleInterval, "sampleInterval must not be null");
    Objects.requireNonNull(failurePolicy, "failurePolicy must not be null");
    Objects.requireNonNull(executor, "executor must not be null");

    final AtomicBoolean stopped = new AtomicBoolean(false);
    final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    final CompletableFuture<Void> completion = new CompletableFuture<>();

    log.info("Starting pattern execution: duration={}", totalDuration);

    pattern
        .generate(totalDuration, sampleInterval)
        .forEach(
            tv -> {
              if (stopped.get()) {
                return;
              }
              final long delayNanos = Math.max(0L, tv.timestamp().toNanos());
              scheduled.add(
                  executor.schedule(
                      () -> applySample(tv, operation, stopped, consecutiveFailures, failurePolicy),
                      delayNanos,
                      TimeUnit.NANOSECONDS));
            });

    // Completion fires after totalDuration (or immediately if already stopped).
    final ScheduledFuture<?> sentinel =
        executor.schedule(
            () -> {
              completion.complete(null);
              log.info("Pattern execution completed");
            },
            totalDuration.toNanos(),
            TimeUnit.NANOSECONDS);
    scheduled.add(sentinel);

    return new PatternExecutionImpl(completion, stopped, scheduled);
  }

  /**
   * One scheduled sample callback. Honours the stop flag and the failure policy.
   *
   * @param tv timed value to apply
   * @param operation user operation
   * @param stopped shared stop flag
   * @param consecutiveFailures running counter of consecutive failed samples
   * @param failurePolicy abort threshold
   */
  private static <T> void applySample(
      final TimedValue<T> tv,
      final ValueConsumer<T> operation,
      final AtomicBoolean stopped,
      final AtomicInteger consecutiveFailures,
      final FailurePolicy failurePolicy) {
    if (stopped.get()) {
      return;
    }
    try {
      operation.accept(tv.value());
      consecutiveFailures.set(0);
      log.debug("Applied pattern value: {}", tv.value());
    } catch (final Exception e) {
      final int failures = consecutiveFailures.incrementAndGet();
      if (failures > failurePolicy.maxConsecutiveFailures()) {
        log.error(
            "Pattern aborted after {} consecutive failures (policy max={}): {}",
            failures,
            failurePolicy.maxConsecutiveFailures(),
            e.getMessage(),
            e);
        stopped.set(true);
      } else {
        log.warn(
            "Pattern sample failed ({} consecutive, policy max={}): {}",
            failures,
            failurePolicy.maxConsecutiveFailures(),
            e.getMessage());
      }
    }
  }

  private static final class PatternExecutionImpl implements PatternExecution {
    private final CompletableFuture<Void> completion;
    private final AtomicBoolean stopped;
    private final List<ScheduledFuture<?>> scheduled;

    PatternExecutionImpl(
        final CompletableFuture<Void> completion,
        final AtomicBoolean stopped,
        final List<ScheduledFuture<?>> scheduled) {
      this.completion = completion;
      this.stopped = stopped;
      this.scheduled = scheduled;
    }

    @Override
    public void stop() {
      stopped.set(true);
      // Cancel pending sample callbacks. mayInterruptIfRunning=false so an in-flight
      // operation completes naturally — user operations may touch container state and
      // should not be interrupted mid-call.
      for (final ScheduledFuture<?> f : scheduled) {
        f.cancel(false);
      }
      completion.complete(null);
    }

    @Override
    public void await() throws InterruptedException {
      try {
        completion.join();
      } catch (final Exception e) {
        if (e.getCause() instanceof InterruptedException ie) {
          throw ie;
        }
        throw e;
      }
    }

    @Override
    public boolean isRunning() {
      return !completion.isDone() && !stopped.get();
    }
  }
}
