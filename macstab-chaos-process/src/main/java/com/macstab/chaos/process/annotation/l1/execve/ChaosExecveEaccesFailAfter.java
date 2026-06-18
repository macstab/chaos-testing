/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

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
 * Allows the first {@link #successesBeforeFailure} {@code execve} calls to succeed, then injects
 * {@code EACCES} on every subsequent call, simulating a permission-policy change that takes effect
 * after a bounded number of successful process image replacements.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code EACCES}, effect =
 * FAIL_AFTER) tuple. FAIL_AFTER is libchaos-process's counter-gated effect: the first {@link
 * #successesBeforeFailure} matched calls succeed normally; every call after that returns {@code -1}
 * with {@code errno = EACCES} until the rule is removed. This models scenarios where a security
 * policy change (LSM rule update, seccomp profile reload, capability drop) takes effect mid-run and
 * causes previously-permitted exec calls to fail with permission denied.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule atomic counter of successful {@code execve} calls.
 *   <li>Once the counter reaches {@link #successesBeforeFailure}, it trips and every subsequent
 *       intercepted call sets {@code errno = EACCES} and returns {@code -1} without executing.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 13, {@code strerror}:
 *       "Permission denied"; the counter remains tripped until rule removal.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} {@code execve} calls succeed; subsequent calls
 *       fail with {@code EACCES} — the application must detect the transition and disable or
 *       degrade the feature that requires child process spawning.
 *   <li>Applications that spawn helper processes as part of request handling must handle the
 *       mid-run permission revocation scenario: assert that in-flight requests that relied on
 *       successful exec before the threshold complete normally, while new requests after the
 *       threshold fail with a clear {@code EACCES} diagnostic rather than silently producing
 *       incorrect results.
 *   <li>Assert that the application does not cache a stale "exec is permitted" assumption after the
 *       fail-after threshold trips — each exec attempt should check the return value and not assume
 *       that because previous execs succeeded, future ones will too.
 * </ul>
 *
 * Production failure mode: a live LSM policy update (AppArmor profile reload, SELinux policy
 * update) takes effect on a running container after N successful exec calls; subsequent exec calls
 * fail with {@code EACCES}; the application continues processing requests that internally require
 * the helper process but silently skips the exec-dependent step, producing incorrect output that is
 * only detected hours later during auditing.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The FAIL_AFTER effect for {@code EACCES} is particularly well-suited to modelling LSM policy
 * changes that take effect mid-run: unlike resource exhaustion (which typically follows a
 * monotonically increasing pressure curve), permission changes are discrete events that flip the
 * permission state immediately. The N-success-then-fail pattern captures the window between the
 * policy update and the application detecting it — during which some exec calls succeed and
 * subsequent ones fail.
 *
 * <p>Unlike the probabilistic ERRNO effect (which fires randomly), FAIL_AFTER guarantees that the
 * transition happens at a predictable call count. This makes it suitable for testing state-machine
 * correctness in process managers: set the threshold to the exact number of worker spawns that
 * occur during a normal request cycle, then verify that the manager's state machine transitions
 * correctly from "healthy" to "degraded" when exec starts failing.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEaccesFailAfter(successesBeforeFailure = 5)
 * class PolicyRevocationTest {
 *   @Test
 *   void processManagerDegradesMidRunWhenExecveEaccesTriggers(ConnectionInfo info) {
 *     // verify manager detects permission change and signals degraded state to health check
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * {@code execve} calls that occur during the "healthy" phase of the test scenario; zero triggers
 * failure on the first exec (useful for startup-permission testing).
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveEaccesFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.EACCES)
public @interface ChaosExecveEaccesFailAfter {

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
   * @ChaosExecveEaccesFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveEaccesFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveEaccesFailAfter[] value();
  }
}
