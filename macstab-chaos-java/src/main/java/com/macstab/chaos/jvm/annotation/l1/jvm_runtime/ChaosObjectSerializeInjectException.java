/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.jvm_runtime;

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
 * Throws a configurable exception at every {@code ObjectOutputStream.writeObject()} call site,
 * simulating a broken serialisation stream such as a closed socket or a codec that rejects an
 * un-serialisable object.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code OBJECT_SERIALIZE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code ObjectOutputStream.writeObject(Object)} in
 *       the target container's JVM.
 *   <li>Before the real serialisation, the interceptor instantiates the exception class named by
 *       {@link #exceptionClassName()} with {@link #message()} as its message and throws it.
 *   <li>The calling thread unwinds from the throw site; the object is never written to the stream.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Session replication fails.</strong> Servlet container session replication via Java
 *       serialisation will fail on every replication attempt; assert that the local session remains
 *       usable and the user is not logged out, even though replication is broken.
 *   <li><strong>RMI call fails with IOException.</strong> Java RMI serialises arguments before
 *       transmission; a thrown {@code IOException} surfaces as a {@code MarshalException} at the
 *       RMI call site; assert that the remote interface's error contract is met.
 *   <li><strong>Distributed cache write fails.</strong> Caches using Java serialisation for remote
 *       storage will see every write fail; assert that the application falls back to the local
 *       cache or returns stale data rather than propagating an error to the end user.
 *   <li><strong>Production failure mode:</strong> a serialisation failure in a Kafka producer's
 *       {@code Serializer} implementation causes the message to be dropped or the producer to enter
 *       an error state, resulting in silent data loss or application shutdown.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The injected exception is thrown by the agent's advice method before the real {@code
 * ObjectOutputStream.writeObject()} is invoked. The exception class is loaded by the thread's
 * context class loader using the binary name supplied in {@link #exceptionClassName()}; if the
 * class cannot be found, the agent logs a warning and allows the call to proceed normally rather
 * than failing silently with a different error.
 *
 * <p>For {@code ObjectOutputStream}, the natural exception type is {@code java.io.IOException} (or
 * its subclass {@code java.io.NotSerializableException}). Using a custom application exception
 * class is possible if that class is on the container's classpath; this allows tests to simulate
 * application-layer serialisation failures (e.g. a codec that throws a proprietary exception) in
 * addition to standard I/O failures.
 *
 * <p>Because the exception is thrown before the stream is written to, there is no partial write to
 * clean up. However, the underlying {@code OutputStream} may be in a consistent state or not,
 * depending on whether the caller wraps it in a buffered stream. Tests should verify that the
 * application does not attempt to continue writing to the same stream after the exception.
 *
 * <p>This annotation tests the error-handling path. For testing slow-but-succeeding serialisation,
 * use {@link ChaosObjectSerializeDelay} instead.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosObjectSerializeInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "Serialization stream closed unexpectedly")
 * class SerializeExceptionTest {
 *   @Test
 *   void sessionRemainsUsableWhenReplicationFails(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an {@code
 *       ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>Chaos agent JAR</strong> accessible at the path configured in
 *       {@code @JvmAgentChaos}.
 *   <li><strong>{@code macstab-chaos-java} on the test classpath</strong> — required for the
 *       translator.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosObjectSerializeInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.OBJECT_SERIALIZE)
public @interface ChaosObjectSerializeInjectException {

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
   * @ChaosObjectSerializeInjectException(id = "primary",  probability = 0.001)
   * @ChaosObjectSerializeInjectException(id = "replica",  probability = 0.01)
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
    ChaosObjectSerializeInjectException[] value();
  }
}
