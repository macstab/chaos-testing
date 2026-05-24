/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pwrite;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Delays every {@code pwrite(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making positional file writes slower than the application
 * expects while still persisting the data normally at the requested offset.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PWRITE}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the data is written normally. No probability gate is applied;
 * the delay fires on every intercepted {@code pwrite} call. No runtime operation-effect validation
 * is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.IO)} on the container definition causes the extension
 *       to upload {@code libchaos-io.so} into the container and prepend it to {@code LD_PRELOAD}
 *       before the process starts.
 *   <li>The shared library interposes {@code open}, {@code read}, {@code write}, {@code close},
 *       {@code fsync}, {@code fdatasync}, {@code truncate}, {@code unlink}, {@code rename}, and
 *       {@code fallocate} at the dynamic-linker level.
 *   <li>On each intercepted {@code pwrite} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Positional file write operations take longer than normal; applications that use {@code
 *       pwrite} for random-access page writes (B-tree page updates, WAL record placement, heap file
 *       page writes) will see increased latency per write. Assert that the application's
 *       transaction commit deadline accounts for the accumulated delay across all page writes in
 *       the transaction.
 *   <li>Database engines that write to multiple pages per transaction using concurrent worker
 *       threads each incur the delay independently; a transaction that modifies N pages from N
 *       writer threads accumulates N × {@link #delayMs} of total wait time. Assert that the
 *       transaction timeout is calibrated for the worst-case number of page writes per transaction.
 *   <li>Applications that hold a write lock while performing {@code pwrite} calls will block other
 *       transactions from acquiring the lock for the duration of the delay; assert that the lock
 *       timeout is longer than the maximum expected {@code pwrite} latency under slow storage, and
 *       that the lock holder releases it correctly even when writes are slow.
 *   <li>Assert that slow {@code pwrite} calls do not cause the WAL checkpointer to time out,
 *       leaving stale WAL entries that prevent log truncation and cause the WAL to grow
 *       unboundedly.
 * </ul>
 *
 * <p>In production, slow {@code pwrite} calls occur when cgroup I/O bandwidth throttling limits the
 * process's write rate and each write must wait for its I/O token budget to replenish, when the
 * storage device's write cache is full and subsequent writes must wait for the device to flush its
 * internal buffer, and when a network filesystem introduces per-RPC latency due to server load or
 * network congestion.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code pwrite(2)} is the positional variant of {@code write(2)}, writing to a caller-supplied
 * offset without modifying the file's current position. This makes it the preferred write primitive
 * for multi-threaded random-access workloads such as database page writers. For buffered I/O, the
 * kernel accepts data into the page cache without waiting for the storage device; the write latency
 * is bounded by the memory bandwidth and the kernel's lock on the page cache entry. For direct I/O
 * ({@code O_DIRECT}), the write waits for the storage device to acknowledge the write, making the
 * latency proportional to the device's write latency.
 *
 * <p>This injection adds the delay before the kernel call, simulating the scheduling stall and I/O
 * queue depth that occurs under write pressure without requiring actual slow storage. The delay
 * fires on every {@code pwrite} call regardless of whether the write hits an existing page or
 * allocates new blocks; on a fast system with low write pressure this makes the injection more
 * severe than real slow storage (where only allocation-heavy writes are slow).
 *
 * <p>Java's {@code FileChannel.write(ByteBuffer, long)} maps directly to {@code pwrite(2)} on
 * Linux. When multiple threads call {@code channel.write(buf, offset)} concurrently, each call
 * independently incurs the injected delay. Thread pool sizing assumptions tuned for fast storage
 * may result in thread starvation when every thread is blocked on a delayed {@code pwrite}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPwriteLatency(delayMs = 50)
 * class PwriteLatencyTest {
 *   @Test
 *   void transactionCommitCompletesWithinDeadlineUnderSlowPageWrites() {
 *     // assert that transaction commit finishes within its deadline even when page writes are slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteLatency
 * @see ChaosPreadLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosPwriteLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.PWRITE)
public @interface ChaosPwriteLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 50L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-io
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosPwriteLatency(id = "primary",  probability = 0.001)
   * @ChaosPwriteLatency(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosPwriteLatency[] value();
  }
}
