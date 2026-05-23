/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.rename_to;

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
 * Delays every {@code rename(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the atomic file rename slower than the application
 * expects while still successfully completing the operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_TO}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call after
 * the configured extra delay — the rename completes normally. No probability gate is applied; the
 * delay fires on every intercepted {@code rename} call. No runtime operation-effect validation is
 * needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.IO)} on the container definition causes the
 *       extension to upload {@code libchaos-io.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code open}, {@code read}, {@code write}, {@code close},
 *       {@code fsync}, {@code fdatasync}, {@code truncate}, {@code unlink}, {@code rename}, and
 *       {@code fallocate} at the dynamic-linker level.
 *   <li>On each intercepted {@code rename} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The "write-to-temporary-then-rename" atomic update pattern uses rename to atomically expose
 *       the new file content; an injected delay extends the window during which a reader observes
 *       the old content or no content at the destination path. Assert that readers tolerate this
 *       extended visibility window rather than treating a momentary stale read as a data error.
 *   <li>Log rotation implementations that rename the current log file before creating a new one
 *       block the caller for the duration of the delay; assert that the log writer's deadline
 *       accounts for slow rename operations and that no log records are lost during the rename.
 *   <li>Applications that rename output files to publish results — report writers, artifact
 *       publishers — block for the duration of the rename; assert that publication timeouts are
 *       set conservatively enough to tolerate storage-layer stalls.
 *   <li>Assert that a slow rename on a background thread does not starve the main request path
 *       by holding a lock across the rename; publish operations should be lock-free or use a
 *       dedicated thread with a bounded queue.
 * </ul>
 *
 * <p>In production, slow {@code rename} calls occur on network filesystems (NFS, SMB/CIFS) where
 * the server must acknowledge the directory modification before the client-side call returns, on
 * storage devices under write pressure where the journal commit is delayed by I/O queuing, and
 * on large directories where the directory data blocks must be read from disk to locate the
 * insertion point for the new entry.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} is a single atomic directory transaction: the kernel updates both the source
 * and destination directories in one journal commit. On a warmed page cache with small directories,
 * the commit takes microseconds — all modifications are in-memory and the journal write is fast.
 * Under storage pressure (journal writeback latency, write throttling, dirty page writeback), the
 * same operation can take hundreds of milliseconds while the calling thread is blocked in the kernel.
 *
 * <p>The latency injection simulates the storage-layer component of rename latency without actually
 * stressing the storage device. This allows controlled reproduction of scenarios where rename is
 * unexpectedly slow — for example, when a cloud provider's network-attached storage volume is
 * underprovisioned for the IOPS load of the workload.
 *
 * <p>The delay is injected before the real kernel call, so the calling thread is blocked for the
 * full {@link #delayMs} plus the actual kernel rename time. Applications that perform the rename
 * while holding a lock — a common pattern when updating a "latest result" symlink or file atomically
 * — will block all other threads waiting for that lock for the entire duration.
 *
 * <p>Java's {@code Files.move(Path, Path, CopyOption...)} with {@code ATOMIC_MOVE} calls
 * {@code rename(2)} and returns only when the kernel call completes. The injected delay adds
 * directly to the wall-clock time observable by the calling thread.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameToLatency(delayMs = 100)
 * class RenameToLatencyTest {
 *   @Test
 *   void publicationDeadlineToleratesSlowRenameToOutputDirectory() {
 *     // assert that write-to-temp-then-rename to output directory finishes within its SLA
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameFromLatency
 * @see ChaosUnlinkLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosRenameToLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.RENAME_TO)
public @interface ChaosRenameToLatency {

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
   * @ChaosRenameToLatency(id = "primary",  probability = 0.001)
   * @ChaosRenameToLatency(id = "replica",  probability = 0.01)
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
    ChaosRenameToLatency[] value();
  }
}
