/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pwrite;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code EDQUOT} into {@code pwrite(2)}, causing the call to return {@code -1} with
 * {@code errno = EDQUOT} as if the user's disk quota on this filesystem has been exceeded and the
 * kernel cannot allocate additional blocks for the positional write operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PWRITE}, errno = {@code EDQUOT})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code pwrite} call; when it fires the interposer returns {@code -1} with {@code errno = EDQUOT}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code pwrite} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EDQUOT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EDQUOT} from {@code pwrite} means the user's storage quota is exhausted; the write
 *       at the requested offset was not performed. Assert that the application does not proceed as
 *       if the write succeeded and reports the quota-exceeded condition rather than silently
 *       dropping data.
 *   <li>Database engines that use {@code pwrite} for page writes must handle {@code EDQUOT} by
 *       aborting the in-progress transaction and refusing new write transactions until the quota
 *       condition is resolved; assert that no transaction is marked committed when the page write
 *       failed due to quota exhaustion.
 *   <li>Applications that write user-generated content directly to a file at a specific offset
 *       (resumable upload targets, append-only journals) must handle {@code EDQUOT} gracefully by
 *       rejecting the write and informing the user that their storage quota is exhausted.
 *   <li>Assert that the application's error message distinguishes "quota exceeded" from "disk full"
 *       to help operators apply the correct remediation — quota increase versus disk expansion.
 * </ul>
 *
 * <p>In production, {@code EDQUOT} from {@code pwrite} occurs in multi-tenant environments where
 * filesystem quotas are enforced per user or per group, in Kubernetes environments using XFS or
 * ext4 project quotas to limit per-namespace storage, and when an NFS server enforces per-user
 * quotas and the NFS client propagates the quota error as {@code EDQUOT}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code pwrite(2)} writes {@code count} bytes from the caller's buffer to file descriptor
 * {@code fd} at offset {@code offset} without modifying the file's current position. Like
 * {@code write(2)}, it must allocate storage blocks when the write extends the file or fills a
 * sparse region. The kernel's quota subsystem checks the user's block allocation before each new
 * block is allocated; when the write would cause the user's block usage to exceed the hard limit,
 * the kernel returns {@code EDQUOT} for the entire {@code pwrite} call.
 *
 * <p>Database storage engines prefer {@code pwrite} over {@code lseek} + {@code write} for page
 * writes because {@code pwrite} is thread-safe: multiple threads can issue concurrent page writes
 * to different offsets without locking the file position. An {@code EDQUOT} error on any of these
 * concurrent writes must be propagated to the transaction layer, which must then abort the affected
 * transaction and mark the storage as degraded. Engines that use a write-ahead log must also abort
 * and re-checkpoint the log to avoid leaving the storage in an inconsistent state.
 *
 * <p>Java's {@code FileChannel.write(ByteBuffer, long)} maps to {@code pwrite(2)} on Linux. When
 * the underlying call returns {@code EDQUOT}, the JVM throws an {@code IOException} with the
 * message "Disk quota exceeded". Application code that catches {@code IOException} and retries
 * indefinitely on quota exhaustion will loop forever; it should apply a retry budget or escalate
 * to an operator alert.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPwriteEdquot(probability = 0.1)
 * class PwriteEdquotTest {
 *   @Test
 *   void quotaExceededOnPageWriteAbortsTransactionNotCommits() {
 *     // assert that EDQUOT on pwrite causes transaction abort rather than silent data loss
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteEdquot
 * @see ChaosPwriteEnospc
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosPwriteEdquot.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.PWRITE, errno = Errno.EDQUOT)
public @interface ChaosPwriteEdquot {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

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
   * @ChaosPwriteEdquot(id = "primary",  probability = 0.001)
   * @ChaosPwriteEdquot(id = "replica",  probability = 0.01)
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
    ChaosPwriteEdquot[] value();
  }
}
