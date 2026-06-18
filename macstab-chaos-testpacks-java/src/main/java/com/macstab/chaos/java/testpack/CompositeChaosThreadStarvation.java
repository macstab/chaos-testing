/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Leaks platform threads by spawning {@link #exhaustAfter()} daemon threads that never
 * terminate, consuming OS-level thread slots and inflating the JVM's thread count until the
 * application's own thread-pool allocation fails or performance degrades.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code THREAD_LEAK} stressor scenario via the JVM chaos agent. The agent creates the
 * requested number of daemon threads that park indefinitely. In production, thread leaks arise from
 * thread pools that are never shut down (e.g. a request-scoped executor created but not closed), or
 * from frameworks that spawn per-connection threads without a lifecycle bound.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Once the OS thread limit is approached, new thread-creation calls throw {@code OutOfMemoryError:
 * unable to create new native thread}. The application's thread pools stop accepting work, health
 * checks time out, and operator intervention (restart) is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>OS-level thread limits ({@code /proc/sys/kernel/threads-max} on Linux) and JVM thread creation
 * failure are documented in the HotSpot internals. Netflix Engineering blog "Thread Starvation in
 * Production" describes real incidents caused by leaked threads in HTTP connection handling.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosThreadStarvation(exhaustAfter = 10)
 * class ThreadStarvationTest {
 *   @Test
 *   void applicationRejectsRequestsOnThreadExhaustion(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosThreadStarvation.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ThreadStarvationComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosThreadStarvation {

  /**
   * Number of threads to leak (never-terminating daemon threads).
   *
   * @return thread count; default 10
   */
  int exhaustAfter() default 10;

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosThreadStarvation[] value();
  }
}
