/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * Injects {@code EINVAL} ("Invalid argument") into every process-management syscall intercepted
 * by libchaos-process — {@code fork}, {@code execve}, {@code posix_spawn}, {@code pthread_create},
 * {@code waitpid}, and their variants — simultaneously, gated by {@link #probability}, modelling
 * argument configuration regressions that can affect any process lifecycle operation.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EINVAL}) tuple.
 * The {@code WILDCARD} selector intercepts every process-management syscall family simultaneously:
 * fork, execve, execveat, posix_spawn, posix_spawnp, pthread_create, and waitpid. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>On each intercepted syscall, a Bernoulli trial with probability {@link #probability}
 *       runs.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EINVAL} and returns {@code -1}
 *       (or the errno value directly for pthread_create and POSIX spawn functions) before the
 *       real kernel call executes.</li>
 *   <li>The calling code receives: {@code fork()}/{@code waitpid()} return {@code -1} with
 *       {@code errno = EINVAL} (22); {@code pthread_create}/{@code posix_spawn} return
 *       {@code EINVAL} directly; {@code strerror(EINVAL)}: "Invalid argument".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code pthread_create} returns {@code EINVAL} directly when the {@code pthread_attr_t}
 *       contains invalid values (stack size below PTHREAD_STACK_MIN, invalid scheduling policy,
 *       priority out of range); assert that the application logs the attribute values that caused
 *       the failure — EINVAL from thread creation is a programming error, non-retryable with
 *       the same attributes.</li>
 *   <li>{@code waitpid()} returns {@code -1} with {@code errno = EINVAL} when the options bitmask
 *       contains invalid bits; assert that the application does not retry — EINVAL from waitpid
 *       means the options argument is wrong, and retrying with the same options will always fail.</li>
 *   <li>{@code posix_spawn}/{@code posix_spawnp} return {@code EINVAL} when the
 *       {@code posix_spawnattr_t} contains an unsupported scheduling policy or flags value;
 *       assert that the application does not call {@code waitpid} on an uninitialised pid —
 *       the child was never created.</li>
 *   <li>Assert that EINVAL from any process-management call is logged with the specific argument
 *       values that were used — EINVAL is a programming error and the argument values are the
 *       essential diagnostic information for root-cause analysis.</li>
 * </ul>
 * Production failure mode: a hot-reload of thread pool configuration changes the stack size
 * attribute to a value below PTHREAD_STACK_MIN; all subsequent pthread_create calls return EINVAL;
 * the pool's error handler does not log the attribute values; operators cannot diagnose why thread
 * creation is failing; the pool exhausts all threads and stops serving requests.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code EINVAL} from process-management syscalls indicates a programming error — an argument
 * that the kernel or library rejected as structurally invalid. The specific invalid argument
 * differs by syscall: for {@code pthread_create}, it is a {@code pthread_attr_t} field value
 * (stack size, scheduling policy, priority, detach state, guard size); for {@code waitpid}, it is
 * a bit in the options bitmask (any bit other than WNOHANG=1, WUNTRACED=2, WCONTINUED=8,
 * WEXITED=4, WNOWAIT=0x1000000); for {@code posix_spawn}, it is a {@code posix_spawnattr_t}
 * or {@code posix_spawn_file_actions_t} field.
 *
 * <p>The wildcard selector fires EINVAL across all process families. This validates that every
 * process-management path logs the specific argument values on EINVAL and treats it as non-retryable.
 * A common defect is a catch-all error handler that logs only "process error: EINVAL" without
 * recording which arguments were invalid — the wildcard variant surfaces this diagnostic gap across
 * all call sites simultaneously.
 *
 * <p>Return-value conventions differ: {@code fork()}/{@code waitpid()} return {@code -1} and set
 * {@code errno}; {@code pthread_create}/{@code posix_spawn} return the error code directly.
 * Code that checks only {@code if (ret == -1 && errno == EINVAL)} misses EINVAL from spawn and
 * thread-create paths. The wildcard variant exercises both return-value conventions simultaneously.
 *
 * <p>EINVAL consequences differ by syscall: EINVAL from {@code waitpid} during active child
 * monitoring causes zombie accumulation (children exit but cannot be reaped); EINVAL from
 * {@code pthread_create} causes thread starvation; EINVAL from POSIX spawn leaves child pids
 * uninitialised. Each consequence requires a different recovery action — the application must
 * identify the specific call site and argument, not just the errno.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEinval(probability = 0.002)
 * class EinvalDiagnosticsTest {
 *   @Test
 *   void allProcessManagementPathsLogArgumentValuesOnEinvalAndDoNotRetry(ConnectionInfo info) {
 *     // drive workload triggering all process families; assert EINVAL logged with argument values;
 *     // assert no retry on EINVAL; assert waitpid zombie accumulation detected; assert thread pool alerts
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 5e-3; EINVAL is a non-retryable programming
 * error so higher rates cause more persistent failures — start with 1e-3 to validate diagnostic
 * logging without disrupting the container's steady state.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosWildcardEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EINVAL)
public @interface ChaosWildcardEinval {

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
   * @ChaosWildcardEinval(id = "primary",  probability = 0.001)
   * @ChaosWildcardEinval(id = "replica",  probability = 0.01)
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
    ChaosWildcardEinval[] value();
  }
}
