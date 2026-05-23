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
 * Delays every {@code Deflater.deflate()} call by a configurable number of milliseconds,
 * simulating CPU-bound deflate compression contention or a degraded compression accelerator.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code ZIP_DEFLATE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until
 * {@code afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code java.util.zip.Deflater.deflate()} (all
 *       overloads) in the target container's JVM.
 *   <li>Before forwarding the call to the native zlib implementation, the interceptor parks the
 *       calling thread for a duration sampled uniformly between {@link #delayMs()} and
 *       {@link #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real deflation executes and the compressed bytes are written to the
 *       output buffer normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>HTTP response body compression slow.</strong> Servlet containers that compress
 *       responses with GZIP (backed by {@code Deflater}) will produce responses more slowly; assert
 *       that response latency P99 stays within SLA and that the connection is not closed by the
 *       upstream proxy before the body is complete.
 *   <li><strong>ZIP file creation slow.</strong> Code that writes ZIP archives (e.g. export
 *       features, build tools) will take longer per entry; assert that the operation does not time
 *       out if it is invoked from within a request handler.
 *   <li><strong>Kafka log compaction delayed.</strong> Kafka uses GZIP internally for log
 *       compaction; slow deflation delays compaction cycles, causing segment retention growth.
 *   <li><strong>Production failure mode:</strong> on CPU-overloaded containers where zlib is
 *       already slow, adding a delay amplifies the latency to the point where connection-keepalive
 *       timeouts fire before the first compressed byte is written, causing the client to close the
 *       connection and receive no response.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code Deflater.deflate()} is ultimately a native call to the zlib {@code deflate()} function
 * via JNI. zlib is CPU-bound during compression, and the JNI boundary releases the JVM-level
 * monitors but does not release the GIL equivalent — threads calling {@code Deflater.deflate()}
 * are fully blocked in native code for the duration of compression, making them invisible to the
 * JVM's thread scheduler.
 *
 * <p>The agent intercepts at the Java-visible {@code Deflater.deflate()} method before the JNI
 * transition, so the delay fires while the thread is still in Java, holding any Java-level monitors
 * it may have acquired around the deflation. This can expose lock-contention issues in code that
 * compresses data while holding an application-level lock.
 *
 * <p>This annotation works in tandem with {@link ChaosZipInflateDelay}: combining both simulates
 * an asymmetric scenario where compression is slow but decompression is fast (matching a
 * multi-reader, single-writer archive pattern), or where both are slow (matching an overloaded
 * node that must compress inbound data before storing it and decompress it before serving it).
 *
 * <p>The delay applies to every {@code Deflater} instance, whether standalone or embedded inside
 * {@code GZIPOutputStream}, {@code ZipOutputStream}, or other wrappers. Code that uses
 * {@code DeflaterOutputStream} will also be affected because it delegates to a {@code Deflater}
 * internally.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosZipDeflateDelay(delayMs = 100, maxDelayMs = 400)
 * class DeflateDelayTest {
 *   @Test
 *   void compressedHttpResponseCompletesWithinSla(ConnectionInfo info) { ... }
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
@Repeatable(ChaosZipDeflateDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.ZIP_DEFLATE)
public @interface ChaosZipDeflateDelay {

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
   * @ChaosZipDeflateDelay(id = "primary",  probability = 0.001)
   * @ChaosZipDeflateDelay(id = "replica",  probability = 0.01)
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
    ChaosZipDeflateDelay[] value();
  }
}
