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
 * Intercepts {@code ServerSocketChannel.accept()} and holds the calling thread for {@link
 * #delayMs()} milliseconds before the new inbound connection is handed to the Netty or Undertow
 * pipeline, simulating a slow accept loop that causes the kernel's TCP backlog queue to fill and
 * inbound connection attempts to be silently dropped or RST-ed.
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
 *   <li>Before every call to {@code java.nio.channels.ServerSocketChannel#accept()} inside the
 *       target container's JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()}, {@link
 *       #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code accept()} executes normally, returning the newly
 *       accepted {@code SocketChannel} to the Netty boss group or Undertow acceptor thread.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>During the sleep, the boss event loop thread (Netty) or the acceptor thread (Undertow) is
 *       blocked; new {@code OP_ACCEPT} readiness signals from the selector accumulate but are not
 *       processed; assert that the application's connection-accepted rate drops to approximately
 *       {@code 1000 / delayMs} connections per second during the fault window.
 *   <li>The kernel's listen backlog (configured via {@code SO_BACKLOG} at bind time) fills as
 *       completed TCP handshakes queue ahead of the slow accept loop; once full, the kernel
 *       silently drops SYN packets (Linux) or sends TCP RST (some OS configurations); assert that
 *       connecting clients observe {@code Connection refused} or long connection timeouts rather
 *       than fast rejection, depending on kernel configuration.
 *   <li>Netty's boss group is configured with a fixed number of threads; if each thread is blocked
 *       in the delay, no other {@code ServerSocketChannel} bound on the same boss group can accept
 *       new connections; assert that all listening ports are affected, not just the targeted one.
 *   <li><strong>Production failure mode:</strong> a GC pause on the JVM application server causes
 *       the acceptor threads to stop running; during the pause the kernel backlog fills; after GC
 *       completes the application accepts connections rapidly; clients that timed out during the
 *       pause send a burst of retry connections simultaneously, overwhelming the newly-resumed
 *       accept loop and causing a thundering-herd that extends the recovery time far beyond the
 *       original pause duration.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.ServerSocketChannelImpl#accept()}, the JDK's
 * internal implementation. Netty's {@code NioServerSocketChannel.doReadMessages()} calls {@code
 * javaChannel().accept()} inside the boss event loop after {@code Selector.select()} returns {@code
 * OP_ACCEPT} readiness. The chaos delay fires before the actual accept syscall ({@code accept4} on
 * Linux), blocking the boss loop thread.
 *
 * <p>The TCP three-way handshake completes entirely in the kernel, independently of the
 * application. Completed connections are placed in the kernel's accept queue (the completed
 * backlog). The {@code SO_BACKLOG} parameter passed to {@code ServerSocket.bind()} controls the
 * maximum length of this queue. On Linux, {@code /proc/sys/net/core/somaxconn} caps the actual
 * backlog regardless of the application's requested value. When the accept queue is full, the
 * kernel either drops new SYN packets (so the client retransmits) or sends RST depending on {@code
 * /proc/sys/net/ipv4/tcp_abort_on_overflow}.
 *
 * <p>Netty's boss group calls {@code accept()} in a loop until all pending connections are drained
 * or the configured {@code maxMessagesPerRead} is reached. Each iteration incurs the delay, so a
 * burst of pending connections in the backlog queue will compound the total delay time: ten pending
 * connections at 100 ms each means the boss loop is occupied for a full second before returning to
 * the selector.
 *
 * <p>Undertow uses a dedicated acceptor thread pool (XNIO {@code AcceptingChannel} wrapper) that
 * calls {@code ServerSocketChannel.accept()} from non-event-loop threads; the delay here affects
 * only the acceptor threads and does not directly block I/O worker threads, making the effect more
 * isolated than in Netty's boss-worker model.
 *
 * <p>Combining this with {@link ChaosNioSelectorSelectDelay} fully blocks the selector-accept
 * pipeline: the selector itself is delayed before reporting readiness, and when it does report
 * readiness, the accept call is delayed again before the connection enters the pipeline — a
 * complete simulation of a heavily throttled server accept path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelAcceptDelay(delayMs = 500)
 * class BacklogOverflowTest {
 *   @Test
 *   void kernelBacklogFillsAndClientsExperienceTimeout(ConnectionInfo info) {
 *     // assert SO_BACKLOG is exceeded and new clients receive connection refused
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
 * @see ChaosNioChannelAcceptSuppress
 * @see ChaosNioSelectorSelectDelay
 */
@Repeatable(ChaosNioChannelAcceptDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_ACCEPT)
public @interface ChaosNioChannelAcceptDelay {

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
   * @ChaosNioChannelAcceptDelay(id = "primary",  probability = 0.001)
   * @ChaosNioChannelAcceptDelay(id = "replica",  probability = 0.01)
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
    ChaosNioChannelAcceptDelay[] value();
  }
}
