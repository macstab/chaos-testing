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
 * Intercepts {@code SocketChannel.read(ByteBuffer)} and throws the configured exception instead of
 * reading bytes from the kernel buffer, simulating a mid-stream connection failure that causes
 * Netty or Undertow to close the channel and invoke the pipeline's exception-caught handler.
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
 *   <li>Before every call to {@code java.nio.channels.SocketChannel#read(ByteBuffer)} inside the
 *       target container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no bytes are read from the kernel
 *       receive buffer.
 *   <li>Netty's {@code NioByteUnsafe.read()} catches the exception, fires {@code
 *       pipeline().fireExceptionCaught()}, and closes the channel.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The Netty channel is closed by the framework after the read exception; assert that the
 *       application's {@code channelInactive()} handler is invoked and any associated resources
 *       (timers, in-flight requests) are cleaned up.
 *   <li>Inject {@code java.io.IOException} (the default) to simulate a connection reset; Netty will
 *       fire {@code exceptionCaught} followed by {@code channelInactive} on the pipeline.
 *   <li>Reactor Netty's HTTP client: a read exception mid-response causes the active {@code
 *       Mono<HttpClientResponse>} to signal an error; assert that the error is propagated to the
 *       subscriber and that the connection is removed from the connection pool.
 *   <li><strong>Production failure mode:</strong> a Kubernetes rolling restart closes TCP
 *       connections mid-response; the Netty-based HTTP client receives {@code IOException:
 *       Connection reset by peer} on the read; applications without retry logic return partial
 *       responses or fail with HTTP 500, depending on how much of the response was processed.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SocketChannelImpl#read(ByteBuffer)}. Netty's {@code
 * NioByteUnsafe.read()} surrounds the read call in a try-catch; any {@code Throwable} triggers
 * {@code handleReadException(pipeline, byteBuf, cause, close, allocHandle)}. The method calls
 * {@code pipeline().fireExceptionCaught(cause)} and then evaluates whether to close the channel.
 * For {@code IOException}, Netty will close the channel; for other exception types the behaviour
 * depends on Netty's exception decision logic in {@code DefaultChannelPipeline}.
 *
 * <p>The {@code SelectionKey.OP_READ} interest bit remains set after the exception; the next {@code
 * Selector.select()} call may again signal readiness for the now-closed channel. Netty's channel
 * registration cleanup removes the channel from the selector after {@code channelInactive} fires.
 * Tests should assert that the channel is fully deregistered and that the selector no longer
 * returns this channel as ready.
 *
 * <p>In HTTP/2 multiplexed connections, a read exception on the TCP channel closes all active
 * streams simultaneously. Each stream's response future completes exceptionally. Applications using
 * HTTP/2 must handle bulk stream failure differently from single-request failure — this annotation
 * exercises that path.
 *
 * <p>Reactor Netty's connection pool evicts the connection when a read exception occurs; a new
 * connection is acquired for the next request. If the fault rate is high, the pool's connection
 * creation rate increases; if creation also fails (see {@link ChaosNioChannelConnectReject}), the
 * pool will eventually reach its maximum pending acquire and fail fast.
 *
 * <p>Inject {@code javax.net.ssl.SSLException} to simulate a TLS read failure (e.g. invalid record
 * MAC), which is distinct from a TCP-level read failure. SSL exceptions propagate through Netty's
 * {@code SslHandler} pipeline stage; assert that the handler closes the channel and logs a
 * TLS-specific error rather than a generic connection reset.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelReadInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "connection reset by peer")
 * class MidResponseResetTest {
 *   @Test
 *   void channelInactiveIsCalledAndResourcesAreReleased(ConnectionInfo info) {
 *     // assert no resource leaks after channel closure
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
 * @see ChaosNioChannelReadDelay
 * @see ChaosNioChannelWriteInjectException
 */
@Repeatable(ChaosNioChannelReadInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_READ)
public @interface ChaosNioChannelReadInjectException {

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
   * @ChaosNioChannelReadInjectException(id = "primary",  probability = 0.001)
   * @ChaosNioChannelReadInjectException(id = "replica",  probability = 0.01)
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
    ChaosNioChannelReadInjectException[] value();
  }
}
