/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.file_io;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Intercepts {@code FileInputStream.read()} and {@code RandomAccessFile.read()} and holds the
 * calling thread for {@link #delayMs()} milliseconds before bytes are read from the underlying
 * file, simulating a slow storage device, a throttled network-attached file system, or an NFS
 * mount experiencing high latency.
 *
 * <h2>What this annotation is</h2>
 *
 * A JVM agent L1 chaos primitive — one typed annotation per (selector family, operation type,
 * effect) tuple. It is declared on a test class or method alongside a container annotation and
 * activates for the lifetime of the test class ({@code beforeAll} / {@code afterAll}) or a single
 * test method ({@code beforeEach} / {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>Before every call to {@code java.io.FileInputStream#read(byte[], int, int)} and
 *       {@code java.io.RandomAccessFile#read(byte[], int, int)} inside the target container's JVM,
 *       the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code read()} executes normally, issuing a
 *       {@code read(2)} or {@code pread(2)} syscall and copying bytes from the kernel page cache
 *       or storage into the caller's buffer.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Configuration file loading at startup (e.g. Spring's {@code ResourceLoader} reading
 *       YAML/properties files) is delayed; assert that the application's startup time increases
 *       by at least {@code numConfigFiles × numChunks × delayMs} and that readiness probes are
 *       configured with sufficient startup grace.
 *   <li>Log4j and Logback's appenders that read configuration via {@code FileInputStream} during
 *       reconfiguration will be delayed; assert that the logging framework remains functional
 *       during reconfiguration and does not drop log events.
 *   <li>Applications that read certificate files, keystores, or trust stores via
 *       {@code FileInputStream} at connection-establishment time (e.g. gRPC channels) will be
 *       delayed during TLS handshake setup; assert that TLS handshake timeouts are configured
 *       to account for slow file I/O.
 *   <li><strong>Production failure mode:</strong> a Kubernetes persistent volume backed by NFS
 *       experiences high latency due to NFS server overload; the application's configuration
 *       hot-reload reads files from this volume; each read incurs high NFS latency; the reload
 *       thread holds a lock during the read; other threads that need the configuration block on
 *       the lock; under high concurrency this causes a thread pile-up that depletes the application
 *       server's thread pool.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.io.FileInputStream#read(byte[], int, int)} and
 * {@code java.io.RandomAccessFile#read(byte[], int, int)} via Byte Buddy. Both ultimately call
 * the native method {@code FileInputStream.readBytes()} (via JNI), which issues {@code read(2)}
 * on the file descriptor. On Linux, if the file's data is in the page cache, the syscall returns
 * immediately (no I/O); if not, it blocks until the storage layer returns the data. The chaos
 * delay fires before the JNI call, adding a JVM-level delay independent of whether the data
 * is cached.
 *
 * <p>Spring's {@code ClassPathResource} and {@code FileSystemResource} both use
 * {@code FileInputStream} to read resources. Calls to {@code Resource.getInputStream()} return
 * a stream that will incur the read delay. Spring Boot's externalized configuration loading
 * reads properties files in {@code ConfigDataEnvironmentPostProcessor}; the delay fires during
 * the initial bootstrap phase, before any beans are created.
 *
 * <p>Hibernate's schema validation and DDL generation reads SQL script files via
 * {@code FileInputStream}; the delay fires during the application startup's schema phase.
 * If Hibernate's DDL mode is {@code validate} or {@code update}, the startup is blocked until
 * all files are read, making this a realistic model for a slow NFS-mounted SQL init script.
 *
 * <p>The delay fires on every read call, not just the first; a large file read in chunks of
 * 8192 bytes (the JVM default buffer size) incurs the delay on every chunk. A 1 MB file with
 * 100 ms delay per read results in approximately 12 seconds of additional read time
 * ({@code 1 MB / 8 KB ≈ 128 chunks × 100 ms}).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosFileIoReadDelay(delayMs = 500)
 * class SlowConfigLoadTest {
 *   @Test
 *   void startupReadinessProbeAccountsForSlowFileRead(ConnectionInfo info) {
 *     // assert application startup takes longer but eventually becomes ready
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the
 *       translator class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFileIoReadInjectException
 * @see ChaosFileIoWriteDelay
 */
@Repeatable(ChaosFileIoReadDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.FILE_IO,
    operationType = OperationType.FILE_IO_READ)
public @interface ChaosFileIoReadDelay {

  /**
   * @return min delay in milliseconds
   */
  long delayMs() default 100L;

  /**
   * @return max delay in milliseconds (defaults to delayMs for deterministic delay)
   */
  long maxDelayMs() default 100L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosFileIoReadDelay(id = "primary",  probability = 0.001)
   * @ChaosFileIoReadDelay(id = "replica",  probability = 0.01)
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
    ChaosFileIoReadDelay[] value();
  }
}
