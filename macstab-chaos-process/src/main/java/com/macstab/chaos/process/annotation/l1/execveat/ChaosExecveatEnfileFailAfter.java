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
 * After {@link #successesBeforeFailure} successful {@code execveat} calls, injects {@code ENFILE}
 * on every subsequent call, causing the calling code to observe a system-wide file-table exhaustion
 * failure that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code ENFILE}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call fails until the rule is removed. This is
 * distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional delay).
 * Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter. Each {@code execveat} call that
 *       passes the counter check decrements the remaining budget; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code execveat} call
 *       sets {@code errno = ENFILE} and returns {@code -1} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 23,
 *       {@code strerror}: "Too many open files in system"; the kernel's global file table
 *       ({@code fs.file-max}) is exhausted; no process on the system can open any new file;
 *       the {@code dirfd} must be closed explicitly since no close-on-exec processing occurs
 *       for failed execs; closing in-process fds will not resolve the condition.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = ENFILE}; assert that the application surfaces a
 *       platform-capacity alert rather than attempting in-process remediation — closing this
 *       process's fds will not restore capacity when the system-wide kernel table is exhausted.</li>
 *   <li>Container runtimes using {@code execveat} with {@code AT_EMPTY_PATH} must close the open
 *       {@code dirfd} on each {@code ENFILE} failure — assert that each failure closes the dirfd
 *       before propagating the error; failing to close the dirfd after ENFILE worsens the
 *       system-wide condition and makes the next exec attempt more likely to fail.</li>
 *   <li>Assert that the application distinguishes {@code ENFILE} (23, system-wide, requires
 *       platform escalation to raise {@code fs.file-max} or reduce aggregate fd usage) from
 *       {@code EMFILE} (24, per-process, fixable by closing leaked fds in this process) — the
 *       diagnostic must route to the correct runbook and must not instruct the operator to close
 *       application fds when the problem is system-wide.</li>
 * </ul>
 * Production failure mode: a high-density Kubernetes node runs container runtimes that each open
 * a dirfd to the container's rootfs overlay for {@code execveat(AT_EMPTY_PATH)} exec; each failed
 * launch leaks its dirfd due to missing close-on-error logic; the cumulative fd usage across all
 * runtimes approaches {@code fs.file-max}; after N successful launches the node crosses the limit;
 * all subsequent exec calls fail with {@code ENFILE}; each failure leaks another dirfd, making
 * every subsequent launch more likely to fail — a compounding node-level cascade.
 *
 * <h2>Deep technical dive</h2>
 * <p>FAIL_AFTER is the correct model for {@code ENFILE} in container-density scenarios. Real
 * system-wide fd exhaustion follows a capacity curve: aggregate fd usage across all runtimes on
 * the node rises with each container launch; after N launches the cumulative usage crosses
 * {@code fs.file-max}; all subsequent launches fail. FAIL_AFTER with threshold N reproduces this
 * deterministic crossing without probabilistic noise, allowing the test to verify the exact
 * boundary behaviour rather than relying on statistical coverage.
 *
 * <p>The compounding failure dynamic is unique to {@code execveat}: the {@code AT_EMPTY_PATH}
 * pattern requires opening a dirfd before calling exec. Under FAIL_AFTER, the first N execs
 * succeed and the dirfd is closed by the kernel (via {@code FD_CLOEXEC}). After the counter
 * trips, every subsequent exec attempt opens a dirfd (consuming one slot from the system-wide
 * table), then receives {@code ENFILE} from the interposer. If the application does not close
 * the dirfd in the error path, each failed launch contributes one more fd to the system-wide
 * table — the failing launches become the cause of the next launch's failure, creating a
 * self-reinforcing cascade that plain ERRNO testing cannot reveal.
 *
 * <p>The operational distinction from {@code EMFILE} is critical: {@code EMFILE} means this
 * process's fd table is full (fixable in-process by closing leaked fds); {@code ENFILE} means
 * the system-wide kernel file table is exhausted (requires the platform team to raise
 * {@code fs.file-max} or reduce aggregate fd usage across all processes on the node). Applications
 * that treat {@code ENFILE} as {@code EMFILE} will instruct operators to close application fds —
 * an action that does not resolve the system-wide condition and wastes incident response time.
 *
 * <p>The counter does not reset between test methods at class scope, mirroring the production
 * timeline: the node's fd table fills over time and the failure persists until operator
 * intervention. A test class can verify the pre-saturation phase (early methods) and the
 * post-saturation phase (later methods) without redeploying the container.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEnfileFailAfter(successesBeforeFailure = 50)
 * class ExecveatNodeFdSaturationTest {
 *   @Test
 *   void runtimeClosesDirfdAndEscalatesToPlatformAlertOnEnfileAfterThreshold(ConnectionInfo info) {
 *     // first 50 execveat calls succeed; subsequent calls return ENFILE;
 *     // verify dirfd closed on each failure; platform alert raised; no in-process fd-close fix
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the observed
 * number of container launches before the node's fd table reaches {@code fs.file-max}; for
 * test purposes, values in the range 10–200 exercise the saturation pattern efficiently;
 * 0 means the system-wide table is exhausted from the first exec attempt (tests cold-start
 * node saturation).
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveatEnfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.ENFILE)
public @interface ChaosExecveatEnfileFailAfter {

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
   * @ChaosExecveatEnfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveatEnfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveatEnfileFailAfter[] value();
  }
}
