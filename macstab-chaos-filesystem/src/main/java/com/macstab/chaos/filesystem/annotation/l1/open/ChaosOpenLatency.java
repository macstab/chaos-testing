/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.open;

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
 * Delays every {@code open(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making file open slower than the application expects while
 * still returning a valid file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code OPEN}, effect = LATENCY) tuple.
 * Unlike errno variants, the latency primitive always delegates to the real kernel call after the
 * configured extra delay — the file is opened normally. No probability gate is applied; the delay
 * fires on every intercepted {@code open} call. No runtime operation-effect validation is needed.
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
 *   <li>On each intercepted {@code open} call the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>File open operations take longer than normal; applications that open files on critical
 *       paths (configuration reload, credential refresh, log file rotation) will see increased
 *       latency on those paths. Assert that the application's timeout configuration accounts for
 *       slow file opens.
 *   <li>Applications that open and close files on every request (file-based session stores, per-request
 *       logging to separate files) accumulate the injected delay on each request; assert that request
 *       latency SLOs tolerate this additional overhead or that the application caches open file
 *       descriptors.
 *   <li>Startup sequences that open many files (configuration, certificates, shared libraries)
 *       accumulate the delay across all opens; assert that the startup timeout is calibrated for
 *       the worst-case number of open calls at startup.
 *   <li>Assert that the application does not interpret slow file open as a failure — the open
 *       succeeds, just slowly, and any retry-on-timeout logic must distinguish a timeout from
 *       an actual open failure.
 * </ul>
 *
 * <p>In production, slow {@code open} calls occur when the filesystem's directory entry cache
 * (dentry cache) is cold after a memory reclaim event, when network filesystems (NFS, Ceph) are
 * under load and the metadata server is slow to respond to lookup and open requests, and when
 * process scheduling stalls under cgroup CPU throttling cause the thread to wait before entering
 * the kernel.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code open(2)} syscall resolves the pathname using the kernel's VFS layer, which walks
 * the dentry cache to find each path component's inode. Cache hits are fast (nanoseconds); cache
 * misses require reading directory blocks from disk or from the network filesystem backend.
 * For NFS mounts, each pathname component requires a separate RPC to the NFS server's metadata
 * path; a path with N components requires N round trips. This injection simulates the aggregate
 * cost of these operations without requiring an actual slow storage backend.
 *
 * <p>Applications that perform health checks by opening and immediately closing a file (a
 * "canary file" pattern) will see increased health check latency under this injection; assert
 * that the health check timeout is calibrated to accommodate slow filesystem opens in stressed
 * environments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosOpenLatency(delayMs = 200)
 * class OpenLatencyTest {
 *   @Test
 *   void configurationReloadToleratesSlowFileOpen() {
 *     // assert that config reload completes within its deadline even when open is slow
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosReadLatency
 * @see ChaosWriteLatency
 * @see com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding
 */
@Repeatable(ChaosOpenLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.OPEN)
public @interface ChaosOpenLatency {

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
   * @ChaosOpenLatency(id = "primary",  probability = 0.001)
   * @ChaosOpenLatency(id = "replica",  probability = 0.01)
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
    ChaosOpenLatency[] value();
  }
}
