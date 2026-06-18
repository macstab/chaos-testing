/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.testpack;

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
 * <p>Simulates an OOM-kill event by injecting {@code ENOMEM} at 100% probability on every
 * interposed VM syscall — {@code mmap}, {@code mprotect}, and {@code madvise} — that
 * libchaos-memory hooks. This approximates the regime a process enters immediately before the Linux
 * OOM killer fires: every memory-management call fails, making it impossible to allocate new heap,
 * create threads, map files, or adjust page permissions.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#simulateOomKiller(container, 1.0)} via libchaos-memory,
 * which injects a wildcard-selector {@code ENOMEM} rule at probability {@code 1.0}. In production
 * the OOM kill path is triggered when the kernel finds no reclaimable memory pages and selects a
 * victim process via the OOM score heuristic (see {@code /proc/<pid>/oom_score_adj}).
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * At 100% toxicity every allocation attempt fails instantly. Processes that survive OOM-kill only
 * do so by catching {@code std::bad_alloc}, returning {@code NULL}-check errors up the call stack,
 * or relying on a watchdog restart. Services with no {@code ENOMEM} handling will crash, corrupt
 * shared state, or deadlock. Immediate operator intervention required.
 *
 * <h2>Industry references</h2>
 *
 * <p>Linux OOM killer behaviour is described in {@code mm/oom_kill.c} and the Linux man-page for
 * {@code proc(5)}. The POSIX specification for {@code mmap(2)} mandates {@code ENOMEM} when the
 * virtual address space is exhausted. Best-practice OOM resilience patterns are covered in
 * Netflix's chaos engineering reports (2012–2016) and in the Kubernetes resource-management
 * documentation for {@code LimitRange} / QoS classes.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosOomKill
 * class OomResilienceTest {
 *
 *   @ServiceHealthCheck
 *   void serviceDoesNotCorruptDataUnderOom(MemoryChaos chaos, GenericContainer<?> app) {
 *     // The framework has already applied the OOM-kill scenario;
 *     // verify that the service responds with an error rather than corrupting data.
 *     assertThat(client.ping()).isEqualTo("error");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosOomKill.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.OomKillComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosOomKill {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code ENOMEM} fires on each interposed VM syscall
   * ({@code mmap}, {@code mprotect}, {@code madvise}). Defaults to {@code 1.0} (deterministic
   * OOM-kill — every allocation attempt fails immediately). Lower values model a severe but not
   * total memory-pressure event; use {@code 0.05}–{@code 0.2} for sustained soak tests.
   */
  double toxicity() default 1.0;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosOomKill[] value();
  }
}
