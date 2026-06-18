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
 * {@code ENFILE} on every subsequent call, simulating system-wide file-table exhaustion that causes
 * exec failures after a bounded number of successful process image replacements.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code ENFILE}, effect =
 * FAIL_AFTER) tuple. FAIL_AFTER is libchaos-process's counter-gated effect: the first {@link
 * #successesBeforeFailure} matched calls succeed normally; every call after that returns {@code -1}
 * with {@code errno = ENFILE} until the rule is removed. This models progressive system-wide file
 * handle exhaustion where the aggregate file usage across all processes on the node reaches {@code
 * fs.file-max}, preventing any new file opens including the binary open required for exec.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule atomic counter of successful {@code execve} calls.
 *   <li>Once the counter reaches {@link #successesBeforeFailure}, it trips and every subsequent
 *       intercepted call sets {@code errno = ENFILE} and returns {@code -1} without executing.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 23, {@code strerror}: "Too many
 *       open files in system"; the counter remains tripped until removal.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} {@code execve} calls succeed; subsequent calls
 *       fail with {@code ENFILE} — the application must treat this as a platform-capacity event and
 *       surface an alert for operator intervention, not as an in-process fd-leak or a missing
 *       binary.
 *   <li>Unlike {@code EMFILE} (fixable by closing leaked fds in-process), {@code ENFILE} cannot be
 *       resolved by the application alone — assert that the application does not attempt to close
 *       its own fds in response to {@code ENFILE} from exec, and instead surfaces a
 *       platform-capacity alert with the system-wide fd count ({@code /proc/sys/fs/file-nr}).
 *   <li>Assert that the application correctly implements backpressure when exec-ENFILE persists
 *       across retries: it should stop accepting new requests that require subprocess spawning
 *       rather than accumulating a queue of spawn attempts that will all fail.
 * </ul>
 *
 * Production failure mode: a high-density Kubernetes node runs many containers with aggregate file
 * handle usage approaching {@code fs.file-max}; a burst of concurrent exec calls from multiple
 * containers simultaneously pushes the node over the system-wide limit; all subsequent exec calls
 * on the node fail with {@code ENFILE} regardless of which container makes them — a node-wide
 * cascading failure triggered by the aggregate file handle usage.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The FAIL_AFTER model for exec-ENFILE captures the progressive system-wide exhaustion curve: as
 * aggregate file usage grows, each exec that opens the binary contributes to the global count;
 * eventually the N-th exec is the one that pushes the system over the limit, and all subsequent
 * execs fail. The deterministic threshold makes this boundary reproducible in tests.
 *
 * <p>The operational distinction between EMFILE and ENFILE is critical for runbooks: the
 * application's diagnostic message must name the specific errno and recommend different actions.
 * For EMFILE: "check this process's fd count and look for leaks." For ENFILE: "check the node's
 * system-wide file handle count with {@code cat /proc/sys/fs/file-nr} and alert the platform team
 * to raise {@code fs.file-max}." Applications that report both as "too many open files" without
 * distinguishing which limit was hit make operator response significantly harder.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEnfileFailAfter(successesBeforeFailure = 50)
 * class SystemFdExhaustionTest {
 *   @Test
 *   void applicationDistinguishesEnfileFromEmfileAndAlertsPlatformTeam(ConnectionInfo info) {
 *     // verify ENFILE triggers platform-capacity alert; application does not try to close own fds
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of exec
 * calls the application makes during the normal request cycle before system-wide exhaustion would
 * occur; the value depends on the node's {@code fs.file-max} and aggregate file usage.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveEnfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.ENFILE)
public @interface ChaosExecveEnfileFailAfter {

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
   * @ChaosExecveEnfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveEnfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveEnfileFailAfter[] value();
  }
}
