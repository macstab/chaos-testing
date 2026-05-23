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
 * Intercepts {@code SocketChannel.write(ByteBuffer)} and throws the configured exception before
 * any bytes are copied to the kernel send buffer, simulating a mid-response write failure that
 * causes Netty or Undertow to close the channel and signal the pipeline's exception handler.
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
 *   <li>Before every call to {@code java.nio.channels.SocketChannel#write(ByteBuffer)} inside the
 *       target container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The agent reflectively instantiates the class named by {@link #exceptionClassName()} with
 *       the message from {@link #message()} and throws it; no bytes are written to the kernel
 *       send buffer.
 *   <li>The exception propagates to Netty's {@code NioSocketChannel.doWriteBytes()} caller which
 *       passes it to {@code pipeline().fireExceptionCaught()} and closes the channel.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Netty's {@code ChannelOutboundBuffer} is not drained; its pending bytes remain; assert
 *       that Netty closes the channel and releases all pending write buffers to avoid memory leaks
 *       in frameworks that track unacknowledged outbound data.
 *   <li>The remote peer receives an abrupt TCP RST (the channel is closed without draining the
 *       kernel send buffer); assert that the client side surfaces a {@code connection reset}
 *       {@code IOException} and that the client's retry logic fires.
 *   <li>Netty's {@code ChannelFuture} for the pending write completes exceptionally with the
 *       injected exception; listeners registered via {@code addListener()} receive the failure;
 *       assert that the application does not leak resources associated with the failed write.
 *   <li>Inject {@code javax.net.ssl.SSLException} to simulate a TLS record write failure; Netty's
 *       {@code SslHandler} catches it and propagates it through {@code exceptionCaught}; assert
 *       that the TLS session is properly invalidated and not reused by the connection pool.
 *   <li><strong>Production failure mode:</strong> a pod is evicted by Kubernetes OOM killer
 *       mid-response; the container's network namespace is destroyed; all active
 *       {@code SocketChannel.write()} calls throw {@code IOException: Broken pipe}; Netty fires
 *       {@code exceptionCaught} on every open channel simultaneously; if the application's handler
 *       performs any blocking operation (e.g. DB rollback) inside {@code exceptionCaught} on the
 *       event loop thread, the event loop is blocked and no further channels can be serviced.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SocketChannelImpl#write(ByteBuffer)} via Byte
 * Buddy. Netty's {@code NioSocketChannel} overrides {@code doWriteBytes(ChannelOutboundBuffer)} to
 * call {@code javaChannel().write(buf.nioBuffer())} in a loop until either all bytes are written
 * or the channel signals that the send buffer is full (returns 0 bytes). The chaos exception fires
 * inside this loop on the first call, causing {@code AbstractNioByteChannel.doWrite()} to catch
 * it and call {@code close(voidPromise())} on the channel.
 *
 * <p>When Netty closes the channel after a write exception, {@code channelInactive()} fires on
 * the pipeline. All pending {@code ChannelFuture}s in {@code ChannelOutboundBuffer} are failed
 * with {@code ClosedChannelException}. Applications using promise-based write patterns must
 * register listeners on every write future; unfuture-tracked writes cause silent data loss that
 * only becomes visible when the channel is closed.
 *
 * <p>Partial writes present a correctness challenge: if the write loop wrote some bytes
 * successfully before the chaos intercept fires on a subsequent iteration, the peer receives a
 * partial response frame. HTTP/1.1 clients may attempt to parse the partial response and close the
 * connection with a parse error; HTTP/2 clients detect a mid-stream frame truncation via the frame
 * header length field and close the connection with a {@code FRAME_SIZE_ERROR} GOAWAY frame.
 *
 * <p>For gRPC-over-HTTP/2 servers, a write exception mid-stream closes all multiplexed streams
 * simultaneously. Each in-flight gRPC call's response future completes exceptionally. Client-side
 * retry policies must distinguish between a retriable status (e.g. {@code UNAVAILABLE}) and a
 * non-retriable status (e.g. {@code INTERNAL}) to avoid re-running non-idempotent RPCs.
 *
 * <p>Unlike {@link ChaosNioChannelReadInjectException} which simulates an inbound fault seen by
 * the server, this annotation simulates an outbound fault seen by the client that sent the
 * request. Combined, they exercise both directions of a bidirectional NIO connection failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelWriteInjectException(
 *     exceptionClassName = "java.io.IOException",
 *     message = "broken pipe")
 * class MidResponseWriteFailureTest {
 *   @Test
 *   void pendingWriteFuturesAreFailedAndNoLeaksOccur(ConnectionInfo info) {
 *     // assert all ChannelFutures failed and ChannelOutboundBuffer is empty
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
 * @see ChaosNioChannelWriteDelay
 * @see ChaosNioChannelReadInjectException
 */
@Repeatable(ChaosNioChannelWriteInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_WRITE)
public @interface ChaosNioChannelWriteInjectException {

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
   * @ChaosNioChannelWriteInjectException(id = "primary",  probability = 0.001)
   * @ChaosNioChannelWriteInjectException(id = "replica",  probability = 0.01)
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
    ChaosNioChannelWriteInjectException[] value();
  }
}
