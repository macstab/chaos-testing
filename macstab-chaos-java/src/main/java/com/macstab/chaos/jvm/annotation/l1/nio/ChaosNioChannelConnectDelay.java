/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.nio;

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
 * Intercepts {@code SocketChannel.connect()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the TCP handshake begins, simulating slow connection establishment as seen
 * by Netty, Undertow, and any other NIO-based async I/O framework.
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
 *   <li>Before every call to {@code java.nio.channels.SocketChannel#connect(SocketAddress)}
 *       inside the target container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code connect()} executes normally, initiating
 *       the TCP three-way handshake.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Every non-blocking TCP connection attempt takes at least {@link #delayMs()} ms longer
 *       before the SYN packet is sent; assert that Netty's connect-timeout handler fires when
 *       the injected delay exceeds {@code Bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS)}.
 *   <li>Netty's event loop thread is blocked during the sleep if the connect call is made from
 *       the event loop — this stalls all other channels registered on the same selector; assert
 *       that the application does not make blocking connect calls from event loop threads.
 *   <li>Connection pool warm-up: frameworks that pre-warm connection pools at startup will take
 *       longer to complete; assert that the health-check endpoint returns STARTING rather than
 *       UP during the delay.
 *   <li><strong>Production failure mode:</strong> a BGP routing change increases RTT from 1 ms
 *       to 400 ms; Netty client connect attempts block for 400 ms per SYN; the event loop
 *       thread is occupied and cannot process responses for existing connections — throughput
 *       drops to zero while connections are being established.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SocketChannelImpl#connect(SocketAddress)}, the
 * JDK's internal implementation of {@code SocketChannel}. In non-blocking mode, {@code connect()}
 * initiates the handshake and returns immediately (the channel transitions to
 * {@code ConnectionPending} state); the completion is signalled via {@code SelectionKey.OP_CONNECT}
 * readiness on the next {@code Selector.select()} call. The chaos delay fires before the
 * {@code connect()} call itself, so it adds to the time before the SYN packet is sent, not to
 * the TCP handshake RTT.
 *
 * <p>In blocking mode (the channel's blocking mode is set to true), {@code connect()} blocks
 * until the handshake completes or the OS connection timeout fires. The chaos delay fires before
 * the OS-level connect, compounding with the OS timeout: if the injected delay is longer than the
 * OS timeout ({@code /proc/sys/net/ipv4/tcp_syn_retries} × RTT), the OS timeout fires during
 * the delay and the connect never actually starts.
 *
 * <p>Netty's {@code NioSocketChannel.doConnect()} calls {@code javaChannel().connect()} directly;
 * the Byte Buddy intercept fires inside Netty's event loop thread. Any significant delay here
 * blocks the event loop, preventing all other channels on the same event loop from processing
 * I/O — a single-point-of-failure scenario unique to NIO event-loop architectures. This is
 * distinct from thread-pool-based I/O where only the connecting thread is blocked.
 *
 * <p>Undertow's XNIO layer similarly uses {@code SocketChannel} for outbound connections when
 * acting as a reverse proxy; the same intercept applies. Reactive HTTP clients (Project Reactor
 * Netty, Vert.x) ultimately delegate to {@code SocketChannel.connect()} for TCP establishment.
 *
 * <p>Combining this annotation with {@link ChaosNioSelectorSelectDelay} simulates a network
 * partition where connection attempts are slow and the selector's ready-key notifications are
 * also delayed, fully blocking the NIO pipeline.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelConnectDelay(delayMs = 500)
 * class NettyConnectTimeoutTest {
 *   @Test
 *   void channelConnectTimeoutFiresCorrectly(ConnectionInfo info) {
 *     // assert Netty ConnectTimeoutException when delay > CONNECT_TIMEOUT_MILLIS
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
 * @see ChaosNioChannelConnectReject
 * @see ChaosNioSelectorSelectDelay
 * @see ChaosNioChannelReadDelay
 */
@Repeatable(ChaosNioChannelConnectDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_CONNECT)
public @interface ChaosNioChannelConnectDelay {

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
   * @ChaosNioChannelConnectDelay(id = "primary",  probability = 0.001)
   * @ChaosNioChannelConnectDelay(id = "replica",  probability = 0.01)
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
    ChaosNioChannelConnectDelay[] value();
  }
}
