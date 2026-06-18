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
 * <p>Causes {@code LockSupport.park()} and NIO {@code Selector.select()} calls to return spuriously
 * without being unparked or having events arrive, exposing code that does not re-check wait
 * conditions in a loop after waking up.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects a {@code spuriousWakeup} effect via the JVM chaos agent's NIO selector interceptor.
 * The JVM specification permits spurious wakeups from {@code Object.wait()}, {@code
 * LockSupport.park()}, and blocking I/O selectors; this scenario makes them happen at the
 * configured {@link #probability()} to surface code that relies on a single notification being
 * genuine. In production, spurious wakeups are rare OS events (Linux futex, macOS pthread) but they
 * are documented in the POSIX specification and must be handled.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * Code that does not guard waits in a loop will perform spurious work without consequence in most
 * cases, but in edge cases (e.g. queue consumers that do not re-check isEmpty()) it can dequeue
 * nothing and silently miss events.
 *
 * <h2>Industry references</h2>
 *
 * <p>POSIX.1-2017 §11.4.1 explicitly permits spurious wakeups. The Java Language Specification
 * §17.2.1 similarly permits them. Doug Lea's "The Art of Multiprocessor Programming" §8.3.3
 * discusses the correct pattern (always re-check the condition in a loop).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosSpuriousWakeup(probability = 0.2)
 * class SpuriousWakeupTest {
 *   @Test
 *   void queueConsumerHandlesSpuriousWakeup(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSpuriousWakeup.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.SpuriousWakeupComposer",
    severity = Severity.MILD)
public @interface CompositeChaosSpuriousWakeup {

  /**
   * Probability in {@code (0.0, 1.0]} that a park/select call returns spuriously.
   *
   * @return probability; default 0.2
   */
  double probability() default 0.2;

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
    CompositeChaosSpuriousWakeup[] value();
  }
}
