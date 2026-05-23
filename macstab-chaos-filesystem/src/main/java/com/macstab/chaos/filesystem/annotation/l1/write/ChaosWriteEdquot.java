/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.write;

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
 * Injects {@code EDQUOT} into {@code write(2)}, causing the call to return {@code -1} with
 * {@code errno = EDQUOT} as if the user's disk quota on this filesystem has been exceeded and the
 * kernel cannot allocate additional blocks for the write operation.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code WRITE}, errno = {@code EDQUOT})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code write} call; when it fires the interposer returns {@code -1} with {@code errno = EDQUOT}
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
 *   <li>On each intercepted {@code write} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EDQUOT}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EDQUOT} from {@code write} is similar to {@code ENOSPC} from the application's
 *       perspective: no additional data can be written. However, the remediation is different —
 *       {@code EDQUOT} requires quota increase or data deletion, while {@code ENOSPC} requires
 *       disk expansion or cleanup. Assert that the application's error message distinguishes
 *       "quota exceeded" from "disk full" to help operators apply the correct fix.
 *   <li>Applications that write user-generated content must handle {@code EDQUOT} gracefully by
 *       rejecting the write and informing the user that their storage quota is exhausted; assert
 *       that the rejection is user-visible rather than silently dropped.
 *   <li>Log-writing paths must handle {@code EDQUOT} by falling back to stderr or a lower-priority
 *       log destination; assert that the logger does not crash or enter an infinite retry loop.
 *   <li>Assert that the application emits a "quota exceeded" metric or alert with the affected
 *       user's identity, enabling operators to identify which tenant is consuming excessive storage.
 * </ul>
 *
 * <p>In production, {@code EDQUOT} from {@code write} occurs in multi-tenant environments where
 * filesystem quotas are enforced per user or per group ({@code quota(1)}, {@code repquota(8)}),
 * in Kubernetes environments that use project quotas on XFS or ext4 volumes to limit per-namespace
 * storage consumption, and in shared NFS environments where the NFS server enforces per-user quotas.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Disk quotas are enforced at two levels: block quotas (limits the total number of filesystem
 * blocks a user can allocate) and inode quotas (limits the number of files a user can create).
 * When {@code write} would cause the user's block usage to exceed the hard limit, the kernel
 * returns {@code EDQUOT}. If the user is between the soft limit and the hard limit, the write
 * succeeds but a grace timer starts; when the timer expires, writes return {@code EDQUOT}
 * even if the hard limit has not been reached.
 *
 * <p>The distinction between {@code EDQUOT} and {@code ENOSPC}: {@code EDQUOT} is per-user or
 * per-group, while {@code ENOSPC} is filesystem-wide. A user can receive {@code EDQUOT} even
 * when the filesystem has free space (because the user's quota is exhausted), and a user can
 * receive {@code ENOSPC} even when they have quota remaining (because the filesystem itself is
 * full). Applications that treat both as equivalent "cannot write" errors should use the
 * {@code strerror} message or the errno value to log the specific cause for operator diagnostics.
 *
 * <p>Java maps {@code EDQUOT} from {@code write} to an {@code IOException} with the message
 * "Disk quota exceeded". The same {@code IOException} type is used for all write-side IO errors;
 * application code that needs to distinguish quota exhaustion from disk exhaustion must inspect
 * the exception message or use a platform-specific API to query the quota status.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosWriteEdquot(probability = 0.1)
 * class WriteEdquotTest {
 *   @Test
 *   void quotaExceededIsReportedWithOperableErrorDistinctFromDiskFull() {
 *     // assert that the error message says "quota exceeded" not "no space left on device"
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteEnospc
 * @see ChaosPwriteEdquot
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosWriteEdquot.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.WRITE, errno = Errno.EDQUOT)
public @interface ChaosWriteEdquot {

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
   * @ChaosWriteEdquot(id = "primary",  probability = 0.001)
   * @ChaosWriteEdquot(id = "replica",  probability = 0.01)
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
    ChaosWriteEdquot[] value();
  }
}
