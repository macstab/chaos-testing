/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.fdatasync;

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
 * Delays every {@code fdatasync(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the data durability barrier slower than the
 * application expects while still flushing all dirty data pages to the storage device normally.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code FDATASYNC}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the fdatasync completes successfully. No probability gate is
 * applied; the delay fires on every intercepted {@code fdatasync} call. No runtime operation-effect
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
 *   <li>On each intercepted {@code fdatasync} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Data durability barriers take longer than normal; applications that use {@code fdatasync}
 *       to make WAL records durable before acknowledging a transaction commit will see increased
 *       commit latency. Assert that the application's transaction commit deadline accounts for the
 *       injected delay and that clients do not time out waiting for commit acknowledgements.
 *   <li>Applications that use group commit (batching multiple transactions' WAL records into a
 *       single fdatasync) will see the batch window fill up during the slow fdatasync; assert that
 *       the group commit logic handles a slow fdatasync by waiting for it to complete (not by
 *       issuing another fdatasync concurrently, which is ineffective).
 *   <li>Database engines that distinguish between fdatasync latency and fsync latency (using {@code
 *       fdatasync} for WAL commits and {@code fsync} for checkpoint completion) should be tested
 *       with this annotation for WAL-path latency and with {@link ChaosFsyncLatency} for
 *       checkpoint-path latency independently.
 *   <li>Assert that a slow fdatasync on the WAL file does not prevent the WAL writer from accepting
 *       new WAL records from other transactions — the write path should be non-blocking with
 *       respect to the fdatasync completion.
 * </ul>
 *
 * <p>In production, slow {@code fdatasync} calls occur under the same conditions as slow {@code
 * fsync} calls: storage device write cache full, cgroup I/O throttling, and NFS server latency. The
 * advantage of {@code fdatasync} over {@code fsync} is that it avoids flushing metadata (saving the
 * journal commit round-trip on journalled filesystems), making it faster under normal conditions.
 * Under I/O pressure, both are equally slow because the bottleneck is the data flush to the storage
 * device, not the metadata flush.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fdatasync(2)} waits until all dirty data pages associated with the file descriptor's
 * inode have been written to the storage device. Unlike {@code fsync(2)}, it does not wait for
 * updated metadata (modification time, inode timestamps) to be flushed unless the metadata is
 * required to make the data accessible (specifically, the file size if it changed). This makes
 * {@code fdatasync} approximately 10–30% faster than {@code fsync} on HDDs on journalled
 * filesystems, because the journal commit for metadata-only changes is skipped.
 *
 * <p>Java's {@code FileChannel.force(false)} calls {@code fdatasync(2)} on Linux; {@code
 * FileChannel.force(true)} calls {@code fsync(2)}. Many WAL implementations use {@code
 * force(false)} for WAL record commits (data durability is sufficient, metadata timing is not
 * critical) and {@code force(true)} only for checkpoint completion (where the inode update must
 * also be durable for crash recovery). This annotation exercises the {@code force(false)} path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosFdatasyncLatency(delayMs = 100)
 * class FdatasyncLatencyTest {
 *   @Test
 *   void walCommitCompletesWithinDeadlineUnderSlowFdatasync() {
 *     // assert that WAL record commit finishes within its deadline even when fdatasync is slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFsyncLatency
 * @see ChaosWriteLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosFdatasyncLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.FDATASYNC)
public @interface ChaosFdatasyncLatency {

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
   * @ChaosFdatasyncLatency(id = "primary",  probability = 0.001)
   * @ChaosFdatasyncLatency(id = "replica",  probability = 0.01)
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
    ChaosFdatasyncLatency[] value();
  }
}
