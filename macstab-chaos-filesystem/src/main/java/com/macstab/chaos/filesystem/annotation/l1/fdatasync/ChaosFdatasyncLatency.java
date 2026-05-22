/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.fdatasync;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.filesystem.annotation.l1.IoLatencyBinding;
import com.macstab.chaos.filesystem.model.IoOperation;

/**
 * Delays every libchaos-intercepted {@code fdatasync} call by {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making the operation succeed but take longer than expected.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive encoding exactly one
 * (selector, effect = LATENCY) pair. Unlike errno variants, the latency primitive always delegates
 * to the kernel — it only adds wall-clock cost before doing so.
 *
 * <p><strong>What chaos this applies:</strong> every {@code fdatasync} call intercepted by libchaos
 * blocks for {@link #delayMs} ms before the kernel call is issued. This simulates the wall-clock
 * cost increase from resource pressure, kernel scheduling stalls, or slow hardware — none of which
 * return an errno but all of which can exhaust application-level timeouts, saturate connection-pool
 * wait budgets, and surface hidden latency assumptions.
 *
 * <p><strong>How this occurs (mechanism):</strong> the {@code @SyscallLevelChaos(LibchaosLib.IO)}
 * annotation causes {@code ChaosTestingExtension} to upload {@code libchaos-io.so} and prepend it
 * to {@code LD_PRELOAD}. The shared library interposes the filesystem libc wrappers (open, read,
 * write, close, fsync, etc.) at the dynamic-linker level. This annotation installs a rule via
 * {@code AdvancedFilesystemChaos.apply(container, rule)}.
 *
 * <p><strong>What is required:</strong>
 *
 * <ul>
 *   <li><strong>Linux host</strong> — {@code LD_PRELOAD} does not apply on macOS or Windows.
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.IO)}</strong> on the container annotation
 *       (e.g. {@code @AppContainer}) — omitting it causes an {@code
 *       ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>glibc-based container image</strong> — musl-based images may not honour {@code
 *       LD_PRELOAD} for statically-linked processes.
 *   <li><strong>{@code macstab-chaos-filesystem} on the test classpath.</strong>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @ChaosFdatasyncLatency(delayMs = 200)
 * class LatencyTest {
 *   @Test
 *   void appHandlesSlowOperation(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> {@code 10}–{@code 200} ms simulates realistic stall events;
 * values above application-level timeouts produce cascading failures rather than isolated latency
 * observations — intentional in some scenarios, noisy in others.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds to a single container; the default empty string
 * applies to every capable container. Use the repeatable form ({@code @ChaosFdatasyncLatencys}) to
 * set different delays on different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosFdatasyncLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.filesystem.annotation.l1.translators.IoLatencyTranslator")
@IoLatencyBinding(operation = IoOperation.FDATASYNC)
public @interface ChaosFdatasyncLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 50L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-io
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosFdatasyncLatency(id = "primary",  probability = 0.001)
   * @ChaosFdatasyncLatency(id = "replica",  probability = 0.01)
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
    ChaosFdatasyncLatency[] value();
  }
}
