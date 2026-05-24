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
 * Intercepts {@code SocketChannel.connect()} and immediately throws {@code java.io.IOException}
 * before any TCP handshake occurs, simulating a hard connection-refused failure at the NIO channel
 * layer used by Netty, Undertow, and async servlet containers.
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
 *   <li>Before every call to {@code java.nio.channels.SocketChannel#connect(SocketAddress)} inside
 *       the target container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The agent throws {@code java.io.IOException} immediately — no SYN packet is sent, no file
 *       descriptor state is modified, and the channel remains in its pre-connect state.
 *   <li>The exception propagates to the NIO framework's connect-completion handler; Netty's {@code
 *       ChannelFuture} is completed exceptionally with this exception.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Netty's {@code Bootstrap.connect()} future completes with {@code IOException}; assert that
 *       the application's {@code ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE} path is invoked
 *       and that the channel is closed cleanly.
 *   <li>Connection pool warm-up in frameworks using Netty connection pools (Reactor Netty's {@code
 *       ConnectionPool}, Vert.x connection pools) will fail entirely; assert that the pool retries
 *       and eventually establishes a minimum-size pool after the fault window closes.
 *   <li>Outbound HTTP clients built on Reactor Netty (Spring WebClient, R2DBC) will surface the
 *       exception as a {@code WebClientRequestException}; assert this type propagates correctly.
 *   <li><strong>Production failure mode:</strong> a firewall rule is mistakenly applied, blocking
 *       outbound TCP from the service to its Redis cluster; every Netty channel connect throws
 *       connection refused; the Lettuce driver's reconnect logic fires and fills the event loop
 *       with reconnect attempts, blocking real I/O on existing connections.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SocketChannelImpl#connect(SocketAddress)} via Byte
 * Buddy. When operating in non-blocking mode, {@code connect()} normally puts the channel into
 * {@code ConnectionPending} state and returns {@code false}; the application then registers {@code
 * SelectionKey.OP_CONNECT} interest and waits for the selector to signal connection completion. The
 * chaos reject fires before this state transition, so the channel never enters {@code
 * ConnectionPending} — it remains in its initial unconnected state.
 *
 * <p>Netty's {@code NioSocketChannel.doConnect()} catches {@code Exception} from the underlying
 * channel connect and propagates it to the pipeline as a {@code ConnectException}. The
 * application's {@code ChannelHandler.exceptionCaught()} and any registered {@code
 * ChannelFutureListener}s will receive this signal. A correct Netty application must close the
 * channel in this handler; if it does not, the unclosed channel leaks a file descriptor.
 *
 * <p>Lettuce's Netty-based Redis driver uses exponential back-off reconnect logic; injecting this
 * fault will trigger the reconnect timer. If the fault window is shorter than the back-off ceiling,
 * the driver will successfully reconnect when the fault clears. If longer, the driver may stop
 * retrying and require a manual reconnect trigger — test both scenarios.
 *
 * <p>The {@code SelectionKey} readiness bit {@code OP_CONNECT} is never set in the selector's ready
 * set because the channel never reached {@code ConnectionPending}; {@code Selector.select()} will
 * not return this channel as ready. This is the correct behaviour for a rejected connect: from the
 * selector's perspective, the channel was never pending.
 *
 * <p>Unlike {@link ChaosNioChannelConnectDelay} which inflates latency before success, this
 * annotation produces immediate hard failure, exercising the fast-fail code path in connection
 * managers that distinguish between "slow connect" and "connect refused".
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelConnectReject(message = "connection refused by chaos")
 * class NettyConnectRejectedTest {
 *   @Test
 *   void lettuceReconnectTimerFires(ConnectionInfo info) {
 *     // assert Lettuce reconnects after fault clears and commands resume
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
 * @see ChaosNioChannelConnectDelay
 * @see ChaosNioChannelReadInjectException
 */
@Repeatable(ChaosNioChannelConnectReject.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.RejectTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_CONNECT)
public @interface ChaosNioChannelConnectReject {

  /**
   * @return exception message used by the reject effect
   */
  String message() default "rejected by chaos L1";

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
   * @ChaosNioChannelConnectReject(id = "primary",  probability = 0.001)
   * @ChaosNioChannelConnectReject(id = "replica",  probability = 0.01)
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
    ChaosNioChannelConnectReject[] value();
  }
}
