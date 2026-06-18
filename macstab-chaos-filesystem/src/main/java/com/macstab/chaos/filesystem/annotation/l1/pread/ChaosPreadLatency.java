/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.pread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.annotation.l1.pwrite.ChaosPwriteLatency;
import com.macstab.chaos.filesystem.annotation.l1.read.ChaosReadLatency;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Delays every {@code pread(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making positional file reads slower than the application
 * expects while still returning the actual file data at the requested offset.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code PREAD}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the data is read normally. No probability gate is applied; the
 * delay fires on every intercepted {@code pread} call. No runtime operation-effect validation is
 * needed.
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
 *   <li>On each intercepted {@code pread} call the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Positional file read operations take longer than normal; applications that use {@code
 *       pread} for random-access page reads (database B-tree traversal, index lookups, heap file
 *       scans) will see increased latency per page. Assert that the application's query timeout
 *       accounts for the accumulated delay across all page reads in the query plan.
 *   <li>Database engines that issue concurrent {@code pread} calls from multiple worker threads
 *       each incur the delay independently; a query that requires reading N pages from N worker
 *       threads accumulates N × {@link #delayMs} of total wait time across the thread pool. Assert
 *       that the connection-level query timeout is calibrated for the worst-case number of page
 *       reads per query.
 *   <li>Applications that prefetch pages by issuing speculative {@code pread} calls ahead of the
 *       access pattern will find that prefetching no longer hides the I/O latency because the
 *       injected delay is added before the kernel call. Assert that the application degrades
 *       gracefully when prefetching is ineffective rather than timing out on the prefetch thread.
 *   <li>Assert that slow {@code pread} calls on index pages do not cause a query to return partial
 *       results by timing out mid-traversal — the application must either complete the traversal or
 *       return an error, not a partial result set.
 * </ul>
 *
 * <p>In production, slow {@code pread} calls occur when the page cache is cold after a process
 * restart and each read triggers a synchronous disk access, when cgroup I/O bandwidth throttling
 * ({@code blkio.throttle.read_bps_device}) limits the process's read throughput and causes reads to
 * queue behind the throttle, and when network filesystems (NFS, CIFS) are under load and the
 * server's response time exceeds the client's expected latency.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code pread(2)} reads from a caller-specified file offset without modifying the file's
 * current position, making it the preferred I/O primitive for multi-threaded random-access
 * workloads. Database storage engines use it exclusively for page I/O because it allows multiple
 * threads to read from different offsets concurrently without locking the file position. The
 * kernel's page cache services cache hits in microseconds; cache misses require a synchronous block
 * device read and take milliseconds to tens of milliseconds depending on the storage type.
 *
 * <p>This injection adds the delay before the kernel call, simulating scheduling stalls and I/O
 * queue latency without requiring actual slow storage. The delay fires on every {@code pread} call
 * regardless of whether the page is in the cache; on a warm cache this makes the injection more
 * severe than real slow storage (where cache hits are fast), which is intentional — it tests the
 * application's timeout handling rather than its cache efficiency.
 *
 * <p>Java's {@code FileChannel.read(ByteBuffer, long)} maps to {@code pread(2)} on Linux. When
 * multiple threads call {@code channel.read(buf, offset)} concurrently, each call independently
 * incurs the injected delay. Thread pool sizing assumptions that were tuned for fast storage may
 * result in thread starvation when every thread is blocked on a delayed {@code pread}; this
 * injection exposes those assumptions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosPreadLatency(delayMs = 50)
 * class PreadLatencyTest {
 *   @Test
 *   void queryCompletesWithinDeadlineUnderSlowPageReads() {
 *     // assert that query execution finishes within its deadline even when page reads are slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadLatency
 * @see ChaosPwriteLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosPreadLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.PREAD)
public @interface ChaosPreadLatency {

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
   * @ChaosPreadLatency(id = "primary",  probability = 0.001)
   * @ChaosPreadLatency(id = "replica",  probability = 0.01)
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
    ChaosPreadLatency[] value();
  }
}
