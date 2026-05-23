/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.ssl;

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
 * Parks the calling thread inside {@link javax.net.ssl.SSLEngine#beginHandshake()
 * SSLEngine.beginHandshake()} for the configured number of milliseconds before the JSSE TLS
 * state machine begins — every TLS handshake takes at least {@code delayMs} longer than the
 * network round-trip alone.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code SSL} selector family with the {@code delay}
 * effect applied to the {@code SSL_HANDSHAKE} operation. It intercepts the JSSE handshake entry
 * point at the JVM level — specifically {@code SSLEngine.beginHandshake()} and
 * {@code SSLSocket.startHandshake()} — and artificially inflates the handshake initiation latency.
 * The annotation is declared on the test class or method alongside a container annotation and is
 * active for the lifetime of the annotated scope (class-scope: {@code beforeAll} to
 * {@code afterAll}; method-scope: {@code beforeEach} to {@code afterEach}).
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code SSLEngine#beginHandshake()} and
 * {@code SSLSocket#startHandshake()}. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the JSSE state machine transitions from
 *       {@code HandshakeStatus.NOT_HANDSHAKING} to its initial handshake state.
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the calling thread for the
 *       configured duration in milliseconds.
 *   <li>After the park returns, the real handshake method executes — the TLS state machine
 *       initiates normally and completes (or fails) based on network conditions.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Total connection-establishment time for HTTPS or TLS connections is at least
 *       {@code delayMs} longer than the non-chaos baseline — assert via client-side timing.
 *   <li>HTTP clients with TLS-handshake timeouts shorter than {@code delayMs} throw
 *       {@code SSLException} or {@code SocketTimeoutException} before the handshake completes —
 *       assert the exception type.
 *   <li>Connection pools that validate connections with a TLS renegotiation on checkout show
 *       elevated checkout latency — assert via pool-checkout timing metrics.
 *   <li>The handshake completes successfully after the delay (assuming the network is healthy) —
 *       assert that data transfer succeeds to distinguish from an exception-injection fault.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a TLS termination proxy under high
 * load that delays {@code ServerHello} by 600 ms — every new HTTPS connection from a Java service
 * that has a 500 ms handshake timeout fails with {@code SSLHandshakeException: Handshake timed
 * out}, causing cascading failures across all downstream endpoints that require fresh TLS sessions.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> JSSE implements TLS through the
 * {@code sun.security.ssl.SSLEngineImpl} and {@code sun.security.ssl.SSLSocketImpl} classes, both
 * of which expose {@code beginHandshake()} / {@code startHandshake()} as the entry point. The
 * agent instruments these methods via the bootstrap class loader channel. The delay fires before
 * the JSSE handshake state ({@code Handshaker} object) is initialised — the underlying
 * {@code SocketChannel} is not yet involved.
 *
 * <p><strong>TLS state-machine interaction.</strong> JSSE's TLS 1.3 state machine manages the
 * handshake through a series of {@code SSLEngineResult} statuses ({@code NEED_TASK},
 * {@code NEED_WRAP}, {@code NEED_UNWRAP}). The delay fires only at the initial
 * {@code beginHandshake()} call; it does not re-fire on each state-machine step. After the park,
 * the state machine runs at normal speed — only the initiation is delayed.
 *
 * <p><strong>Session resumption.</strong> TLS session resumption (TLS 1.2 session IDs or TLS 1.3
 * session tickets) bypasses the full handshake, but still calls {@code beginHandshake()}. The
 * delay fires on resumed sessions too, adding {@code delayMs} to what would otherwise be a
 * fast resumption handshake.
 *
 * <p><strong>Distinction from {@code ChaosSslHandshakeInjectException}.</strong> The delay effect
 * lets the handshake complete normally (with added latency); the TLS connection is eventually
 * established. The inject-exception effect throws before or during the handshake, so the TLS
 * connection is never established and no data is exchanged. Use delay to test handshake-timeout
 * tolerance; use inject-exception to test error handling for TLS failures.
 *
 * <p><strong>Virtual-thread interaction.</strong> Virtual threads that initiate TLS handshakes
 * (e.g. in Loom-based HTTP servers) may pin their carrier during the {@code beginHandshake()} call
 * if the JDK's implementation uses {@code synchronized} internally (JDK 21 JSSE does). The
 * pre-handshake delay park fires before the pinning, so the carrier is free during the park and
 * pinned only during the actual handshake.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSslHandshakeDelay(delayMs = 600)
 * class SslHandshakeDelayTest {
 *
 *   @Test
 *   void httpsClientTimesOutWhenHandshakeIsSlow(AppConnectionInfo info) {
 *     // client has 500 ms TLS timeout; handshake delay alone is 600 ms
 *     assertThatThrownBy(() -> client.httpsGet(info, "https://downstream/api"))
 *         .isInstanceOf(SSLException.class);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#SSL_HANDSHAKE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#ssl(java.util.Set)
 * @see ChaosSslHandshakeInjectException
 */
@Repeatable(ChaosSslHandshakeDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.SSL,
    operationType = OperationType.SSL_HANDSHAKE)
public @interface ChaosSslHandshakeDelay {

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
   * @ChaosSslHandshakeDelay(id = "primary",  probability = 0.001)
   * @ChaosSslHandshakeDelay(id = "replica",  probability = 0.01)
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
    ChaosSslHandshakeDelay[] value();
  }
}
