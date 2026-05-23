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
 * Intercepts {@code ServerSocketChannel.accept()} and returns {@code null} without dequeuing any
 * connection from the kernel backlog, simulating a server that continuously ignores incoming TCP
 * connections until the kernel backlog fills and new connection attempts are rejected.
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
 *   <li>The agent returns {@code null} immediately without calling the real {@code accept()}
 *       syscall; no connection is dequeued from the kernel's completed-handshake queue.
 *   <li>Netty's {@code NioServerSocketChannel.doReadMessages()} interprets a {@code null} accept
 *       result as no new connection available and returns without adding a child channel to the
 *       read list; the connection remains in the kernel backlog.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The kernel backlog fills as completed TCP handshakes accumulate without being dequeued;
 *       once the backlog is exhausted, the kernel drops SYN packets or sends RST to new
 *       connecting clients; assert that connecting clients observe {@code Connection refused}
 *       errors or connection timeouts depending on kernel configuration.
 *   <li>Existing established connections are not affected by this annotation; only new inbound
 *       connections are suppressed; assert that already-connected clients continue to exchange
 *       data normally while new clients cannot connect.
 *   <li>The {@code OP_ACCEPT} readiness bit remains set in the selector's ready set; the boss
 *       event loop continues calling {@code accept()} on each selector loop iteration, receiving
 *       {@code null} each time; assert that the boss loop does not spin at 100% CPU in response
 *       to persistent spurious accept readiness signals.
 *   <li>After the fault window closes, the suppressed connections in the kernel backlog are
 *       accepted normally; assert that applications recover gracefully and process the backlogged
 *       connections without dropping them.
 *   <li><strong>Production failure mode:</strong> a thread-pool deadlock inside the Netty
 *       child-channel initializer ({@code channelInitializer.initChannel()}) causes the boss
 *       group to block; from the kernel's perspective, {@code accept()} is not being called; the
 *       listen backlog fills silently; health checks that open new TCP connections report the
 *       server as down while existing long-lived connections continue to work — misleading
 *       operators into thinking there is a network problem rather than an application deadlock.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code sun.nio.ch.ServerSocketChannelImpl#accept()}. In NIO
 * non-blocking mode, {@code ServerSocketChannel.accept()} returns {@code null} when no connection
 * is pending in the kernel accept queue — this is the normal non-blocking semantic that Netty uses
 * to terminate its accept loop. The suppress effect exploits this by returning {@code null}
 * unconditionally, causing Netty to believe there are no pending connections even when the kernel
 * backlog is full.
 *
 * <p>The kernel's TCP accept queue has two stages: the SYN queue (incomplete handshakes) and the
 * accept queue (completed three-way handshakes). {@code SO_BACKLOG} controls the maximum size of
 * the accept queue on Linux (via {@code /proc/sys/net/core/somaxconn} as an upper bound). When the
 * accept queue is full, the kernel's behaviour is controlled by
 * {@code /proc/sys/net/ipv4/tcp_abort_on_overflow}: if {@code 0}, the kernel drops SYN packets
 * and the client retransmits (apparent timeout); if {@code 1}, the kernel sends RST and the client
 * gets an immediate connection refused.
 *
 * <p>Netty's boss group calls {@code doReadMessages()} from the event loop after the selector
 * signals {@code OP_ACCEPT}. The method loops calling {@code accept()} until it returns
 * {@code null} (meaning the accept queue is drained) or the configured {@code maxMessagesPerRead}
 * is reached. With the suppress effect, every iteration returns {@code null} immediately, so the
 * loop exits after zero messages, but the selector will signal {@code OP_ACCEPT} again on the very
 * next {@code select()} call since connections remain in the queue. This creates a tight
 * select-suppress-return loop that, while CPU-cheap, never makes progress.
 *
 * <p>Unlike {@link ChaosNioChannelAcceptDelay} which slows the accept rate, this annotation
 * halts it entirely. The distinction is important for testing: a delay allows recovery tests
 * (connections eventually get through), while suppress models a complete listen-path failure
 * with no recovery until the annotation is removed.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosNioChannelAcceptSuppress
 * class ListenBacklogExhaustionTest {
 *   @Test
 *   void newConnectionsAreRejectedAndExistingOnesStillWork(ConnectionInfo info) {
 *     // assert existing clients see normal responses
 *     // assert new connection attempts fail with connection refused
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
 * @see ChaosNioChannelAcceptDelay
 * @see ChaosNioSelectorSelectSpuriousWakeup
 */
@Repeatable(ChaosNioChannelAcceptSuppress.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.SuppressTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NIO,
    operationType = OperationType.NIO_CHANNEL_ACCEPT)
public @interface ChaosNioChannelAcceptSuppress {

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
   * @ChaosNioChannelAcceptSuppress(id = "primary",  probability = 0.001)
   * @ChaosNioChannelAcceptSuppress(id = "replica",  probability = 0.01)
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
    ChaosNioChannelAcceptSuppress[] value();
  }
}
