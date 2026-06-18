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
 * Intercepts {@code FileOutputStream.write()} and {@code RandomAccessFile.write()} and holds the
 * calling thread for {@link #delayMs()} milliseconds before bytes are written to the file or
 * flushed to the OS page cache, simulating a slow disk, a throttled NFS write path, or a full
 * filesystem that causes log appenders, audit writers, and file-based state stores to stall.
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
 *   <li>Before every call to {@code java.io.FileOutputStream#write(byte[], int, int)} and {@code
 *       java.io.RandomAccessFile#write(byte[], int, int)} inside the target container's JVM, the
 *       chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code write()} executes normally, issuing a {@code
 *       write(2)} syscall and copying bytes from the caller's buffer to the kernel page cache (or
 *       directly to device if {@code O_SYNC} is set on the file descriptor).
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Log4j and Logback synchronous file appenders write log events via {@code FileOutputStream};
 *       the write delay inflates the time each logging call takes; in high-throughput applications,
 *       logging becomes a bottleneck; assert that the application's throughput drops proportionally
 *       and that the logging framework's internal queue fills if it has one (e.g. Log4j's {@code
 *       AsyncAppender}).
 *   <li>Audit log writers that write to files before returning a response will have their response
 *       latency increased by the write delay; assert that the application's SLO alerting fires and
 *       that the audit log remains consistent (no partial records).
 *   <li>File-based state stores (e.g. RocksDB's WAL, Kafka's log segment files) write data via
 *       {@code FileOutputStream} or {@code RandomAccessFile}; the write delay inflates producer
 *       latency for Kafka brokers; assert that Kafka's {@code request.timeout.ms} is set higher
 *       than the expected write delay to avoid spurious producer timeouts.
 *   <li><strong>Production failure mode:</strong> a Kubernetes pod running a Kafka broker mounts a
 *       persistent volume on a cloud storage backend that degrades due to I/O contention from other
 *       tenants; every log segment write takes hundreds of milliseconds instead of microseconds;
 *       Kafka's ISR (in-sync replica) lag grows; leader election may be triggered; producers
 *       experience increased latency; the root cause (slow PVC) is obscured by the high-level
 *       symptoms of Kafka performance degradation.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.io.FileOutputStream#write(byte[], int, int)} and {@code
 * java.io.RandomAccessFile#write(byte[], int, int)} via Byte Buddy. Both call JNI native methods
 * ({@code FileOutputStream.writeBytes} and {@code RandomAccessFile.writeBytes}); the chaos delay
 * fires before the JNI call. The delay represents time before any bytes enter the kernel page
 * cache; the actual persistence to disk depends on whether the application calls {@code
 * FileDescriptor.sync()} ({@code fsync(2)}) after the write.
 *
 * <p>Log4j 2's {@code FileAppender} uses a {@code FileOutputStream} wrapped in a {@code
 * BufferedOutputStream} (buffer size 8192 bytes by default). Log events are serialised into the
 * buffer; when the buffer fills, it calls {@code FileOutputStream.write(buf, 0, len)}. The chaos
 * delay fires on each buffer flush. For applications logging at 10,000 events/second with an
 * average event size of 200 bytes, the buffer flushes approximately 240 times per second; at 100 ms
 * delay per flush, the flush thread can only complete 10 flushes per second, causing the appender's
 * internal queue to fill and ultimately dropping log events.
 *
 * <p>Logback's {@code RollingFileAppender} uses {@code FileOutputStream} for the active log file
 * and may use {@code RandomAccessFile} for certain rolling strategies; the delay applies to both.
 * During log rotation, Logback opens a new {@code FileOutputStream} and starts writing; the chaos
 * delay applies to all writes on both the old and new file during the rotation window.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosFileIoWriteDelay(delayMs = 200)
 * class SlowDiskLoggingTest {
 *   @Test
 *   void asyncAppenderQueuesEventsAndDoesNotBlockApplication(ConnectionInfo info) {
 *     // assert application throughput is maintained and only log latency increases
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation is required; omitting
 *       it causes an {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>The chaos agent JAR</strong> must be on the path configured in
 *       {@code @JvmAgentChaos}; it is attached before the container starts.
 *   <li><strong>{@code macstab-chaos-java}</strong> must be on the test classpath so the translator
 *       class can be resolved.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosFileIoWriteInjectException
 * @see ChaosFileIoReadDelay
 */
@Repeatable(ChaosFileIoWriteDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.FILE_IO,
    operationType = OperationType.FILE_IO_WRITE)
public @interface ChaosFileIoWriteDelay {

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
   * @ChaosFileIoWriteDelay(id = "primary",  probability = 0.001)
   * @ChaosFileIoWriteDelay(id = "replica",  probability = 0.01)
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
    ChaosFileIoWriteDelay[] value();
  }
}
