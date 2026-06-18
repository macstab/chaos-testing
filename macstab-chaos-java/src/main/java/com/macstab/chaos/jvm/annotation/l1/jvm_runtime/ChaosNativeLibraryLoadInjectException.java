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
 * Throws a configurable exception (typically {@link UnsatisfiedLinkError}) at every {@code
 * System.loadLibrary()} or {@code System.load()} call site, simulating a missing or incompatible
 * native library.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code NATIVE_LIBRARY_LOAD} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code System.loadLibrary(String)} and {@code
 *       System.load(String)} in the target container's JVM.
 *   <li>Before the real OS-level library load, the interceptor instantiates the exception class
 *       named by {@link #exceptionClassName()} with {@link #message()} and throws it.
 *   <li>The calling thread unwinds from the throw site; the library is never loaded and no native
 *       methods become available.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>JNI static initialiser fails.</strong> If the load call is in a static initialiser
 *       block, the class will fail to initialise and will throw {@code NoClassDefFoundError} on
 *       every subsequent attempt to use it; assert that the application detects the unavailable
 *       native component and falls back to a pure-Java implementation or shuts down gracefully.
 *   <li><strong>Optional native acceleration missing.</strong> Many libraries (LZ4, Snappy, Netty
 *       native transport) check whether the native library is available and fall back to Java;
 *       assert that the fallback is exercised and that its performance characteristics are
 *       acceptable for the test scenario.
 *   <li><strong>Security module unavailable.</strong> HSM or PKCS#11 integrations that load a
 *       native provider library will fail; assert that the application rejects cryptographic
 *       operations rather than silently degrading to software-only crypto.
 *   <li><strong>Production failure mode:</strong> on container deployments where the native library
 *       is provided by an init container or a volume mount, a race condition or a mount failure
 *       causes the library to be absent; the application must detect this and either restart or
 *       enter a safe degraded mode rather than continuing with uninitialised native state.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The standard exception thrown by {@code System.loadLibrary()} on failure is {@code
 * java.lang.UnsatisfiedLinkError}, an {@code Error} (not a checked exception). Because it is an
 * {@code Error}, many application frameworks do not catch it in their normal exception handlers, so
 * an {@code UnsatisfiedLinkError} during static initialisation causes the class loader to mark the
 * class as failed and throws {@code NoClassDefFoundError} on any subsequent attempt to use the
 * class.
 *
 * <p>The agent throws the configured exception class at the method-entry point, before the JVM
 * attempts to find or open the native library file. This means the JVM's native-library table is
 * not updated, so even if the annotation is removed later (e.g. by a test teardown), the load call
 * will succeed on a retry. Tests that need to observe the "library available after retry" scenario
 * should combine this annotation with a probabilistic injection rate rather than injecting on every
 * call.
 *
 * <p>To test the "library not on java.library.path" scenario authentically, use the {@code
 * exceptionClassName = "java.lang.UnsatisfiedLinkError"} default. To test an application-level
 * exception handler that wraps the native load in a try-catch, use a custom exception class from
 * the application's own classpath.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNativeLibraryLoadInjectException(
 *     exceptionClassName = "java.lang.UnsatisfiedLinkError",
 *     message = "librocksdb.so: cannot open shared object file")
 * class NativeLoadFailureTest {
 *   @Test
 *   void applicationFallsBackToJavaStorageEngineWhenNativeUnavailable(ConnectionInfo info) { ... }
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
@Repeatable(ChaosNativeLibraryLoadInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.NATIVE_LIBRARY_LOAD)
public @interface ChaosNativeLibraryLoadInjectException {

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
   * @ChaosNativeLibraryLoadInjectException(id = "primary",  probability = 0.001)
   * @ChaosNativeLibraryLoadInjectException(id = "replica",  probability = 0.01)
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
    ChaosNativeLibraryLoadInjectException[] value();
  }
}
