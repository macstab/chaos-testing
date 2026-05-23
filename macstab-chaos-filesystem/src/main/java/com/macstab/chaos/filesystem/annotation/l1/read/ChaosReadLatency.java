/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.read;

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
 * Delays every {@code read(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making file reads slower than the application expects while
 * still returning the actual file data.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code READ}, effect = LATENCY) tuple.
 * Unlike errno variants, the latency primitive always delegates to the real kernel call after the
 * configured extra delay — the data is read normally. No probability gate is applied; the delay
 * fires on every intercepted {@code read} call. No runtime operation-effect validation is needed.
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
 *   <li>On each intercepted {@code read} call the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>File read operations take longer than normal; applications that read files on request
 *       critical paths (config parsing, credential loading, data file access) will see increased
 *       latency. Assert that the application's read timeout or operation deadline accounts for
 *       the injected delay.
 *   <li>Applications that perform many small reads per request (reading a binary format header,
 *       then record by record) accumulate the delay across all read calls; assert that the total
 *       request timeout is calibrated for the worst-case number of reads per request.
 *   <li>Streaming file parsers (XML, JSON stream parsers, CSV readers) that issue one read per
 *       token or line will be severely impacted by per-read latency; assert that the application
 *       uses a buffered reader to minimize the number of underlying read calls.
 *   <li>Assert that the application does not treat slow reads as read errors — a delayed read
 *       still returns data, and any timeout logic must not conflate a delayed read with an
 *       {@code EIO} or EOF condition.
 * </ul>
 *
 * <p>In production, slow {@code read} calls occur when the page cache is cold after a process
 * restart and each read results in a disk access (page fault into kernel page cache), when
 * network filesystems (NFS, CIFS) are under load and I/O requests are queued behind other
 * outstanding requests, and when cgroup I/O bandwidth throttling ({@code blkio.throttle}) limits
 * the process's storage throughput.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code read(2)} syscall copies data from the kernel's page cache to the user-space
 * buffer. If the data is in the page cache (a cache hit), the copy is fast (limited by memory
 * bandwidth). If the data is not in the page cache (a cache miss), the kernel must read it from
 * the storage device, which adds the device's latency to the read. This injection simulates the
 * miss-path latency without requiring actual disk I/O.
 *
 * <p>Applications that read large files sequentially benefit from the kernel's read-ahead
 * mechanism, which prefetches upcoming pages into the cache. Read-ahead reduces per-read latency
 * by overlapping I/O with processing time. This injection adds delay before the read call, which
 * occurs after the read-ahead has already prefetched the data; the injection therefore measures
 * the process scheduling cost rather than storage latency.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosReadLatency(delayMs = 50)
 * class ReadLatencyTest {
 *   @Test
 *   void configurationFileParsingCompletesWithinDeadlineUnderSlowStorage() {
 *     // assert that config parsing finishes within its deadline even when reads are slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadEio
 * @see ChaosWriteLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosReadLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.READ)
public @interface ChaosReadLatency {

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
   * @ChaosReadLatency(id = "primary",  probability = 0.001)
   * @ChaosReadLatency(id = "replica",  probability = 0.01)
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
    ChaosReadLatency[] value();
  }
}
