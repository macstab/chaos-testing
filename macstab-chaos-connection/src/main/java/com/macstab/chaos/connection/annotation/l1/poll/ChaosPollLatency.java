/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.poll;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Delays every libchaos-intercepted {@code poll} call by {@link #delayMs}
 * milliseconds before delegating to the real kernel call, making the operation succeed but
 * take longer than expected.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive encoding exactly one
 * (selector, effect = LATENCY) pair. Unlike errno variants, the latency primitive always
 * delegates to the kernel — it only adds wall-clock cost before doing so.
 *
 * <p><strong>What chaos this applies:</strong> every {@code poll} call intercepted
 * by libchaos blocks for {@link #delayMs} ms before the kernel call is issued. This
 * simulates the wall-clock cost increase from resource pressure, kernel scheduling stalls, or
 * slow hardware — none of which return an errno but all of which can exhaust application-level
 * timeouts, saturate connection-pool wait budgets, and surface hidden latency assumptions.
 *
 * <p><strong>How this occurs (mechanism):</strong> the {@code @SyscallLevelChaos(LibchaosLib.NET)} annotation causes {@code ChaosTestingExtension} to upload {@code libchaos-net.so} and prepend it to {@code LD_PRELOAD}. The shared library interposes socket-layer libc wrappers (connect, accept, socket, bind, listen, shutdown, send, recv, poll). This annotation installs a rule via {@code AdvancedConnectionChaos.apply(container, rule)}.
 *
 * <p><strong>What is required:</strong>
 * <ul>
 *   <li><strong>Linux host</strong> — {@code LD_PRELOAD} does not apply on macOS or Windows.</li>
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.NET)}</strong> on the container annotation
 *       (e.g. {@code @RedisStandalone}) — omitting it causes an
 *       {@code ExtensionConfigurationException} at {@code beforeAll}.</li>
 *   <li><strong>glibc-based container image</strong> — musl-based images may not honour
 *       {@code LD_PRELOAD} for statically-linked processes.</li>
 *   <li><strong>{@code macstab-chaos-connection} on the test classpath.</strong></li>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosPollLatency(delayMs = 200)
 * class LatencyTest {
 *   @Test
 *   void appHandlesSlowOperation(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> {@code 10}–{@code 200} ms simulates realistic stall
 * events; values above application-level timeouts produce cascading failures rather than isolated
 * latency observations — intentional in some scenarios, noisy in others.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds to a single container; the default empty string
 * applies to every capable container. Use the repeatable form ({@code @ChaosPollLatencys}) to set
 * different delays on different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosPollLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.POLL)
public @interface ChaosPollLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

  /**
   * @return probability the latency fires when matched, in {@code (0.0, 1.0]}
   */
  double toxicity() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-net
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   * <pre>{@code
   * @ChaosPollLatency(id = "primary",  probability = 0.001)
   * @ChaosPollLatency(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD
  })
  @interface Repeatable {
    ChaosPollLatency[] value();
  }
}
