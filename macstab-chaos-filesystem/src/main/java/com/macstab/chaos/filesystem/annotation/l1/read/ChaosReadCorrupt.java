/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.read;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoCorruptBinding;
import com.macstab.chaos.filesystem.annotation.l1.pread.ChaosPreadCorrupt;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Corrupts bytes in the buffer returned by each intercepted {@code read(2)} call, flipping random
 * bits in the data read from the file at a per-byte probability of {@link #probability}, simulating
 * bit errors introduced by failing storage hardware, filesystem bugs, or memory corruption on the
 * read path.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code READ}, effect = CORRUPT) tuple.
 * Unlike errno variants, the corruption primitive delegates to the real kernel call and receives
 * authentic data, then flips random bits in the returned buffer before returning to the caller.
 * {@link #probability} controls the per-byte bit-flip rate. No runtime operation-effect validation
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
 *   <li>After each real {@code read} call completes, a per-byte Bernoulli trial with probability
 *       {@link #probability} is conducted for each byte in the returned buffer; when it fires for a
 *       given byte, a random bit in that byte is flipped before the buffer is returned to the
 *       caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Corrupted bytes appear valid from the read syscall's perspective — the call returns the
 *       correct byte count with no error indication. Assert that the application uses a checksum or
 *       hash to detect data corruption rather than trusting the read return value alone.
 *   <li>Serialization formats with length-prefixed fields (protobuf, Avro, Thrift) will produce
 *       parse errors when length fields are corrupted; assert that the application handles
 *       deserialization exceptions without crashing or leaking partial state.
 *   <li>Binary file formats with magic-number headers will appear invalid when the header bytes are
 *       corrupted; assert that the application validates the magic number and rejects corrupt files
 *       with a clear error rather than proceeding with garbled data.
 *   <li>Assert that the application's checksum validation (CRC32C, SHA-256 of WAL entries, etc.)
 *       detects the corruption and either retries from a replica or reports a data integrity error
 *       rather than silently processing corrupt data.
 * </ul>
 *
 * <p>In production, data corruption on {@code read} occurs due to failing DRAM (bit flips in the
 * page cache), storage controller firmware bugs that produce corrupt data without an error signal,
 * and filesystem bugs in the page cache consistency path. Unlike hardware EIO errors, silent data
 * corruption (SDC) is particularly dangerous because the application receives incorrect data
 * without any indication that something went wrong.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The corruption is applied after the real {@code read} syscall completes, to the data in the
 * user-space buffer. This simulates corruption in the page cache copy path (from kernel page to
 * user buffer), which can occur due to DRAM bit flips or DMA errors. The actual on-disk data is not
 * modified; a subsequent read of the same file offset may return different (uncorrupted) data if
 * the page cache entry is evicted and re-read from disk.
 *
 * <p>The per-byte probability model means that for a {@link #probability} of {@code p}, the
 * expected number of corrupted bytes in a buffer of size N is N × p. For a 4 KB page with {@code p
 * = 0.001}, approximately 4 bytes are expected to be flipped per read. For structured binary
 * formats with checksums or integrity codes, even a single bit flip in the right location (a length
 * field, a record type byte, or a checksum field itself) can cause catastrophic parse failures.
 *
 * <p>Java's NIO {@code FileChannel.read(ByteBuffer)} and classic {@code
 * FileInputStream.read(byte[])} both use the same underlying {@code read(2)} syscall path and will
 * receive the corrupted buffer. Memory-mapped files ({@code FileChannel.map()}) bypass the {@code
 * read(2)} path and are not affected by this injection — the corruption is injected at the syscall
 * interposition layer, not at the page cache level.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosReadCorrupt(probability = 0.001)
 * class ReadCorruptTest {
 *   @Test
 *   void checksumValidationDetectsCorruptedFileData() {
 *     // assert that CRC32C validation catches the flipped bits and reports a data integrity error
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadEio
 * @see ChaosPreadCorrupt
 * @see com.macstab.chaos.filesystem.annotation.l1.IoCorruptBinding
 */
@Repeatable(ChaosReadCorrupt.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoCorruptTranslator")
@IoCorruptBinding(operation = IoOperation.READ)
public @interface ChaosReadCorrupt {

  /**
   * @return per-read probability of corruption, in {@code (0.0, 1.0]}
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
   * @ChaosReadCorrupt(id = "primary",  probability = 0.001)
   * @ChaosReadCorrupt(id = "replica",  probability = 0.01)
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
    ChaosReadCorrupt[] value();
  }
}
