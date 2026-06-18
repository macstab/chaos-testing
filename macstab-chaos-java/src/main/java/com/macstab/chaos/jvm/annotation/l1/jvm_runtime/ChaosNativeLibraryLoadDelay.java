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
 * Delays every {@code System.loadLibrary()} and {@code System.load()} call by a configurable number
 * of milliseconds, simulating a slow filesystem or a shared library resolver under load.
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
 *   <li>Before forwarding the call, the interceptor parks the calling thread for a duration sampled
 *       uniformly between {@link #delayMs()} and {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real native library loading executes; the library is loaded normally
 *       and becomes available to native methods.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>JNI-dependent startup slow.</strong> Native libraries are typically loaded once at
 *       startup by static initialisers; a delay here extends the time from container start to
 *       readiness-probe success; assert that the readiness probe timeout is configured with enough
 *       headroom.
 *   <li><strong>Lazy-loaded native components slow.</strong> Some frameworks defer native library
 *       loading until first use; assert that the first call to a JNI method succeeds within the
 *       operation's configured timeout even when the load is slow.
 *   <li><strong>Container startup ordering exposed.</strong> If a sidecar or init container
 *       provides the native library via a shared volume, a slow load exposes timing assumptions
 *       about when the volume is available; assert that the application retries rather than failing
 *       permanently.
 *   <li><strong>Production failure mode:</strong> on a host with an overloaded NFS mount or a slow
 *       tmpfs, {@code dlopen} (which backs {@code loadLibrary}) can block for seconds; if the
 *       loading thread holds a class-loading lock, all class loading in the JVM stalls until the
 *       native load completes.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code System.loadLibrary()} delegates to the JVM's dynamic linker ({@code dlopen} on Linux,
 * {@code LoadLibrary} on Windows). The delay fires before the JVM calls the OS-level linker, so it
 * extends the time the class-loading lock is held if the call originates from a static initialiser
 * block. Because the JVM acquires a per-class-loader lock before running static initialisers, a
 * delayed native load can block any thread that attempts to use or define a class in the same class
 * loader until the delay expires and the library loads.
 *
 * <p>The agent intercepts both {@code System.loadLibrary(String)} (logical name, resolved against
 * {@code java.library.path}) and {@code System.load(String)} (absolute path). Both calls are
 * guarded by the JVM's native-library table lock, which is separate from the class-loading lock; a
 * long delay can therefore cause visible pauses even in threads that are not waiting for any class
 * initialisation.
 *
 * <p>Combining this annotation with {@link ChaosNativeLibraryLoadInjectException} (in a repeatable
 * form) allows a test to verify that the application first retries a slow load and then handles a
 * hard failure, matching the behaviour seen when a shared library is temporarily unavailable (e.g.
 * during a volume remount).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNativeLibraryLoadDelay(delayMs = 3_000, maxDelayMs = 5_000)
 * class NativeLoadDelayTest {
 *   @Test
 *   void readinessProbePassesDespiteSlowNativeLoad(ConnectionInfo info) { ... }
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
@Repeatable(ChaosNativeLibraryLoadDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.NATIVE_LIBRARY_LOAD)
public @interface ChaosNativeLibraryLoadDelay {

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
   * @ChaosNativeLibraryLoadDelay(id = "primary",  probability = 0.001)
   * @ChaosNativeLibraryLoadDelay(id = "replica",  probability = 0.01)
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
    ChaosNativeLibraryLoadDelay[] value();
  }
}
