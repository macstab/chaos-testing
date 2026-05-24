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
 * After {@link #successesBeforeFailure} successful {@code execveat} calls, injects {@code EACCES}
 * on every subsequent call, causing the calling code to observe a permission-denied failure that
 * persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code EACCES}, effect
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
 *       sets {@code errno = EACCES} and returns {@code -1} without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 13, {@code strerror}:
 *       "Permission denied"; the {@code dirfd} must be closed by the caller to avoid an fd leak
 *       since no close-on-exec processing occurs for failed execs.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = EACCES}; assert that the counter threshold
 *       corresponds to the expected number of exec calls before an LSM policy change or credential
 *       rotation takes effect in the application's lifecycle.
 *   <li>Container runtimes using {@code execveat} must close the open {@code dirfd} in the {@code
 *       EACCES} error path — assert that each failure closes the dirfd before propagating the
 *       error, since failed execs do not trigger {@code FD_CLOEXEC} processing on the dirfd.
 *   <li>Assert that the application distinguishes a post-N EACCES from EPERM — EACCES (13) means
 *       the process's credentials or the file's permissions denied exec, suggesting a configuration
 *       or deployment fix; EPERM (1) means an operation-class denial requiring a capability change;
 *       these require different runbook actions and the diagnostic must identify which occurred.
 * </ul>
 *
 * Production failure mode: a container runtime uses {@code execveat} to launch workloads; an LSM
 * policy update ({@code auditd} policy reload, AppArmor profile revision) denies the exec type
 * after the first N launches succeed; subsequent exec calls return {@code EACCES}; the runtime
 * leaks the open dirfd on each failure, progressively exhausting {@code RLIMIT_NOFILE}.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER is the appropriate model for LSM policy change scenarios. Real {@code EACCES} from
 * {@code execveat} is not random — it occurs when a policy change takes effect while the runtime is
 * mid-operation. The first N execs proceed before the policy update; then the LSM hook ({@code
 * security_bprm_check} in the kernel) begins denying subsequent exec attempts. FAIL_AFTER with
 * threshold N reproduces this state-machine transition: N successes followed by permanent failure
 * captures what probabilistic ERRNO cannot — the fact that real policy denials are sticky.
 *
 * <p>The {@code execveat} context has a notable difference from {@code execve}: the LSM hook is
 * applied to the already-open file description pointed to by {@code dirfd}, not to a fresh path
 * lookup. This means an LSM policy label that was applied to the file after the dirfd was opened
 * may differ from what a fresh {@code execve} path lookup would encounter. FAIL_AFTER tests this
 * scenario where the first N dirfds are opened before the label changes, and subsequent opens pick
 * up the new label that causes {@code EACCES} from the LSM check.
 *
 * <p>A {@code noexec} mount flag on the filesystem backing the {@code dirfd} is another source of
 * sticky {@code EACCES}: if the mount is remounted with {@code noexec} after the process opens
 * existing dirfds (which remain valid), any new dirfd opened after the remount and passed to {@code
 * execveat} will trigger {@code EACCES}. FAIL_AFTER with threshold 0 tests this case where the
 * runtime must handle {@code EACCES} from the very first exec attempt.
 *
 * <p>The counter does not reset between test methods at class scope, enabling a test class to
 * exercise the pre-change phase in early methods and the post-change (denial) phase in later
 * methods without restarting the container. This matches the real production timeline: the policy
 * change happens once and all subsequent execs are denied.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEaccesFailAfter(successesBeforeFailure = 10)
 * class ExecveatLsmPolicyChangeTest {
 *   @Test
 *   void runtimeClosesDirfdAndReportsLsmDenialAfterThreshold(ConnectionInfo info) {
 *     // first 10 execveat calls succeed; subsequent calls return EACCES;
 *     // verify dirfd closed on each failure; EACCES reported as LSM policy denial
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to match the expected
 * number of successful execs before the policy change takes effect; values in the range 5–50 cover
 * most runtime lifecycle scenarios; 0 means the first exec fails (mount remounted noexec before any
 * launch).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveatEaccesFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.EACCES)
public @interface ChaosExecveatEaccesFailAfter {

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
   * @ChaosExecveatEaccesFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveatEaccesFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveatEaccesFailAfter[] value();
  }
}
