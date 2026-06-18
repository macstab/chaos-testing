/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.close;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding;
import com.macstab.chaos.filesystem.annotation.l1.fdatasync.ChaosFdatasyncEio;
import com.macstab.chaos.filesystem.annotation.l1.fsync.ChaosFsyncEio;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Injects {@code EIO} into {@code close(2)}, causing the call to return {@code -1} with {@code
 * errno = EIO} as if the kernel's final flush of dirty pages for the file descriptor failed due to
 * a storage device error — indicating that buffered writes made before this {@code close} may not
 * have been persisted to the storage device.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code CLOSE}, errno = {@code EIO})
 * tuple. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * close} call; when it fires the interposer returns {@code -1} with {@code errno = EIO} without
 * performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On each intercepted {@code close} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = EIO}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code EIO} from {@code close(2)} is one of the most dangerous errors in the Linux I/O
 *       model: even when {@code close} returns {@code EIO}, the file descriptor is closed and
 *       cannot be used again. The application cannot retry the close or re-issue the flush; the
 *       dirty pages that failed to flush may be lost. Assert that the application treats {@code
 *       close}-time {@code EIO} as a durable write failure and does not assume the data was
 *       persisted.
 *   <li>Applications that use the "write and close" idiom (write data, close the file, then rename
 *       the temporary file over the target) must check the return value of {@code close} and must
 *       not perform the rename if {@code close} returns an error. Assert that the rename is only
 *       performed after a successful close.
 *   <li>Java's {@code FileOutputStream.close()} and {@code FileChannel.close()} suppress the error
 *       from the underlying {@code close} syscall and always return without throwing; the {@code
 *       EIO} is silently discarded. Assert that the application uses an explicit {@code fsync}
 *       before closing to surface the error through a path that Java does propagate.
 *   <li>Assert that the application's write path uses {@code fsync}/{@code fdatasync} before {@code
 *       close} so that write errors are detected on the {@code fsync} call (which Java does
 *       propagate) rather than silently lost on the {@code close} call.
 * </ul>
 *
 * <p>In production, {@code EIO} from {@code close} occurs when buffered writes that were accepted
 * into the page cache fail during the final writeback triggered by {@code close}: the storage
 * device returns a medium error for the dirty pages that must be flushed. On Linux, the file
 * descriptor is always closed even when {@code close} returns an error; the error indicates that
 * some or all of the buffered data was not persisted.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The Linux kernel's {@code close(2)} syscall releases the file description associated with the
 * file descriptor. For regular files with buffered writes, the kernel flushes the file's dirty
 * pages during {@code close} as a courtesy (not a guarantee); if the flush fails, {@code close}
 * returns {@code EIO}. Critically, the file descriptor is still closed regardless of the error —
 * unlike other syscalls, a retried {@code close} on an already-closed descriptor would operate on a
 * recycled file descriptor, causing data corruption.
 *
 * <p>POSIX specifies that a failed {@code close} leaves the state of the file descriptor
 * implementation-defined; on Linux, the fd is always released. This makes {@code close}-time {@code
 * EIO} particularly insidious: the application cannot recover by retrying, and the error may only
 * surface if the application explicitly checks {@code close}'s return value (which many
 * applications, including some standard library wrappers, do not).
 *
 * <p>Java's {@code FileOutputStream.close()} calls the JVM's {@code FileDescriptor.closeAll()}
 * which eventually calls the native {@code close} syscall but discards its return value. The {@code
 * EIO} from the underlying syscall is therefore silently lost. Applications that rely on Java's
 * close path to detect write errors should instead call {@code FileChannel.force(true)} or
 * equivalent before closing, as these methods do propagate I/O errors.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosCloseEio(probability = 0.001)
 * class CloseEioTest {
 *   @Test
 *   void writePathUsesExplicitFsyncToDetectStorageErrorsBeforeClose() {
 *     // assert that the write path calls fsync before close so errors are not silently lost
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFsyncEio
 * @see ChaosFdatasyncEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoErrnoBinding
 */
@Repeatable(ChaosCloseEio.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoErrnoTranslator")
@IoErrnoBinding(operation = IoOperation.CLOSE, errno = Errno.EIO)
public @interface ChaosCloseEio {

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
   * @ChaosCloseEio(id = "primary",  probability = 0.001)
   * @ChaosCloseEio(id = "replica",  probability = 0.01)
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
    ChaosCloseEio[] value();
  }
}
