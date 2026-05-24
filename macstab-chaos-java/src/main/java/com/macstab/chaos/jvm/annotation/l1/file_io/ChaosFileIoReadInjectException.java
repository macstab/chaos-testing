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
 * Intercepts {@code FileInputStream.read()} and {@code RandomAccessFile.read()} and throws the
 * configured exception before any bytes are read from the file, simulating storage hardware errors,
 * corrupt NFS mounts, or missing files that cause configuration loaders, keystore readers, and log
 * appenders to fail at read time.
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
 *   <li>Before every call to {@code java.io.FileInputStream#read(byte[], int, int)} and {@code
 *       java.io.RandomAccessFile#read(byte[], int, int)} inside the target container's JVM, the
 *       chaos agent intercepts the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no bytes are read from the file.
 *   <li>The exception propagates to the caller — configuration loader, properties reader, keystore
 *       parser — which must handle the read failure gracefully or fail with a fatal startup error.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Spring Boot's configuration loading path reads YAML/properties files via {@code
 *       FileInputStream}; an exception here causes {@code ConfigDataException} to propagate from
 *       {@code SpringApplication.run()} and the application fails to start; assert that the
 *       application's health probe returns a failure state and that the container is restarted by
 *       Kubernetes (rather than staying alive with no config).
 *   <li>Java keystores ({@code KeyStore.load(InputStream, char[])}) read via {@code
 *       FileInputStream}; an exception here causes {@code KeyStoreException: failed to load
 *       keystore data}; TLS handshake initialisation fails; assert that the application reports the
 *       keystore load failure clearly and does not silently use an empty trust store.
 *   <li>Inject {@code java.io.EOFException} to simulate a truncated file (e.g. the file was
 *       partially written); assert that the application does not accept partial configuration and
 *       fails fast with a clear error message rather than starting with inconsistent state.
 *   <li><strong>Production failure mode:</strong> a cloud storage provider (AWS EFS, GCP Filestore)
 *       returns I/O errors during a maintenance window; application pods that read secrets or
 *       certificates from the mounted volume during startup or hot-reload fail with {@code
 *       IOException: Input/output error}; pods cannot start; Kubernetes marks them as
 *       CrashLoopBackOff; the root cause (storage maintenance) is not visible in the pod logs
 *       without correlating with storage provider events.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.io.FileInputStream#read(byte[], int, int)} and {@code
 * java.io.RandomAccessFile#read(byte[], int, int)} via Byte Buddy. Both methods ultimately call JNI
 * native methods ({@code FileInputStream.readBytes} and {@code RandomAccessFile.readBytes}
 * respectively); the chaos intercept fires before the JNI call, so no syscall is issued and no page
 * cache I/O occurs.
 *
 * <p>Spring's {@code PropertiesLoaderUtils.loadProperties(Resource)} reads properties files via
 * {@code resource.getInputStream()}, which for file-backed resources returns a {@code
 * FileInputStream}. An {@code IOException} thrown during the read propagates as {@code IOException}
 * from {@code loadProperties()}, which Spring wraps in a {@code BeanDefinitionStoreException}
 * during context refresh. The application context initialisation aborts and the JVM exits (for
 * Spring Boot with an embedded server) or the WAR deployment fails (for standalone app servers).
 *
 * <p>Java's {@code KeyStore.load(InputStream, char[])} reads the keystore format (JKS, PKCS12) from
 * the stream; an {@code IOException} during the read causes {@code KeyStoreException}. If the
 * keystore is loaded at TLS context initialisation (inside {@code SSLContext.init()}) via {@code
 * KeyManagerFactory.init()}, the exception propagates as {@code KeyManagementException} and the
 * entire TLS context fails to initialise.
 *
 * <p>The file read exception fires on every {@code read()} call, not just the first; if the caller
 * handles partial reads and retries (e.g. by calling {@code read()} in a loop), every iteration
 * throws. The caller's retry loop should be bounded; an unbounded loop retrying on every {@code
 * IOException} will spin indefinitely, exhausting CPU.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosFileIoReadInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "Input/output error")
 * class ConfigReadFailureTest {
 *   @Test
 *   void applicationFailsFastWithClearErrorOnConfigReadFailure(ConnectionInfo info) {
 *     // assert startup fails with ConfigDataException and pod enters CrashLoopBackOff
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
 * @see ChaosFileIoReadDelay
 * @see ChaosFileIoWriteInjectException
 */
@Repeatable(ChaosFileIoReadInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.FILE_IO,
    operationType = OperationType.FILE_IO_READ)
public @interface ChaosFileIoReadInjectException {

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
   * @ChaosFileIoReadInjectException(id = "primary",  probability = 0.001)
   * @ChaosFileIoReadInjectException(id = "replica",  probability = 0.01)
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
    ChaosFileIoReadInjectException[] value();
  }
}
