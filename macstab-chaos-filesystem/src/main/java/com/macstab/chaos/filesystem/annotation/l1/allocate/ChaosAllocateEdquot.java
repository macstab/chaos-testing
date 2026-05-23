/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.allocate;

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
 * Injects {@code EDQUOT} into {@code fallocate(2)}, causing the call to return {@code -1} with
 * {@code errno = EDQUOT} as if the user's or group's disk quota has been exceeded and the kernel
 * cannot pre-allocate the requested disk space for the file.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ALLOCATE}, errno = {@code EDQUOT})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted
 * {@code fallocate} call; when it fires the interposer returns {@code -1} with {@code errno = EDQUOT}
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
 *   <li>On each intercepted {@code fallocate} call a Bernoulli trial with probability {@link #probability}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EDQUOT}, simulating a quota-exceeded condition at block pre-allocation time.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EDQUOT} from {@code fallocate} means the quota manager rejected the block allocation
 *       before any data was written; the file's size and content are unchanged. Assert that the
 *       application treats this as a quota-exceeded error (not a disk-full error) and notifies the
 *       user or operator that quota limits need to be raised before retrying.
 *   <li>Database engines that use {@code fallocate} to pre-allocate WAL segment files must handle
 *       {@code EDQUOT} at segment creation time; assert that the database does not attempt to write
 *       to a segment that failed pre-allocation and that the transaction in progress is rolled back
 *       cleanly.
 *   <li>Applications that pre-allocate large output files before writing (avoiding fragmentation)
 *       must handle {@code EDQUOT} as a hard stop; assert that the application falls back to writing
 *       without pre-allocation or aborts with a clear quota-exceeded error rather than a generic I/O
 *       error.
 *   <li>Assert that the application distinguishes {@code EDQUOT} from {@code ENOSPC}: quota
 *       exhaustion is per-user or per-group (the quota can be raised without freeing space), while
 *       disk-full requires either deleting files or expanding the filesystem.
 * </ul>
 *
 * <p>In production, {@code EDQUOT} from {@code fallocate} occurs in multi-tenant environments where
 * disk quotas are enforced per service account, in Kubernetes clusters with per-pod storage limits
 * enforced through project quotas, and in enterprise storage systems where each department has
 * capacity allocations enforced at the filesystem level.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code fallocate(2)} with {@code FALLOC_FL_KEEP_SIZE} or without it allocates real disk blocks
 * for a file region without writing any data. This allows applications to guarantee that future
 * writes to the allocated region will not fail with {@code ENOSPC}. The quota check occurs before
 * any blocks are reserved: the kernel computes whether the requested allocation would push the
 * caller's block usage beyond the hard quota limit and returns {@code EDQUOT} immediately if so.
 *
 * <p>On ext4 and XFS, the quota accounting tracks both block usage and inode usage. {@code fallocate}
 * only increases block usage (not inode count), so {@code EDQUOT} from {@code fallocate} indicates
 * that the block quota limit has been reached. Applications that perform their own free-space
 * estimation using {@code statfs(2)} will not see the quota limit — {@code statfs} reports filesystem-wide
 * free space, not per-user quota headroom. Only the actual allocation call exposes the quota limit.
 *
 * <p>Java's NIO {@code FileChannel} does not expose {@code fallocate} directly. Applications that
 * use native code or JNI to call {@code fallocate} must handle the returned error through their
 * native bridge; the JVM layer will throw an {@code IOException} wrapping the native error. Some
 * database embedded in the JVM (like RocksDB or SQLite via JDBC) use {@code fallocate} through JNI
 * and may surface {@code EDQUOT} as a generic storage exception.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosAllocateEdquot(probability = 0.001)
 * class AllocateEdquotTest {
 *   @Test
 *   void walSegmentPreallocationFailsGracefullyOnQuotaExhaustion() {
 *     // assert that EDQUOT on fallocate aborts the WAL segment creation cleanly
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAllocateEnospc
 * @see ChaosWriteEdquot
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosAllocateEdquot.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.ALLOCATE, errno = Errno.EDQUOT)
public @interface ChaosAllocateEdquot {

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
   * @ChaosAllocateEdquot(id = "primary",  probability = 0.001)
   * @ChaosAllocateEdquot(id = "replica",  probability = 0.01)
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
    ChaosAllocateEdquot[] value();
  }
}
