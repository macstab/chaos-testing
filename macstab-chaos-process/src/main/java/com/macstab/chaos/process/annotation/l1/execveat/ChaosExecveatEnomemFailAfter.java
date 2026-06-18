/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execveat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * After {@link #successesBeforeFailure} successful {@code execveat} calls, injects {@code ENOMEM}
 * on every subsequent call, causing the calling code to observe an out-of-memory failure that
 * persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code ENOMEM}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call fails until the rule is removed. This is
 * distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional delay).
 * Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter. Each {@code execveat} call that passes
 *       the counter check decrements the remaining budget; the counter does not reset automatically
 *       between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code execveat} call
 *       sets {@code errno = ENOMEM} and returns {@code -1} without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12, {@code strerror}: "Out of
 *       memory"; the calling process remains in its current state and the {@code dirfd} must be
 *       closed explicitly since no close-on-exec processing occurs for failed execs; in fork+exec
 *       patterns the child must call {@code _exit()} to avoid zombies.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = ENOMEM}; assert that the application closes the
 *       {@code dirfd} on every failure and reports a memory-pressure diagnostic rather than
 *       treating the failure as a permanent binary-unavailability error.
 *   <li>In fork+exec patterns using {@code execveat}, the child process that receives
 *       post-threshold {@code ENOMEM} must call {@code _exit()} (not {@code exit()}) so that the
 *       parent can detect the exec failure via {@code waitpid} without a zombie accumulating for
 *       each failed launch — assert that the runtime's fork+exec child exits cleanly with a
 *       specific exit code that the parent maps to an ENOMEM error.
 *   <li>Assert that the runtime implements exponential backoff for subsequent exec attempts after
 *       the counter trips — ENOMEM under memory pressure is often transient, and tight retry loops
 *       under OOM conditions worsen the memory situation by repeatedly allocating argv/envp copies
 *       for each exec attempt before the kernel rejects the call.
 * </ul>
 *
 * Production failure mode: a container runtime uses {@code execveat(AT_EMPTY_PATH)} to launch
 * containerised workloads; the Kubernetes node is under progressive memory pressure from
 * OOM-protected workloads whose actual RSS exceeds their requests; after N successful launches the
 * exec's internal kernel memory allocations fail; the runtime's fork+exec children do not call
 * {@code _exit()} on exec failure, producing zombie processes that consume pid table space and
 * worsening the OOM condition.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER captures the progressive memory exhaustion curve more accurately than probabilistic
 * ERRNO. Real {@code ENOMEM} from {@code execveat} follows a threshold pattern: the node has
 * sufficient memory for the first N exec calls (each of which allocates kernel data structures for
 * the new process image); once the node's free memory falls below the allocation requirement the
 * exec fails and continues to fail until memory is reclaimed. Setting {@link
 * #successesBeforeFailure} to the observed exec count before the node crosses the exhaustion
 * threshold reproduces this deterministic behaviour.
 *
 * <p>The {@code execveat} + FAIL_AFTER combination creates a compounding dirfd leak risk not
 * present in the ERRNO variant: under FAIL_AFTER, each of the first N successful execs closes the
 * dirfd via {@code FD_CLOEXEC} on exec success. After the counter trips, every subsequent call
 * opens a fresh dirfd, then receives {@code ENOMEM} from the interposer. The dirfd is now open but
 * the exec has not proceeded — the application must close it explicitly. Applications that omit the
 * explicit close accumulate one leaked fd per failed exec attempt under memory pressure,
 * progressively adding to the system-wide fd count.
 *
 * <p>The fork+exec zombie risk is amplified under FAIL_AFTER because all fork+exec calls after the
 * counter trips will fail in the child. In the probabilistic ERRNO variant, only a fraction of
 * child processes fail and each failure is independent; under FAIL_AFTER, every fork+exec child
 * after the threshold fails in the exec and must call {@code _exit()}. Applications that use {@code
 * exit()} rather than {@code _exit()} in the failed-exec child run C++ destructors and flush stdio
 * buffers inherited from the parent, which can corrupt the parent's shared state and is undefined
 * behaviour.
 *
 * <p>The counter is scoped to the container lifecycle and does not reset between test methods at
 * class scope. This allows a test class to verify the success phase in early methods and the
 * failure-with-backoff phase in later methods without redeploying the container — the same
 * progression a production node experiences during a memory exhaustion incident.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEnomemFailAfter(successesBeforeFailure = 30)
 * class ExecveatMemoryExhaustionTest {
 *   @Test
 *   void runtimeClosesDirfdAndExitsCleanlyAfterEnomemThreshold(ConnectionInfo info) {
 *     // first 30 execveat calls succeed; subsequent calls return ENOMEM;
 *     // verify dirfd closed on each failure; no zombie processes; backoff retry implemented
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the expected
 * number of successful exec calls before the memory exhaustion threshold is crossed; values in the
 * range 10–100 cover most container-density scenarios; 0 exercises the cold-start OOM path where
 * memory is exhausted before the first launch succeeds.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveatEnomemFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.ENOMEM)
public @interface ChaosExecveatEnomemFailAfter {

  /**
   * @return number of matched calls allowed to succeed before failure begins ({@code >= 0})
   */
  long successesBeforeFailure() default 0L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-process
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosExecveatEnomemFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveatEnomemFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveatEnomemFailAfter[] value();
  }
}
