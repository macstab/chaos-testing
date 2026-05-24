/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.write;

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
 * Simulates a torn {@code write(2)} by intercepting the call, performing only a partial write of
 * the caller's buffer, and returning the partial byte count — causing the caller to observe a short
 * write without any error, exactly as POSIX permits for writes larger than {@code PIPE_BUF}.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code WRITE}, effect = TORN) tuple.
 * Unlike errno variants, this primitive does not inject a failure — it performs a real but partial
 * write. A Bernoulli trial with probability {@link #probability} is run on each intercepted {@code
 * write} call; when it fires the interposer passes a randomly-chosen prefix of the buffer to the
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
 *   <li>On each intercepted {@code write} call a Bernoulli trial with probability {@link
 *       #probability} is conducted; when it fires the interposer selects a random split point
 *       within the buffer, issues the real {@code write} syscall with only the prefix bytes, and
 *       returns the partial count to the caller. The remaining bytes are silently dropped for this
 *       call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>A torn write returns a positive byte count smaller than the requested count, which is valid
 *       POSIX behaviour for {@code write(2)} on any file descriptor. Applications that do not loop
 *       on short writes will silently lose the trailing bytes. Assert that every write loop
 *       correctly advances the buffer pointer and repeats until all bytes are written.
 *   <li>Structured binary formats (database pages, WAL records, protocol framing) that are written
 *       in a single {@code write} call may arrive partially: a page header is written but the page
 *       body is missing, or a WAL record is split at a non-boundary. Assert that the reader detects
 *       partial records via checksums, length-prefix validation, or magic-byte sentinels.
 *   <li>Applications that use {@code FileChannel.write(ByteBuffer)} and check the return value
 *       against the expected count must loop on short writes; assert that the channel-level writer
 *       retries until all bytes are written rather than assuming a single call is sufficient.
 *   <li>Assert that torn writes during a checkpoint or compaction do not leave the data structure
 *       in a state that cannot be recovered — the torn record must either be fully absent (rolled
 *       back) or fully present (committed) after crash recovery.
 * </ul>
 *
 * <p>In production, short writes occur when a pipe or socket write exceeds the kernel buffer size
 * and the kernel can only accept part of the data, when a signal interrupts a large write and the
 * kernel restarts with a partial count, and on network filesystems where the server's write window
 * limits how many bytes the client can send in a single RPC.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code write(2)} may return a count smaller than the requested count (a
 * "short write") for any file descriptor type, without this being an error. The only guarantee for
 * regular files is that writes smaller than or equal to {@code PIPE_BUF} bytes (4096 on Linux) to a
 * pipe are atomic; for regular files there is no atomicity guarantee at any size. Applications that
 * assume a single {@code write} call atomically persists an entire structured record — a database
 * page, a WAL entry, a log line — are relying on implementation-specific behaviour that is not
 * guaranteed by POSIX and does not hold under kernel I/O throttling or signal delivery.
 *
 * <p>The torn write injection tests the write-loop invariant: after every {@code write} call the
 * application must check whether the returned count equals the requested count and, if not, advance
 * the buffer pointer by the returned count and retry with the remaining bytes. Failure to do so
 * results in silent data loss — the application believes the full buffer was written, but only a
 * prefix reached the kernel. This class of bug is often invisible in testing because local
 * filesystem writes to regular files almost never produce short counts under normal conditions.
 *
 * <p>Java's {@code FileOutputStream.write(byte[], int, int)} calls the underlying write syscall in
 * a loop inside the JVM and will not return until all bytes are written, masking short writes.
 * However, {@code FileChannel.write(ByteBuffer)} delegates directly to a single write syscall and
 * returns the actual byte count written; callers must check the return value and retry. The {@code
 * WritableByteChannel} contract documents this behaviour, but many callers assume the count always
 * equals the buffer limit. This injection exposes those incorrect assumptions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosWriteTorn(probability = 0.001)
 * class WriteTornTest {
 *   @Test
 *   void walWriteLoopPersistsAllBytesUnderTornWrites() {
 *     // assert that the WAL writer loops until all bytes are written and records are complete
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosPwriteTorn
 * @see ChaosReadCorrupt
 * @see com.macstab.chaos.filesystem.annotation.l1.IoTornBinding
 */
@Repeatable(ChaosWriteTorn.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoTornTranslator")
@IoTornBinding(operation = IoOperation.WRITE)
public @interface ChaosWriteTorn {

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
   * @ChaosWriteTorn(id = "primary",  probability = 0.001)
   * @ChaosWriteTorn(id = "replica",  probability = 0.01)
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
    ChaosWriteTorn[] value();
  }
}
