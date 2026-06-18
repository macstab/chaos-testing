/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * After {@link #successesBeforeFailure} successful process-management syscall invocations across
 * all intercepted families, injects {@code EINVAL} on every subsequent call, modelling a
 * configuration regression scenario where a hot-reload introduces invalid attribute values for
 * thread creation, spawn, or waitpid options after N successful operations.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EINVAL}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
 * process-management calls (across all families — fork, execve, posix_spawn, pthread_create,
 * waitpid) succeed, then the counter trips permanently and every subsequent call returns the error
 * code until the rule is removed. Compile-time safety: invalid selector/errno/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter shared across all intercepted syscall
 *       families; the counter does not reset automatically between test methods when the annotation
 *       is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent process-management
 *       call returns {@code -1} (or the errno value directly for pthread_create and posix_spawn)
 *       with {@code errno = EINVAL}.
 *   <li>The calling code receives: {@code waitpid()}/{@code fork()} return {@code -1} with {@code
 *       errno = EINVAL} (22); {@code posix_spawn}/{@code pthread_create} return {@code EINVAL}
 *       directly; {@code strerror(EINVAL)}: "Invalid argument".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return EINVAL permanently; assert that the application logs the specific
 *       argument values that caused EINVAL on each affected call site — EINVAL is a programming
 *       error and the argument values are the primary diagnostic for root-cause analysis.
 *   <li>FAIL_AFTER models the configuration regression scenario: N process-management calls succeed
 *       with valid configuration; a hot-reload introduces an invalid pthread_attr_t stack size or
 *       waitpid options bitmask; all subsequent calls return EINVAL — assert that the application
 *       identifies the specific configuration value that caused the regression and does not retry
 *       with the same invalid value.
 *   <li>Assert that EINVAL from waitpid during child monitoring causes the application to detect
 *       and alert on zombie accumulation — children that exit while waitpid always returns EINVAL
 *       cannot be reaped and become permanent zombies; assert that the application stops spawning
 *       new children until the EINVAL condition is resolved.
 * </ul>
 *
 * Production failure mode: a hot-reload of thread pool configuration changes the stack size to a
 * value below PTHREAD_STACK_MIN; all subsequent pthread_create and waitpid calls start returning
 * EINVAL; the application's generic error handler does not log the specific attribute values;
 * operators cannot identify the regression; the thread pool exhausts while zombies accumulate.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>EINVAL from different process-management syscalls has different root causes: from {@code
 * pthread_create}, it indicates an invalid {@code pthread_attr_t} (stack size below
 * PTHREAD_STACK_MIN=16384, invalid scheduling policy, priority out of range, invalid guard size);
 * from {@code waitpid}, it indicates an invalid options bitmask (non-standard extension bits); from
 * {@code posix_spawn}, it indicates an invalid {@code posix_spawnattr_t} or {@code
 * posix_spawn_file_actions_t} value. All are non-retryable programming errors.
 *
 * <p>The WILDCARD counter charges across all families. The EINVAL phase begins when the combined
 * traffic exhausts the counter. After the EINVAL phase starts, all process-management operations
 * return EINVAL simultaneously — the application must detect that EINVAL is firing cross-family and
 * trace it to the configuration regression that caused the invalid argument values.
 *
 * <p>A critical secondary consequence: when waitpid starts returning EINVAL, children that exit
 * cannot be reaped and become permanent zombies. Zombie accumulation rate equals the child spawn
 * rate. The application must detect EINVAL from waitpid and stop spawning new children to prevent
 * the process table from filling. The wildcard variant tests whether this zombie accumulation alert
 * fires when EINVAL affects waitpid in combination with other families.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEinvalFailAfter(successesBeforeFailure = 25)
 * class ConfigRegressionTest {
 *   @Test
 *   void applicationLogsArgumentValuesOnEinvalAndStopsSpawningToPreventZombies(ConnectionInfo info) {
 *     // first 25 process calls succeed; subsequent calls return EINVAL;
 *     // verify argument values logged on every path; verify zombie accumulation alert;
 *     // verify spawning stopped; verify no retry with same invalid arguments
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the total number
 * of process-management calls during the valid-configuration phase; values 10–200 cover typical
 * init + steady-state phases; 0 means EINVAL fires from the very first call.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEinvalFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EINVAL)
public @interface ChaosWildcardEinvalFailAfter {

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
   * @ChaosWildcardEinvalFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEinvalFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEinvalFailAfter[] value();
  }
}
