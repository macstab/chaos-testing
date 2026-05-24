/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoCorruptBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Simulates silent data corruption on {@code pread(2)} by performing the real kernel call and then
 * randomly flipping bits in the returned buffer before returning control to the caller, as if the
 * storage device returned corrupted data for the requested file offset range.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PREAD}, effect = CORRUPT)
 * tuple. Unlike errno variants, this primitive does not fail the call — it allows the real kernel
 * operation to complete and then mutates the returned data. A Bernoulli trial with probability
 * {@link #probability} is run on each byte of the returned buffer; each byte that passes the trial
 * has one of its bits flipped. No runtime operation-effect validation is needed.
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
 *   <li>On each intercepted {@code pread} call the interposer issues the real syscall and then
 *       walks the returned buffer, applying a per-byte Bernoulli trial with probability {@link
 *       #probability}; each selected byte has one randomly-chosen bit flipped.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The {@code pread} call returns a successful byte count but the buffer contains corrupted
 *       data; the application sees no error indication and must detect the corruption through
 *       checksums, length-prefix validation, or magic-byte sentinels. Assert that the application's
 *       checksum verification catches the corruption and raises an error rather than processing the
 *       corrupted data.
 *   <li>{@code pread(2)} is commonly used by database engines for random-access page reads (each
 *       page is read at its known file offset without adjusting the file position). A corrupted
 *       page header length field causes the parser to read garbage; assert that the page checksum
 *       is verified before the page contents are trusted.
 *   <li>Applications that use memory-mapped files ({@code mmap}) are NOT affected by this injection
 *       — memory-mapped reads bypass the {@code pread} interposer. Use this annotation only to test
 *       code paths that use explicit {@code pread}-based I/O.
 *   <li>Assert that silent corruption on a index page is detected by the index consistency check at
 *       startup and triggers a rebuild rather than returning incorrect query results.
 * </ul>
 *
 * <p>In production, silent data corruption occurs when a storage device returns incorrect data
 * without signalling an error (a "silent data corruption" event), when DRAM bit flips affect the
 * kernel's page cache copy of the data, and when a DMA engine writes data to the wrong memory
 * location and the original buffer position is returned to the application.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code pread(2)} reads {@code count} bytes from file descriptor {@code fd} at offset {@code
 * offset} without modifying the file's current position. It is equivalent to calling {@code lseek}
 * to the offset, calling {@code read}, and then restoring the position — but as a single atomic
 * operation. This makes it the preferred I/O primitive for multi-threaded applications that read
 * from different regions of a file concurrently, because each call is self-contained and does not
 * race with other threads' file position updates.
 *
 * <p>Database storage engines use {@code pread} for B-tree page reads: each page is identified by
 * its page number, which maps to a file offset via {@code page_number * page_size}. The engine
 * issues {@code pread(fd, buf, page_size, page_number * page_size)} and then validates the returned
 * page's checksum before using its contents. This injection simulates the case where the storage
 * device returns data that passes the block-layer integrity check (no SCSI MEDIUM ERROR) but
 * contains corrupted bytes — the kind of corruption that checksums are designed to detect.
 *
 * <p>Java's {@code FileChannel.read(ByteBuffer, long)} maps directly to {@code pread(2)} on Linux.
 * The channel does not perform any checksum validation on the returned data; the application is
 * responsible for verifying data integrity. Higher-level frameworks like RocksDB and SQLite compute
 * a CRC32 or xxHash over each page before writing and verify it after reading; this injection
 * triggers those verification paths without requiring a real storage hardware fault.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPreadCorrupt(probability = 0.001)
 * class PreadCorruptTest {
 *   @Test
 *   void corruptedPageChecksumDetectedBeforeQueryExecution() {
 *     // assert that page checksum failure is detected and raises an error rather than returning wrong results
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadCorrupt
 * @see ChaosPreadEio
 * @see com.macstab.chaos.filesystem.annotation.l1.IoCorruptBinding
 */
@Repeatable(ChaosPreadCorrupt.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoCorruptTranslator")
@IoCorruptBinding(operation = IoOperation.PREAD)
public @interface ChaosPreadCorrupt {

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
   * @ChaosPreadCorrupt(id = "primary",  probability = 0.001)
   * @ChaosPreadCorrupt(id = "replica",  probability = 0.01)
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
    ChaosPreadCorrupt[] value();
  }
}
