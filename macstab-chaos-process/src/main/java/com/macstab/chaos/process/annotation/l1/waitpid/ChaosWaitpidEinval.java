/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.waitpid;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Injects {@code EINVAL} into {@code waitpid} calls intercepted by libchaos-process, causing the
 * calling code to observe an invalid-argument failure when the options bitmask passed to waitpid
 * contains an unrecognised flag.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WAITPID}, errno = {@code EINVAL}) tuple.
 * The {@code WAITPID} selector intercepts {@code waitpid} calls only, leaving {@code fork},
 * {@code posix_spawn}, and all other process syscalls unaffected. Compile-time safety: invalid
 * selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code waitpid} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code waitpid} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.</li>
 *   <li>When the trial fires, the interposer returns {@code -1} and sets {@code errno = EINVAL}
 *       without issuing the real kernel call.</li>
 *   <li>The calling code receives: return value {@code -1}, {@code errno = EINVAL} (22),
 *       {@code strerror(EINVAL)}: "Invalid argument"; the {@code options} argument contains an
 *       invalid or unsupported flag combination; no child state change is returned.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code waitpid} returns {@code -1} with {@code errno == EINVAL}; assert that the
 *       application treats EINVAL as a non-retryable programming error — the options bitmask
 *       contains an invalid flag that must be corrected in code, not retried.</li>
 *   <li>Applications that build waitpid options flags dynamically (from feature detection or
 *       configuration) must validate the resulting bitmask — assert that EINVAL from waitpid
 *       includes the options value in the diagnostic to enable rapid identification of the
 *       invalid flag.</li>
 *   <li>Assert that the application distinguishes waitpid-EINVAL (programming error, options flag
 *       invalid) from waitpid-EINTR (signal interrupt, retry required) and from waitpid-ECHILD
 *       (no child, expected loop termination) — the three error codes require completely different
 *       responses and are adjacent in numeric value ({@code EINVAL=22}, {@code ECHILD=10},
 *       {@code EINTR=4}).</li>
 * </ul>
 * Production failure mode: a process supervisor calls waitpid with an options mask built from
 * feature-detected flags; a kernel upgrade removes support for a non-standard extension flag;
 * waitpid returns EINVAL; the supervisor retries in a loop treating EINVAL as a transient
 * error, never reaping the child, which accumulates as a zombie; the supervisor's retry loop
 * does not log the options value, making root cause analysis require kernel changelog review.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EINVAL} from {@code waitpid} is returned when the {@code options} argument contains
 * bits that are not recognised by the kernel. Valid options on Linux are: {@code WNOHANG} (1),
 * {@code WUNTRACED} (2), {@code WCONTINUED} (8), and for waitid-style calls: {@code WSTOPPED} (2),
 * {@code WEXITED} (4), {@code WNOWAIT} (0x1000000). Setting any other bit produces EINVAL.
 * Platform-specific extension flags that exist on one kernel version may be removed or renamed
 * in another, making dynamic flag construction fragile.
 *
 * <p>waitpid returns -1 on error and sets errno. Code that tests
 * {@code if (ret == -1 && errno == EINVAL)} is correct and must not retry. The distinction between
 * EINVAL and ECHILD matters: ECHILD (-1, errno=10) is expected in reap loops while EINVAL
 * (-1, errno=22) is never expected in correct code. An error handler that dispatches only on the
 * -1 return value without checking errno will silently conflate all three error conditions.
 *
 * <p>EINVAL from waitpid does not affect the child process — the child continues running (or
 * remains a zombie) unchanged. The failed waitpid call has no side effects: no child state is
 * consumed, no zombie is removed. The application must correct the options flag before the next
 * waitpid call, not the pid argument.
 *
 * <p>A subtle source of EINVAL: calling {@code waitpid} with {@code options = 0} on a stopped
 * child without {@code WUNTRACED} will not return EINVAL — it will simply block until the child
 * exits. EINVAL fires only on genuinely invalid bit patterns, not on valid flags that happen to
 * not match the current child state.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWaitpidEinval(probability = 0.01)
 * class WaitpidInvalidOptionsTest {
 *   @Test
 *   void processManagerLogsOptionsMaskOnEinvalAndDoesNotRetry(ConnectionInfo info) {
 *     // verify EINVAL treated as non-retryable; options bitmask logged; EINVAL vs EINTR vs ECHILD
 *     // distinguished; child not orphaned; alert escalated
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EINVAL from waitpid is a programming
 * error; any non-zero probability exercises the non-retryable options-validation diagnostic path.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWaitpidEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WAITPID, errno = ProcessErrno.EINVAL)
public @interface ChaosWaitpidEinval {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

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
   * @ChaosWaitpidEinval(id = "primary",  probability = 0.001)
   * @ChaosWaitpidEinval(id = "replica",  probability = 0.01)
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
    ChaosWaitpidEinval[] value();
  }
}
