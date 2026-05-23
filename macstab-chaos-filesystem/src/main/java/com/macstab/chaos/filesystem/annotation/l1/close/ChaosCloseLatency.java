/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.close;

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
 * Delays every {@code close(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making file descriptor cleanup slower than the application
 * expects while still successfully closing the descriptor and flushing any pending buffered data.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code CLOSE}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the file descriptor is closed normally. No probability gate
 * is applied; the delay fires on every intercepted {@code close} call. No runtime operation-effect
 * validation is needed.
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
 *   <li>On each intercepted {@code close} call the interposer sleeps for {@link #delayMs} ms
 *       before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>File descriptor cleanup operations take longer than normal; applications that close many
 *       file descriptors in a tight loop (closing a connection pool during shutdown, closing a
 *       batch of temporary files) will see increased shutdown latency. Assert that the application's
 *       shutdown timeout accounts for the accumulated delay across all close calls.
 *   <li>Applications that use try-with-resources or finally blocks to close files will hold the
 *       thread for the duration of the delayed close, potentially blocking other threads waiting
 *       for the same lock. Assert that close calls in critical sections are not delayed beyond the
 *       lock timeout.
 *   <li>Connection pool implementations that close idle connections in a background thread will
 *       accumulate the delay across each closed connection; assert that the background thread's
 *       cleanup budget is calibrated for slow close calls rather than assuming each close
 *       completes in microseconds.
 *   <li>Assert that the application does not treat a delayed {@code close} as a hang and forcibly
 *       terminate the process before the shutdown sequence completes; the application must
 *       distinguish a slow close from a stuck close.
 * </ul>
 *
 * <p>In production, slow {@code close} calls occur when the kernel must flush dirty pages to the
 * storage device during close (when the application did not call {@code fsync} before closing),
 * when the storage device is under heavy write pressure and the flush must wait for the device's
 * write queue to drain, and on NFS mounts when the server must acknowledge the close before the
 * client-side {@code close} returns.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code close(2)} syscall releases the file description associated with the file descriptor
 * and decrements the reference count. For the last reference, the kernel may flush dirty pages to
 * storage, free inode resources, and release any advisory locks held by the process. On a local
 * filesystem with buffered writes and no prior {@code fsync}, the close path can trigger writeback
 * of all dirty pages associated with the file, making close significantly slower than expected
 * for large files with many dirty pages.
 *
 * <p>This injection adds the delay before the kernel call, simulating the scheduling stall and
 * writeback latency without requiring actual dirty pages or slow storage. The delay fires on every
 * close call regardless of whether the file has dirty pages; on a freshly-synced file this makes
 * the injection more severe than real slow storage.
 *
 * <p>Java's {@code FileInputStream.close()} and {@code FileOutputStream.close()} both delegate
 * to the JVM's {@code FileDescriptor.closeAll()}, which calls the native {@code close} syscall.
 * In a try-with-resources block, a slow {@code close} delays the thread until the resource is
 * fully released. If the {@code close} is on the critical path (inside a synchronized block or
 * while holding a connection pool lock), the delay can cause cascading timeouts in other threads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosCloseLatency(delayMs = 50)
 * class CloseLatencyTest {
 *   @Test
 *   void connectionPoolShutdownCompletesWithinDeadlineUnderSlowClose() {
 *     // assert that pool shutdown finishes within its timeout even when each close is slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosOpenLatency
 * @see ChaosFsyncLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosCloseLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.CLOSE)
public @interface ChaosCloseLatency {

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
   * @ChaosCloseLatency(id = "primary",  probability = 0.001)
   * @ChaosCloseLatency(id = "replica",  probability = 0.01)
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
    ChaosCloseLatency[] value();
  }
}
