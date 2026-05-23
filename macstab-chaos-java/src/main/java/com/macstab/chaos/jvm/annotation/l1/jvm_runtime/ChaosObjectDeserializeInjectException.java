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
 * Throws a configurable exception at every {@code ObjectInputStream.readObject()} call site,
 * simulating a corrupted serialised stream, a class-version mismatch, or an incompatible payload
 * received from a remote peer.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code OBJECT_DESERIALIZE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code ObjectInputStream.readObject()} in the
 *       target container's JVM.
 *   <li>Before the real deserialisation, the interceptor instantiates the exception class named by
 *       {@link #exceptionClassName()} with {@link #message()} and throws it.
 *   <li>The calling thread unwinds from the throw site; no object is reconstructed.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Session restoration fails at failover.</strong> The servlet container will fail
 *       to restore the session object; assert that the application redirects to login or creates a
 *       new session rather than serving a 500 error.
 *   <li><strong>Incoming RMI arguments rejected.</strong> The RMI dispatcher will receive an
 *       exception during argument deserialisation; assert that the call fails with a
 *       {@code MarshalException} on the client side and that the server does not enter an
 *       inconsistent state.
 *   <li><strong>Distributed cache read returns error.</strong> A cache miss due to deserialisation
 *       failure should be treated the same as a cache miss due to TTL expiry; assert that the
 *       application falls through to the database rather than propagating the exception.
 *   <li><strong>Production failure mode:</strong> deserialisation failures from a rolling upgrade
 *       (old serialisation format incompatible with new class definition) cause the receiving node
 *       to drop all in-flight messages from the old node, potentially causing message loss in
 *       Akka clusters or Hazelcast partitions.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The injected exception is thrown before the {@code ObjectInputStream} reads even the class
 * descriptor, so the {@code InputStream} position is not advanced. Callers that wrap the input in
 * a buffered stream may attempt to re-read the same bytes on retry, which would succeed if the
 * chaos rule is probabilistic and the second attempt is not intercepted. Tests should decide
 * whether this retry-succeeds behaviour is the expected production pattern.
 *
 * <p>The natural exception types for deserialisation failures are {@code java.io.IOException}
 * (I/O-level errors), {@code java.io.InvalidClassException} (class incompatibility),
 * {@code java.io.StreamCorruptedException} (bad magic number), and
 * {@code java.lang.ClassNotFoundException} (class not on classpath). Each tests a different branch
 * of the application's exception handler; configure {@link #exceptionClassName()} to match the
 * scenario being tested.
 *
 * <p>Security note: a real attacker can craft malicious serialised payloads that trigger
 * gadget-chain exploits during {@code readObject()}. This annotation does not simulate that —
 * it prevents the payload from being processed at all. For testing defensive deserialisation
 * hardening, the annotation is therefore most useful for validating that the application's error
 * handler runs and that the exception is logged and reported, not for simulating an exploit.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosObjectDeserializeInjectException(
 *     exceptionClassName = "java.io.InvalidClassException",
 *     message = "serialVersionUID mismatch during rolling upgrade")
 * class DeserializeExceptionTest {
 *   @Test
 *   void cacheReadFallsBackToDatabaseOnDeserializationError(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.
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
@Repeatable(ChaosObjectDeserializeInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.OBJECT_DESERIALIZE)
public @interface ChaosObjectDeserializeInjectException {

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
   * @ChaosObjectDeserializeInjectException(id = "primary",  probability = 0.001)
   * @ChaosObjectDeserializeInjectException(id = "replica",  probability = 0.01)
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
    ChaosObjectDeserializeInjectException[] value();
  }
}
