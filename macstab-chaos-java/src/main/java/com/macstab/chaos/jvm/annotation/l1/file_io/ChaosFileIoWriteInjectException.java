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
 * Intercepts {@code FileOutputStream.write()} and {@code RandomAccessFile.write()} and throws the
 * configured exception before any bytes are written to the file, simulating a full filesystem,
 * a read-only mount, or a storage I/O error that causes log appenders, audit writers, and
 * file-based transactional logs to fail mid-write.
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
 *   <li>Before every call to {@code java.io.FileOutputStream#write(byte[], int, int)} and
 *       {@code java.io.RandomAccessFile#write(byte[], int, int)} inside the target container's
 *       JVM, the chaos agent intercepts the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no bytes are written to the file.
 *   <li>The exception propagates to the caller — log appender, audit writer, WAL writer — which
 *       must handle the write failure or propagate it to the application as a critical error.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Log4j 2 and Logback file appenders catch {@code IOException} from the write, log an
 *       internal error message (using the status listener), and depending on configuration either
 *       drop the failed log event or enter a degraded state; assert that the application does not
 *       crash when logging fails and that the status listener reports the write error.
 *   <li>Inject {@code java.io.IOException: No space left on device} (ENOSPC) to simulate a full
 *       filesystem; Spring Boot's embedded server writes access logs and temporary files to disk;
 *       assert that the application transitions to a degraded health state and that the
 *       {@code /actuator/health} endpoint reflects the disk-full condition.
 *   <li>Applications that use {@code RandomAccessFile} for local state storage (e.g. offset
 *       tracking files, local caches) will throw on write; assert that the application rolls back
 *       to the last persisted state rather than silently losing the update.
 *   <li><strong>Production failure mode:</strong> a Kafka broker's log directory disk fills due
 *       to insufficient retention configuration; every log segment write throws
 *       {@code IOException: No space left on device}; the broker is unable to accept new messages;
 *       it marks itself as unclean and triggers leader election on all partitions; the cluster
 *       becomes unavailable for producers; operators must manually clean the disk and restart the
 *       broker to recover.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.io.FileOutputStream#write(byte[], int, int)} and
 * {@code java.io.RandomAccessFile#write(byte[], int, int)} via Byte Buddy. Both call JNI native
 * methods; the chaos exception fires before the JNI boundary, so the OS file state is unmodified.
 * The file remains at its pre-write size with its pre-write contents; no partial writes occur.
 *
 * <p>Log4j 2's {@code RollingFileManager} calls {@code channel.write(byteBuffer)} for the NIO
 * path or {@code outputStream.write(bytes)}  for the classic path; both eventually reach
 * {@code FileOutputStream.write()}. On write failure, Log4j's error handling depends on the
 * configured {@code AbstractAppender#isIgnoreExceptions()} flag: if {@code true} (the default),
 * the exception is silently swallowed and the event is dropped; if {@code false}, the exception
 * propagates to the caller. Applications relying on the default silent-drop behaviour may not
 * notice that they have lost log events until a post-incident analysis.
 *
 * <p>RocksDB (used by many JVM applications as an embedded store via JNI) writes its WAL and
 * SSTable files via JNI; the Byte Buddy intercept at the Java level does not fire for JNI-based
 * writes. Only Java-level file writes are intercepted. For RocksDB write fault injection, use
 * OS-level fault injection (e.g. dm-flakey) instead.
 *
 * <p>The distinction from {@link ChaosFileIoWriteDelay} is severity: a delay allows the write to
 * eventually succeed after a pause; an exception causes permanent write failure for every call
 * during the fault window. The exception variant exercises the "disk is broken" code path; the
 * delay variant exercises the "disk is slow" code path — distinct failure modes that trigger
 * different application recovery strategies.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosFileIoWriteInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "No space left on device")
 * class DiskFullTest {
 *   @Test
 *   void applicationHealthDegradesDuringDiskFull(ConnectionInfo info) {
 *     // assert health endpoint shows DISK_FULL status
 *     // assert log events are not silently dropped (if appender is configured to fail fast)
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
 * @see ChaosFileIoWriteDelay
 * @see ChaosFileIoReadInjectException
 */
@Repeatable(ChaosFileIoWriteInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.FILE_IO,
    operationType = OperationType.FILE_IO_WRITE)
public @interface ChaosFileIoWriteInjectException {

  /**
   * @return binary class name of the exception to throw (e.g. "java.io.IOException")
   */
  String exceptionClassName() default "java.io.IOException";

  /**
   * @return exception message
   */
  String message() default "injected by chaos L1";

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
   * @ChaosFileIoWriteInjectException(id = "primary",  probability = 0.001)
   * @ChaosFileIoWriteInjectException(id = "replica",  probability = 0.01)
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
    ChaosFileIoWriteInjectException[] value();
  }
}
