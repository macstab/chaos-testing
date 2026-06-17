/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.fsync;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.annotation.l1.fdatasync.ChaosFdatasyncLatency;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteLatency;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Delays every {@code fsync(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the durability barrier slower than the application
 * expects while still flushing all dirty pages to the storage device normally.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code FSYNC}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the fsync completes successfully. No probability gate is
 * applied; the delay fires on every intercepted {@code fsync} call. No runtime operation-effect
 * validation is needed.
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
 *   <li>On each intercepted {@code fsync} call the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Durability barriers take longer than normal; applications that use {@code fsync} to make
 *       writes durable before acknowledging a transaction will see increased transaction commit
 *       latency. Assert that the application's transaction commit deadline accounts for the
 *       injected delay.
 *   <li>WAL implementations that fsync the WAL file on every commit to guarantee durability will
 *       see each commit take at least {@link #delayMs} ms longer; assert that the transaction
 *       throughput drops proportionally and that the application degrades gracefully rather than
 *       timing out or queuing commits unboundedly.
 *   <li>Applications that use group commit (batching multiple transactions' data into a single
 *       fsync to amortise the disk flush cost) may see their batch window fill up before the fsync
 *       completes; assert that the group commit logic correctly handles a slow fsync without
 *       dropping pending transactions or sending commit acknowledgements before the fsync returns.
 *   <li>Assert that slow fsync calls do not block the application's write path: if the WAL fsync is
 *       on the critical path, a slow fsync stalls all new writes and exposes any unbounded write
 *       queue that fills during the stall.
 * </ul>
 *
 * <p>In production, slow {@code fsync} calls occur when the storage device's write cache is full
 * and the flush must wait for the device's internal buffer to drain, when the cgroup I/O bandwidth
 * throttle limits the rate at which dirty pages can be written to the device, and on NFS mounts
 * when the server must commit all pending writes before the client-side fsync can return.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fsync(2)} waits until all dirty pages associated with the file descriptor's inode have
 * been written to the storage device and the device has acknowledged the write. On HDDs, fsync
 * latency is dominated by rotational latency and seek time (3–10 ms). On SSDs, fsync latency is
 * dominated by the device's internal write commit cycle (0.05–1 ms). On NVMe SSDs with power-loss
 * protection, fsync typically completes in under 100 µs.
 *
 * <p>This injection adds the delay before the kernel call, simulating the case where the device is
 * under heavy load and the flush must wait for the I/O queue to drain. The delay fires before the
 * kernel call, so the actual storage flush still happens at full speed after the delay; the total
 * fsync latency is delay + actual flush time. This makes the injection additive with the real
 * device latency, which is the correct model for queue-induced latency.
 *
 * <p>Java's {@code FileDescriptor.sync()} and {@code FileChannel.force(boolean)} both call {@code
 * fsync(2)}. Applications that use {@code force(false)} (which calls {@code fdatasync}) rather than
 * {@code force(true)} (which calls {@code fsync}) will not be affected by this annotation but will
 * be affected by {@link ChaosFdatasyncLatency}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosFsyncLatency(delayMs = 100)
 * class FsyncLatencyTest {
 *   @Test
 *   void transactionCommitCompletesWithinDeadlineUnderSlowFsync() {
 *     // assert that transaction commit finishes within its deadline even when fsync is slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFdatasyncLatency
 * @see ChaosWriteLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosFsyncLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.FSYNC)
public @interface ChaosFsyncLatency {

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
   * @ChaosFsyncLatency(id = "primary",  probability = 0.001)
   * @ChaosFsyncLatency(id = "replica",  probability = 0.01)
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
    ChaosFsyncLatency[] value();
  }
}
