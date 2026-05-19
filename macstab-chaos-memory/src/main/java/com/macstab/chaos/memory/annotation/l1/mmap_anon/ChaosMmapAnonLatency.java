/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_anon;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryLatencyBinding;
import com.macstab.chaos.memory.model.MemorySelector;

/**
 * L1 chaos primitive: delay every libchaos-memory-intercepted {@code mmap(MAP_ANONYMOUS)} call by
 * {@link #delayMs} milliseconds before delegating to libc.
 *
 * <p><strong>What this simulates:</strong> the VM-syscall latency increase that surfaces under
 * memory-pressure stall events, transparent-hugepage compaction storms, and NUMA balancing
 * passes — none of which fail with an errno but all of which add wall-clock cost to
 * allocations and stress timeouts in the application.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapAnonLatency(delayMs = 50)
 * class MyTest { ... }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> {@code 10}-{@code 100} ms mirrors realistic stall events;
 * {@code > 1000} ms typically saturates connection-pool timeouts and produces noisy failures.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryLatencyBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#latency(MemorySelector, java.time.Duration)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryLatencyTranslator")
@MemoryLatencyBinding(selector = MemorySelector.MMAP_ANON)
public @interface ChaosMmapAnonLatency {

  /**
   * @return latency to apply on every match, in milliseconds (must be non-negative)
   */
  long delayMs() default 50L;

  /**
   * @return container id to bind to ({@code ""} = every matching container in the test class)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-memory
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
