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
 * Intercepts {@code SocketChannel.write(ByteBuffer)} and holds the calling thread for {@link
 * #delayMs()} milliseconds before flushing bytes to the kernel send buffer, simulating a slow
 * sender or a saturated network interface as experienced by Netty, Undertow, and all NIO-based
 * frameworks that write to the channel directly.
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
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code write()} executes normally, copying bytes from
 *       the application's {@code ByteBuffer} into the kernel TCP send buffer.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Netty's write pipeline is blocked during the delay; any subsequent writes queued on the
 *       same channel accumulate in Netty's {@code ChannelOutboundBuffer}; assert that the buffer's
 *       high-water mark is crossed and that {@code Channel.isWritable()} returns {@code false},
 *       triggering back-pressure notifications to the application.
 *   <li>Write-timeout handlers (Netty's {@code IdleStateHandler} with {@code writerIdleTime}) may
 *       fire during the delay if the delay exceeds the configured idle time; assert that the
 *       application closes or reconnects the channel correctly rather than leaving a stale channel
 *       in the pool.
 *   <li>HTTP/2 frame writers flush HEADERS and DATA frames via {@code SocketChannel.write()}; each
 *       frame incurs the delay, causing stream-level flow control windows to remain open while the
 *       sender holds data; assert that the receiver does not time out on an incomplete response.
 *   <li>Reactor Netty's outbound pipeline eventually calls {@code SocketChannel.write()} after
 *       encoding; a long delay here means the {@code Mono.timeout()} on the subscriber fires before
 *       the request bytes are sent; assert that the timeout propagates as {@code TimeoutException}
 *       and that the connection is evicted from the pool.
 *   <li><strong>Production failure mode:</strong> a network interface on the application host
 *       becomes saturated; the OS kernel write buffer fills for all outbound connections; every
 *       {@code SocketChannel.write()} blocks in {@code send(2)} (blocking channels) or returns zero
 *       bytes written (non-blocking channels); Netty re-registers {@code OP_WRITE} interest on the
 *       selector and retries on the next loop iteration; the event loop is occupied servicing
 *       write-ready events instead of read-ready events, starving inbound processing and causing
 *       inbound requests to queue behind the saturated outbound path.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SocketChannelImpl#write(ByteBuffer)}, the JDK's
 * internal implementation of {@code SocketChannel}. Netty's {@code NioSocketChannel.doWriteBytes()}
 * calls this method directly inside the event loop thread after dequeuing a buffer from {@code
 * ChannelOutboundBuffer}. The chaos delay fires synchronously before the kernel {@code send}
 * syscall, blocking the event loop thread for the duration of the sleep.
 *
 * <p>In non-blocking mode, {@code SocketChannel.write()} returns the number of bytes actually
 * written, which may be less than the buffer's remaining bytes if the kernel send buffer is full.
 * Netty handles partial writes by re-registering {@code SelectionKey.OP_WRITE} interest on the
 * selector and retrying when the selector signals write readiness. The chaos delay compounds with
 * each retry: a message requiring three write calls incurs the delay three times, one per {@code
 * write()} invocation.
 *
 * <p>Netty's {@code ChannelOutboundBuffer} tracks the total pending bytes across all queued writes.
 * When the total exceeds the configured high-water mark ({@code WriteBufferWaterMark.high()}),
 * Netty sets {@code Channel.isWritable()} to {@code false} and fires {@code
 * channelWritabilityChanged(false)} on all handlers. Handlers that respect back-pressure should
 * pause sending new messages. The delay annotation exercises this path by slowing down the write
 * path enough for the buffer to cross the high-water mark.
 *
 * <p>Undertow's XNIO layer uses {@code StreamSinkChannel.write()} which delegates to {@code
 * SocketChannel.write()} for the underlying channel; the same intercept fires. Reactive HTTP
 * clients built on Reactor Netty ultimately call through the same chain: Flux encoding → Netty
 * pipeline → {@code NioSocketChannel.doWriteBytes()} → {@code SocketChannelImpl.write()}.
 *
 * <p>Combining this annotation with {@link ChaosNioChannelReadDelay} models a symmetric
 * congested-link scenario. Combining it with {@link ChaosNioSelectorSelectDelay} models a fully
 * degraded NIO pipeline where both selector readiness notification and the actual write are
 * delayed, which is the worst-case profile for a throttled container network interface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelWriteDelay(delayMs = 300)
 * class SlowSenderBackPressureTest {
 *   @Test
 *   void channelWritabilityChangedFiresAndPausesProducer(ConnectionInfo info) {
 *     // assert Channel.isWritable() becomes false and producer pauses
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
 * @see ChaosNioChannelWriteInjectException
 * @see ChaosNioChannelReadDelay
 * @see ChaosNioSelectorSelectDelay
 */
@Repeatable(ChaosNioChannelWriteDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_WRITE)
public @interface ChaosNioChannelWriteDelay {

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
   * @ChaosNioChannelWriteDelay(id = "primary",  probability = 0.001)
   * @ChaosNioChannelWriteDelay(id = "replica",  probability = 0.01)
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
    ChaosNioChannelWriteDelay[] value();
  }
}
