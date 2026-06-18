/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.write;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.annotation.l1.fsync.ChaosFsyncLatency;
import com.macstab.chaos.filesystem.annotation.l1.read.ChaosReadLatency;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Delays every {@code write(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making file writes slower than the application expects while
 * still persisting the written data normally.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code WRITE}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the data is written normally. No probability gate is applied;
 * the delay fires on every intercepted {@code write} call. No runtime operation-effect validation
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
 *   <li>On each intercepted {@code write} call the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>File write operations take longer than normal; applications that write on request critical
 *       paths (logging, audit trail, WAL writes, checkpoint files) will see increased latency on
 *       those paths. Assert that the application's write deadline or timeout accounts for the
 *       injected delay.
 *   <li>Applications that perform many small writes per transaction (one write per WAL record, one
 *       write per log line) accumulate the delay across all write calls; assert that the total
 *       transaction timeout is calibrated for the worst-case number of writes per transaction.
 *   <li>Buffered writers (Java's {@code BufferedOutputStream}, {@code BufferedWriter}) batch
 *       multiple logical writes into a single underlying write call; assert that the application
 *       uses buffered writes to reduce the number of write calls and therefore the accumulated
 *       latency.
 *   <li>Assert that slow writes do not cause a cascade where the thread holding a write lock blocks
 *       other threads from making progress — write latency can expose lock contention that is
 *       invisible when writes are fast.
 * </ul>
 *
 * <p>In production, slow {@code write} calls occur when cgroup I/O bandwidth throttling limits the
 * process's write rate, when the filesystem is under heavy write pressure from other processes and
 * the kernel must wait for the page cache to drain, and when the storage device's write cache is
 * full and subsequent writes must wait for the device to flush its internal buffer.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>For buffered writes (the default in Linux), {@code write(2)} copies data into the kernel's
 * page cache and returns without waiting for the data to reach the storage device. The write is
 * fast as long as the page cache has space and the process is not throttled by the kernel's
 * writeback pressure mechanism. When the dirty page ratio exceeds {@code
 * vm.dirty_background_ratio}, the kernel starts writeback; when it exceeds {@code vm.dirty_ratio},
 * subsequent writes block until the dirty ratio drops.
 *
 * <p>For synchronous writes ({@code O_SYNC} or {@code O_DSYNC}), each write waits for the data to
 * be flushed to the storage device before returning. In this case, the write latency equals the
 * storage device's write latency plus the kernel's processing time. This injection simulates the
 * synchronous write latency without requiring actual slow storage.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosWriteLatency(delayMs = 100)
 * class WriteLatencyTest {
 *   @Test
 *   void transactionCommitCompletesWithinDeadlineUnderSlowStorage() {
 *     // assert that transaction commit finishes within its deadline even when writes are slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadLatency
 * @see ChaosFsyncLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosWriteLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.WRITE)
public @interface ChaosWriteLatency {

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
   * @ChaosWriteLatency(id = "primary",  probability = 0.001)
   * @ChaosWriteLatency(id = "replica",  probability = 0.01)
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
    ChaosWriteLatency[] value();
  }
}
