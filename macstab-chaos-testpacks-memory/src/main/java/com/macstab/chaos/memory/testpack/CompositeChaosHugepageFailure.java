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
 * <h2>What this is</h2>
 *
 * <p>Simulates transparent hugepage allocation advisory failure by injecting {@code ENOMEM} on
 * {@code madvise} calls at 30% probability. Applications and runtimes that call
 * {@code madvise(MADV_HUGEPAGE)} or {@code madvise(MADV_COLLAPSE)} to request 2 MiB transparent
 * hugepages (THP) will see the advisory fail when the kernel cannot find a contiguous 2 MiB physical
 * region. A correct application treats {@code madvise} failures as non-fatal hints and continues
 * with 4 KiB base pages; an incorrect one aborts, retries indefinitely, or silently skips the
 * fallback and operates without the performance benefit.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#failPagePurge(container, 0.3)} via libchaos-memory, which
 * installs a {@code madvise:ERRNO:ENOMEM@0.3} rule. ({@code ENOMEM} is the errno returned by the
 * kernel when the hugepage advisory cannot be satisfied — the same errno used for
 * {@code MADV_DONTNEED} / {@code MADV_FREE} failures.) In production hugepage failures arise from
 * memory fragmentation (common on long-running hosts), from kernel NUMA topology constraints, or
 * from {@code /sys/kernel/mm/transparent_hugepage/enabled} being set to {@code never} by a
 * platform policy.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * {@code madvise} is always a hint — the kernel is allowed to ignore it regardless. A well-written
 * application ignores a non-zero return and operates with base pages at reduced performance. The
 * practical risk is in applications that treat {@code madvise} failures as fatal configuration
 * errors, or in allocators (jemalloc, TCMalloc) that are not compiled with {@code madvise}-failure
 * tolerance. At 30% the service remains responsive but may exhibit elevated memory latency.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code madvise(2)} defines {@code ENOMEM} as the return when the kernel cannot commit to
 * fulfilling the advice. Transparent hugepage behaviour is documented in the Linux kernel admin
 * guide ({@code Documentation/admin-guide/mm/transhuge.rst}). TCMalloc and jemalloc hugepage
 * strategies are covered in their respective design documents. The interaction between {@code
 * MADV_HUGEPAGE} and memory fragmentation is a known operational issue documented in Red Hat and
 * Oracle performance tuning guides.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosHugepageFailure
 * class HugepageFallbackTest {
 *
 *   @Test
 *   void allocatorFallsBackToBasePagesOnHugepageFailure(GenericContainer<?> app) {
 *     // Service must remain fully functional — hugepage failure is non-fatal
 *     assertThat(client.ping()).isEqualTo("ok");
 *     assertThat(app.getLogs()).doesNotContain("fatal").doesNotContain("abort");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosHugepageFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.HugepageFailureComposer",
    severity = Severity.MILD)
public @interface CompositeChaosHugepageFailure {

  /**
   * Probability that any intercepted {@code madvise} call returns {@code ENOMEM}. Must be in
   * {@code (0.0, 1.0]}. Default {@code 0.3} (30%).
   */
  double toxicity() default 0.3;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosHugepageFailure[] value();
  }
}
