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
 * Intercepts {@code SocketChannel.read(ByteBuffer)} and holds the calling thread for {@link
 * #delayMs()} milliseconds before reading bytes from the kernel receive buffer, inflating the time
 * between {@code SelectionKey.OP_READ} readiness and actual data consumption in Netty, Undertow,
 * and NIO-based async I/O frameworks.
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
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code read()} executes normally, draining bytes from
 *       the kernel socket receive buffer into the application's {@code ByteBuffer}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The event loop thread that processes {@code OP_READ} events is blocked during the delay;
 *       all other channels registered on the same event loop are starved of I/O processing; assert
 *       that event-loop starvation detection (Netty's {@code BlockingOperationDetector}) fires or
 *       that the application metrics show event-loop lag.
 *   <li>HTTP/2 flow control: if the application delays reading for long enough, the sender's send
 *       window is exhausted and the sender blocks; assert that the application does not deadlock
 *       when both sides are waiting.
 *   <li>Read-timeout handlers (Netty's {@code IdleStateHandler} with {@code readerIdleTime}) may
 *       fire during the delay if the delay is longer than the configured idle time; assert that the
 *       application closes or reconnects the channel correctly.
 *   <li><strong>Production failure mode:</strong> a slow consumer application delays reading from
 *       its Netty channels; the TCP receive buffer fills; the TCP window shrinks to zero; the
 *       sender's TCP stack blocks, backing pressure all the way to the upstream service — an
 *       application-level slowdown causes a network-level propagation that appears to upstream
 *       services as a packet loss event.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.SocketChannelImpl#read(ByteBuffer)}, the JDK's
 * internal implementation. The call is made by Netty's {@code NioByteUnsafe.read()} inside the
 * event loop thread after {@code Selector.select()} returns {@code OP_READ} as ready for the
 * channel. The chaos delay fires between the selector wakeup and the actual read syscall ({@code
 * recvmsg} or {@code read} on Linux), causing the kernel receive buffer to hold data during the
 * sleep.
 *
 * <p>During the delay, the TCP receive window remains open (the kernel has already acknowledged the
 * data in the receive buffer at the TCP level). The sender can continue pushing data until the
 * receive buffer fills, at which point the TCP window shrinks to zero and the sender pauses. This
 * is the correct model for testing back-pressure propagation: the chaos delay simulates a slow
 * consumer, and the back-pressure propagates to the sender at the TCP layer.
 *
 * <p>In Netty, {@code NioByteUnsafe.read()} reads in a loop until the channel has no more data or
 * the configured {@code maxMessagesPerRead} is reached. Each loop iteration calls {@code
 * SocketChannel.read()}, so the chaos delay fires on every iteration of the read loop — a large
 * message split across multiple TCP segments will incur the delay once per {@code read()} call,
 * potentially multiplying the total delay.
 *
 * <p>For {@code ScatteringByteChannel#read(ByteBuffer[])} (scatter reads used by zero-copy
 * optimised paths), a separate intercept is required; this annotation covers only the single {@code
 * ByteBuffer} variant which is the most common path in application code.
 *
 * <p>Combining this with {@link ChaosNioChannelWriteDelay} simulates a symmetric slow-link scenario
 * where both incoming and outgoing data are delayed, which is the typical effect of a throttled
 * network interface or a satellite link with high latency in both directions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelReadDelay(delayMs = 200)
 * class EventLoopStarvationTest {
 *   @Test
 *   void eventLoopLagIsDetectedAndAlertsAreFired(ConnectionInfo info) {
 *     // assert event-loop lag metric exceeds threshold
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
 * @see ChaosNioChannelReadInjectException
 * @see ChaosNioChannelWriteDelay
 * @see ChaosNioSelectorSelectDelay
 */
@Repeatable(ChaosNioChannelReadDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_READ)
public @interface ChaosNioChannelReadDelay {

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
   * @ChaosNioChannelReadDelay(id = "primary",  probability = 0.001)
   * @ChaosNioChannelReadDelay(id = "replica",  probability = 0.01)
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
    ChaosNioChannelReadDelay[] value();
  }
}
