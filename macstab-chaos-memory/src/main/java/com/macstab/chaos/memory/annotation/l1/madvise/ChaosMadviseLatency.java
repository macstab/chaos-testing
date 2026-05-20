/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.madvise;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryLatencyBinding;
import com.macstab.chaos.memory.model.MemorySelector;

/**
 * Delays every libchaos-memory-intercepted {@code madvise} call by {@link #delayMs}
 * milliseconds before delegating to the real kernel call.
 *
 * <p><strong>What this annotation is:</strong> an L1 chaos primitive encoding exactly one
 * (selector = {@code MADVISE}, effect = LATENCY) pair. Unlike the errno variants, the
 * latency primitive always delegates to the kernel — it only adds wall-clock cost before doing so.
 *
 * <p><strong>What chaos this applies:</strong> every {@code madvise} call intercepted by
 * libchaos-memory blocks for {@link #delayMs} ms before the kernel call is issued. This simulates
 * the wall-clock cost increase that surfaces under memory-pressure stall events, transparent
 * hugepage compaction storms, and NUMA balancing passes — none of which return an errno but all of
 * which add latency to allocations and can exhaust application-level timeouts.
 *
 * <p><strong>How this occurs (mechanism):</strong> the
 * {@code @SyscallLevelChaos(LibchaosLib.MEMORY)} annotation causes {@code ChaosTestingExtension}
 * to upload {@code libchaos-memory.so} and prepend it to {@code LD_PRELOAD} before the container
 * starts. The shared library interposes the libc wrappers for the {@code MADVISE} syscall
 * family. This annotation installs a LATENCY rule via
 * {@code AdvancedMemoryChaos.apply(container, rule)} that configures the sleep duration.
 *
 * <p><strong>What is required:</strong>
 * <ul>
 *   <li><strong>Linux host</strong> — {@code LD_PRELOAD} does not apply on macOS or Windows.</li>
 *   <li><strong>{@code @SyscallLevelChaos(LibchaosLib.MEMORY)}</strong> on the container
 *       annotation — omitting it causes an {@code ExtensionConfigurationException} at
 *       {@code beforeAll}.</li>
 *   <li><strong>glibc-based container image</strong> — musl-based images may not honour
 *       {@code LD_PRELOAD} for statically-linked processes.</li>
 *   <li><strong>{@code macstab-chaos-memory} on the test classpath.</strong></li>
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseLatency(delayMs = 50)
 * class AllocationLatencyTest { ... }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> {@code 10}–{@code 100} ms mirrors realistic stall events;
 * values above {@code 1000} ms typically saturate connection-pool timeouts and produce noisy
 * cascading failures.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds to a single container; the default empty string
 * applies the rule to every capable container. Use the repeatable form ({@code @ChaosMadviseLatencys})
 * to set different delays on different containers simultaneously.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Repeatable(ChaosMadviseLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MADVISE)
public @interface ChaosMadviseLatency {

  /**
   * Latency to inject before every matching {@code madvise} call, in milliseconds.
   * Must be non-negative. Zero is valid but produces no observable effect.
   */
  long delayMs() default 50L;

  /**
   * Container id to bind this rule to. The default empty string {@code ""} applies the rule to
   * every memory-chaos-capable container in the test class. A non-empty id must match a container
   * annotation on the same test class, otherwise an {@code ExtensionConfigurationException} is
   * thrown at {@code beforeAll}.
   */
  String id() default "";

  /**
   * Policy applied when the active backend cannot honour the libchaos-memory requirement.
   * {@link OnMissingEnv#ERROR} fails the test class at {@code beforeAll};
   * {@link OnMissingEnv#ABORT} raises a {@code TestAbortedException} (YELLOW in CI).
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   * <pre>{@code
   * @ChaosMadviseLatency(id = "primary",  probability = 0.001)
   * @ChaosMadviseLatency(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD
  })
  @interface Repeatable {
    ChaosMadviseLatency[] value();
  }
}
