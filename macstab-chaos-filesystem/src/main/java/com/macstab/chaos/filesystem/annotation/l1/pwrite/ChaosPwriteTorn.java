/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pwrite;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoTornBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Simulates a torn {@code pwrite(2)} by intercepting the call, performing only a partial write of
 * the caller's buffer at the requested offset, and returning the partial byte count — causing the
 * caller to observe a short write without any error, exactly as POSIX permits.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PWRITE}, effect = TORN) tuple.
 * Unlike errno variants, this primitive does not inject a failure — it performs a real but partial
 * write. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * pwrite} call; when it fires the interposer passes a randomly-chosen prefix of the buffer to the
 * real kernel call and returns the prefix length. No runtime operation-effect validation is needed.
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
 *   <li>On each intercepted {@code pwrite} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer selects a random split point
 *       within the buffer, issues the real {@code pwrite} syscall with only the prefix bytes at the
 *       original offset, and returns the partial count to the caller. The remaining bytes are not
 *       written.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>A torn {@code pwrite} returns a positive byte count smaller than the requested count at the
 *       requested file offset, which is valid POSIX behaviour. Applications that do not check the
 *       return value and loop to write the remaining bytes will silently leave a partial write in
 *       the file at a known offset. Assert that every {@code pwrite} loop correctly advances the
 *       offset and repeats until all bytes are written.
 *   <li>Database engines that write complete pages via a single {@code pwrite(fd, page, page_size,
 *       offset)} call may receive a partial count when the write is torn; a page header is written
 *       but the page body is missing, leaving the page region in a mixed state. Assert that the
 *       engine verifies the full page was written (either by checking the return count or by using
 *       {@code fsync} and then re-reading the page) before marking the write as complete.
 *   <li>WAL implementations that write a record to a known WAL file offset via {@code pwrite} must
 *       detect short writes and retry until the full record is written; assert that the WAL writer
 *       does not consider the record durable until all its bytes are confirmed at the target
 *       offset.
 *   <li>Assert that torn writes during a crash-recovery test leave the data file in a state that
 *       can be fully recovered — either the page is absent (not yet written) or fully present,
 *       never partially written without detection.
 * </ul>
 *
 * <p>In production, short {@code pwrite} returns occur on NFS mounts when the server's write window
 * limits how many bytes can be committed in a single RPC, when an interrupt is delivered during a
 * large write and the kernel restarts with a partial count, and when a signal is delivered to the
 * writing thread and the kernel returns the partial count before restarting the operation.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code pwrite(2)} may return a count smaller than the requested count (a
 * "short write") for any file descriptor type, without this being an error. The returned count
 * reflects the number of bytes actually written at the requested offset; the remaining bytes were
 * not written and the file's content beyond the returned count is unchanged. Applications must
 * check the return value and loop with an adjusted offset and buffer pointer until all bytes are
 * written, using the pattern: {@code while (remaining > 0) { n = pwrite(fd, buf, remaining, off);
 * buf += n; off += n; remaining -= n; }}.
 *
 * <p>The torn write injection specifically targets the {@code pwrite}-based page I/O path used by
 * storage engines. Unlike {@code write}, which updates the file position on each call (making retry
 * loops straightforward), {@code pwrite} requires the caller to explicitly advance the offset on
 * each retry. Storage engines that copy the offset from the page number calculation and reuse it
 * without adjustment on short-write retries will write the remaining bytes at the wrong offset,
 * overwriting previously-written data.
 *
 * <p>Java's {@code FileChannel.write(ByteBuffer, long)} maps to {@code pwrite(2)} on Linux and
 * returns the actual byte count written. Application code that calls this method and assumes the
 * count always equals {@code buf.remaining()} will silently leave partial data in the file. The
 * {@code WritableByteChannel} contract documents that the count may be less than the buffer limit,
 * but many callers ignore this because local filesystem writes rarely produce short counts under
 * normal conditions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPwriteTorn(probability = 0.001)
 * class PwriteTornTest {
 *   @Test
 *   void pageWriteLoopPersistsAllBytesAtCorrectOffsetUnderTornWrites() {
 *     // assert that the page writer loops until all bytes are written at the correct file offset
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosWriteTorn
 * @see ChaosPreadCorrupt
 * @see com.macstab.chaos.filesystem.annotation.l1.IoTornBinding
 */
@Repeatable(ChaosPwriteTorn.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoTornTranslator")
@IoTornBinding(operation = IoOperation.PWRITE)
public @interface ChaosPwriteTorn {

  /**
   * @return per-write probability of tearing, in {@code (0.0, 1.0]}
   */
  double probability() default 0.001;

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
   * @ChaosPwriteTorn(id = "primary",  probability = 0.001)
   * @ChaosPwriteTorn(id = "replica",  probability = 0.01)
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
    ChaosPwriteTorn[] value();
  }
}
