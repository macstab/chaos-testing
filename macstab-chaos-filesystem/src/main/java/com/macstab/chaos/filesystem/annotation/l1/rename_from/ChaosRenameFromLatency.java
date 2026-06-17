/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.rename_from;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.annotation.l1.rename_to.ChaosRenameToLatency;
import com.macstab.chaos.filesystem.annotation.l1.unlink.ChaosUnlinkLatency;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Delays every {@code rename(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the atomic file rename slower than the application
 * expects while still successfully completing the operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code RENAME_FROM}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the rename completes normally. No probability gate is applied;
 * the delay fires on every intercepted {@code rename} call. No runtime operation-effect validation
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
 *   <li>On each intercepted {@code rename} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The "write-to-temporary-then-rename" atomic update pattern relies on a fast rename to
 *       minimise the window during which readers see the old file content; injected delay extends
 *       this window significantly. Assert that readers that observe the old content during the
 *       window do not treat it as an error condition.
 *   <li>Log rotation implementations that rename the active log file to an archive name block the
 *       calling thread for the duration of the delay; assert that the log writer's timeout accounts
 *       for slow rename operations and that in-progress writes are not lost during the rename.
 *   <li>Applications that rename many files in sequence (directory-based queue consumers, batch
 *       file processors) accumulate delay across all renames; assert that the batch operation's
 *       overall timeout is calibrated for the worst-case number of renames per batch.
 *   <li>Assert that a slow rename on a background worker thread does not block the main request
 *       processing path; rename should be performed asynchronously or with a deadline that protects
 *       the calling thread from unbounded blocking.
 * </ul>
 *
 * <p>In production, slow {@code rename} calls occur on network filesystems (NFS, CIFS) where
 * directory entry modifications must be acknowledged by the server before the client-side rename
 * returns, on overloaded storage backends under heavy write pressure, and when the directory's data
 * blocks are not cached and must be fetched from disk to locate and modify the entry.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code rename(2)} is a single atomic filesystem operation that simultaneously removes the old
 * name from the source directory and adds the new name to the destination directory. On a local
 * filesystem with warm caches (directory blocks resident in the page cache), the operation
 * completes in microseconds — it requires only in-memory directory updates and a journal entry. The
 * latency injection simulates the case where the directory blocks are cold or the storage device is
 * under pressure, making the journal commit take orders of magnitude longer.
 *
 * <p>On NFS, the rename requires a round-trip to the server for the directory modification; the
 * client blocks until the server acknowledges the atomic update. NFS server-side rename latency can
 * easily reach hundreds of milliseconds under load. Applications that assume rename is "instant"
 * because it is instant on local storage will fail timeouts in NFS-mounted environments.
 *
 * <p>The delay is injected before the kernel call, so the calling thread is blocked for the full
 * {@link #delayMs} plus the actual kernel rename time. For applications that hold a lock across the
 * rename (a common pattern for atomic update sequences), the lock is held for the duration of the
 * injected delay, potentially starving other threads waiting for the same lock.
 *
 * <p>Java's {@code Files.move(Path, Path, CopyOption...)} with {@code ATOMIC_MOVE} calls {@code
 * rename(2)} directly and returns only after the kernel call completes. The injected delay adds
 * directly to the wall-clock time of {@code Files.move()}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosRenameFromLatency(delayMs = 100)
 * class RenameFromLatencyTest {
 *   @Test
 *   void atomicUpdateCompletesWithinDeadlineUnderSlowRename() {
 *     // assert that write-to-temp-then-rename finishes within its SLA even when rename is slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosRenameToLatency
 * @see ChaosUnlinkLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosRenameFromLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.RENAME_FROM)
public @interface ChaosRenameFromLatency {

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
   * @ChaosRenameFromLatency(id = "primary",  probability = 0.001)
   * @ChaosRenameFromLatency(id = "replica",  probability = 0.01)
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
    ChaosRenameFromLatency[] value();
  }
}
