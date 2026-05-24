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
 * Delays every {@code Inflater.inflate()} call by a configurable number of milliseconds, simulating
 * CPU-bound inflate decompression contention or a degraded decompression path.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent L1 chaos primitive targeting the {@code ZIP_INFLATE} operation — one typed
 * annotation per (selector family, operation type, effect) tuple. Declared on a test class or
 * {@code @Test} method, it is active from {@code beforeAll}/{@code beforeEach} until {@code
 * afterAll}/{@code afterEach} respectively.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The chaos agent intercepts every call to {@code java.util.zip.Inflater.inflate()} (all
 *       overloads) in the target container's JVM.
 *   <li>Before forwarding the call to the native zlib implementation, the interceptor parks the
 *       calling thread for a duration sampled uniformly between {@link #delayMs()} and {@link
 *       #maxDelayMs()} milliseconds.
 *   <li>After the delay, the real inflation executes and the decompressed bytes are written to the
 *       output buffer normally.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>HTTP request body decompression slow.</strong> Servlet containers that decompress
 *       GZIP-encoded request bodies (backed by {@code Inflater}) will take longer to deliver the
 *       body to the request handler; assert that the request processing timeout is not exceeded
 *       before the body is fully read.
 *   <li><strong>Compressed payload read from Kafka slow.</strong> Kafka consumers that read
 *       GZIP-compressed messages will decompress them slowly; assert that the consumer's poll
 *       interval is not exceeded and that the consumer does not trigger a session timeout.
 *   <li><strong>ZIP archive extraction slow.</strong> Code that reads ZIP entries (e.g. reading
 *       resources from a JAR file, extracting an uploaded archive) will take longer per entry;
 *       assert that the extraction operation does not block the calling thread beyond its
 *       configured timeout.
 *   <li><strong>Production failure mode:</strong> on nodes under memory pressure where zlib's
 *       internal decompression window buffer must be paged in from swap, {@code inflate()} can
 *       block for hundreds of milliseconds, causing the calling thread to appear to hang and
 *       potentially triggering a false positive liveness-probe failure.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code Inflater.inflate()} is the symmetric counterpart to {@code Deflater.deflate()}: it
 * calls the native zlib {@code inflate()} function via JNI, which decompresses data into the
 * supplied output buffer. Decompression is generally faster than compression for the same data, but
 * it is still CPU-bound and memory-intensive for large payloads.
 *
 * <p>The agent intercepts at the Java-visible {@code Inflater.inflate()} method before the JNI
 * transition, so the delay fires while the calling thread is still in managed code. If the thread
 * holds any Java-level monitors at this point (e.g. a lock around a compressed-data cache entry),
 * the delay extends the time the lock is held, exposing potential contention.
 *
 * <p>In HTTP/2 and HTTP/3 servers, header compression (HPACK/QPACK) uses a different codec, not
 * {@code Inflater}. This annotation therefore targets GZIP body compression and ZIP-based
 * resources, not header compression. Tests that need to target header decompression latency must
 * use the method-selector annotations to target the specific HPACK/QPACK implementation.
 *
 * <p>The delay is applied per-call to {@code inflate()}, not per-compressed-stream. A single
 * compressed stream may require many calls to {@code inflate()} if the output buffer is smaller
 * than the decompressed size; the total delay is therefore proportional to the number of {@code
 * inflate()} invocations needed to exhaust the stream. Tests should size the delay and the payload
 * accordingly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosZipInflateDelay(delayMs = 50, maxDelayMs = 200)
 * class InflateDelayTest {
 *   @Test
 *   void kafkaConsumerDoesNotSessionTimeoutUnderSlowDecompression(ConnectionInfo info) { ... }
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
@Repeatable(ChaosZipInflateDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.JVM_RUNTIME,
    operationType = OperationType.ZIP_INFLATE)
public @interface ChaosZipInflateDelay {

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
   * @ChaosZipInflateDelay(id = "primary",  probability = 0.001)
   * @ChaosZipInflateDelay(id = "replica",  probability = 0.01)
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
    ChaosZipInflateDelay[] value();
  }
}
