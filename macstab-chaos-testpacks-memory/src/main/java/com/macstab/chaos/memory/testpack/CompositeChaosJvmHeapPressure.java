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
 * <p>Simulates JVM heap expansion failure by injecting {@code ENOMEM} on {@code mmap} calls at 10%
 * probability. The JVM's garbage collector expands the heap by calling {@code mmap(MAP_ANONYMOUS)}
 * to commit new pages; when this call fails the JVM must either trigger a full GC, shrink survivor
 * regions, or throw {@code java.lang.OutOfMemoryError: Java heap space}. This scenario exercises
 * the code path that connects the OS-level {@code ENOMEM} to the JVM's heap-expansion error handler
 * — a path that is never hit in normal load tests because heap expansion always succeeds on
 * well-provisioned CI machines.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#failHeapAllocation(container, 0.1)} via libchaos-memory,
 * which installs a {@code mmap/anon:ERRNO:ENOMEM@0.1} rule. Anonymous mmap is the pathway used by
 * both glibc {@code malloc()} for allocations above {@code MMAP_THRESHOLD} (default 128 KiB) and
 * musl's mallocng for all sizes. In production JVM heap expansion failures arise from cgroup {@code
 * memory.max} limits being reached, from JVM {@code -Xmx} exhaustion, or from physical memory
 * pressure on the host.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * At 10% a healthy JVM will handle the failures via GC pressure but remain functional. Applications
 * that allocate large objects frequently will see elevated GC pause times and may throw {@code
 * OutOfMemoryError} on individual requests. Services with no {@code OutOfMemoryError} handling will
 * propagate the error to callers as 500s. At sustained 10% the service degrades noticeably but
 * typically recovers without a restart if load is reduced.
 *
 * <h2>Industry references</h2>
 *
 * <p>JVM heap expansion via anonymous mmap is documented in HotSpot internals ({@code
 * src/hotspot/os/linux/os_linux.cpp}, {@code pd_commit_memory}). The connection between {@code
 * ENOMEM} and {@code OutOfMemoryError} is described in JEP 387 (Elastic Metaspace) and in the JVM
 * Specification (chapter 6, memory areas). cgroups v2 memory accounting that causes heap expansion
 * failure is covered in the Kubernetes memory-resource documentation and in the systemd cgroup
 * integration guide.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosJvmHeapPressure
 * class HeapExpansionTest {
 *
 *   @Test
 *   void serviceHandlesOutOfMemoryErrorGracefully(GenericContainer<?> javaApp) {
 *     // Each request must return a response — either success or a well-formed 503
 *     final Response r = client.largeRequest();
 *     assertThat(r.status()).isIn(200, 503);
 *     assertThat(r.body()).isNotEmpty();
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosJvmHeapPressure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.JvmHeapPressureComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosJvmHeapPressure {

  /**
   * Probability that any intercepted anonymous {@code mmap} call returns {@code ENOMEM}. Must be in
   * {@code (0.0, 1.0]}. Default {@code 0.1} (10%) — moderate pressure that exercises GC and heap
   * error-handling paths without immediately crashing the JVM.
   */
  double toxicity() default 0.1;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosJvmHeapPressure[] value();
  }
}
