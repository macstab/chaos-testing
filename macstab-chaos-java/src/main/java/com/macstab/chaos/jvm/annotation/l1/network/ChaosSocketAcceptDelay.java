/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.network;

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
 * Intercepts {@code ServerSocket.accept()} and holds the calling thread for {@link #delayMs()}
 * milliseconds before the new inbound connection is dequeued from the kernel's TCP accept queue,
 * simulating a slow acceptor that causes the kernel backlog to fill and new connection attempts
 * to be dropped in blocking-socket server frameworks such as Tomcat's BIO connector.
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
 *   <li>Before every call to {@code java.net.ServerSocket#accept()} inside the target container's
 *       JVM, the chaos agent intercepts the calling thread.
 *   <li>The thread sleeps for a duration drawn uniformly from [{@link #delayMs()},
 *       {@link #maxDelayMs()}]; equal values produce a deterministic delay.
 *   <li>Control returns and the underlying {@code accept()} executes normally, blocking until a
 *       connection is available in the kernel accept queue and returning the new {@code Socket}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The accept loop is delayed; the kernel's TCP accept queue fills with completed handshakes
 *       that have not been dequeued; once full, the kernel drops SYN packets or sends RST;
 *       assert that connecting clients observe connection refused or connection timeout depending
 *       on the kernel's {@code tcp_abort_on_overflow} setting.
 *   <li>Tomcat BIO connector uses a dedicated acceptor thread that calls
 *       {@code serverSocket.accept()} in a loop; the delay here limits the maximum accept rate to
 *       approximately {@code 1000 / delayMs} connections per second; assert that Tomcat's
 *       connection count metric grows slower than the client connection rate during the fault.
 *   <li>The accept rate reduction may cause Tomcat's {@code maxConnections} to be reached faster
 *       under load if connections are accepted slowly but processed quickly; assert that Tomcat
 *       correctly applies back-pressure to the kernel rather than accepting beyond its configured
 *       limit.
 *   <li><strong>Production failure mode:</strong> a JVM full-GC pause stops all application
 *       threads, including the acceptor; the kernel's accept queue fills during the GC; after GC
 *       completes the acceptor drains the queue rapidly; clients that connected during the pause
 *       receive their first response after a delay equal to the GC duration plus processing time;
 *       clients with short timeouts see connection timeouts and generate spurious error alerts.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The interception targets {@code java.net.ServerSocket#accept()}, the blocking-socket server
 * API. Unlike {@link ChaosNioChannelAcceptDelay} which targets {@code ServerSocketChannel.accept()}
 * used by NIO-based frameworks (Netty, Undertow), this annotation targets the blocking API used
 * by Tomcat BIO (pre-NIO), embedded Zookeeper servers, and custom server socket implementations.
 *
 * <p>{@code ServerSocket.accept()} blocks in the OS {@code accept(2)} syscall until a connection
 * is available. The chaos delay fires before this syscall, blocking the calling thread in the JVM
 * rather than in the OS. This means the thread is not blocked in an interruptible system call —
 * {@code Thread.interrupt()} will interrupt the sleep but not the subsequent {@code accept()} call.
 * Applications that rely on thread interruption to stop the acceptor loop must handle this
 * correctly.
 *
 * <p>The kernel's listen backlog has two independent queues on Linux: the incomplete-handshake
 * SYN queue and the complete-handshake accept queue. {@code ServerSocket.accept()} drains the
 * accept queue. When the accept queue is full (controlled by {@code SO_BACKLOG} and
 * {@code /proc/sys/net/core/somaxconn}), new SYN packets are dropped if
 * {@code /proc/sys/net/ipv4/tcp_abort_on_overflow} is {@code 0}, or new handshakes are RST-ed if
 * it is {@code 1}.
 *
 * <p>The accept delay models the same OS behaviour as a GC pause: both cause the acceptor thread
 * to stop running, both cause the accept queue to fill, and both result in client-visible connection
 * drops when the queue overflows. The difference is that a chaos delay is deterministic and
 * controllable, while a GC pause is stochastic — making this annotation useful for ensuring the
 * application behaves correctly in the GC-pause scenario without needing to trigger an actual GC.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosSocketAcceptDelay(delayMs = 1000)
 * class TomcatBacklogOverflowTest {
 *   @Test
 *   void kernelBacklogFillsAndClientsTimeOut(ConnectionInfo info) {
 *     // assert that clients beyond backlog capacity receive connection refused
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
 * @see ChaosSocketAcceptSuppress
 * @see ChaosNioChannelAcceptDelay
 */
@Repeatable(ChaosSocketAcceptDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.NETWORK,
    operationType = OperationType.SOCKET_ACCEPT)
public @interface ChaosSocketAcceptDelay {

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
   * @ChaosSocketAcceptDelay(id = "primary",  probability = 0.001)
   * @ChaosSocketAcceptDelay(id = "replica",  probability = 0.01)
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
    ChaosSocketAcceptDelay[] value();
  }
}
