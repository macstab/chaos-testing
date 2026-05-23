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
 * all intercepted families, injects {@code E2BIG} on every subsequent call, modelling an
 * environment variable accumulation scenario where argument vectors grow beyond ARG_MAX after N
 * successful process launches.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code E2BIG},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
 * process-management calls (across all families — fork, execve, posix_spawn, pthread_create,
 * waitpid) succeed, then the counter trips permanently and every subsequent call returns the error
 * code until the rule is removed. Compile-time safety: invalid selector/errno/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter shared across all intercepted syscall
 *       families; the counter does not reset automatically between test methods when the annotation
 *       is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent process-management
 *       call returns {@code -1} (or the errno value directly for pthread_create and posix_spawn)
 *       with {@code errno = E2BIG}.</li>
 *   <li>The calling code receives: {@code execve}/{@code fork} return {@code -1} with
 *       {@code errno = E2BIG} (7); {@code posix_spawn} and {@code pthread_create} return
 *       {@code E2BIG} directly; {@code strerror(E2BIG)}: "Argument list too long".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls (across all families)
 *       proceed normally; all subsequent calls return E2BIG; assert that the application detects
 *       the argument-too-long condition and logs the argument byte count for operator diagnosis.</li>
 *   <li>FAIL_AFTER models the environment-accumulation scenario: N successful process launches
 *       each carry an incrementally larger environment; at call N+1 the environment has crossed
 *       ARG_MAX; all subsequent launches return E2BIG — assert that the application prunes the
 *       environment before retrying rather than retrying with the same oversized environment.</li>
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after
 *       E2BIG from a spawn call — the child was never created; assert that the application's
 *       child-tracking registry is not updated when the spawn fails.</li>
 * </ul>
 * Production failure mode: a supervisor process spawns worker processes, passing each worker the
 * full parent environment augmented with per-worker configuration; over N launches the environment
 * grows beyond ARG_MAX; subsequent spawn attempts return E2BIG; the supervisor retries with the
 * same environment; the retry loop blocks all worker pool expansion indefinitely.
 *
 * <h2>Deep technical dive</h2>
 * <p>The WILDCARD FAIL_AFTER counter is shared across all intercepted syscall families. This means
 * that N fork calls + M pthread_create calls together consume the counter budget. For testing a
 * specific scenario (e.g., the Nth exec fails), use a single-selector variant (e.g.,
 * {@code ChaosExecveE2bigFailAfter}) so the counter only charges on the targeted syscall family.
 * The wildcard variant is appropriate when testing that the application correctly handles E2BIG
 * regardless of which syscall returns it, without needing to predict which family fires first.
 *
 * <p>The counter does not reset between test methods when the annotation is at class scope. This
 * enables sequential testing: first test method exercises N successful process launches (the
 * pre-regression phase); subsequent test methods exercise the E2BIG phase. Set
 * {@link #successesBeforeFailure} to the number of process-management calls the application makes
 * during its startup and first successful request handling phase.
 *
 * <p>Return-value conventions differ by function: {@code execve}/{@code fork} return {@code -1}
 * and set {@code errno}; {@code posix_spawn}/{@code posix_spawnp} return the error code directly;
 * {@code pthread_create} also returns the error code directly. Code that checks only
 * {@code if (ret == -1 && errno == E2BIG)} misses errors from spawn and thread-create paths.
 * The FAIL_AFTER counter charges across all families, so the test exercises all of these paths
 * in sequence.
 *
 * <p>E2BIG is non-retryable with the same arguments. The application must either trim its
 * argument vector, prune the environment to reduce total byte count, or fall back to a minimal
 * spawn that avoids environment passthrough. Applications that retry on E2BIG without pruning
 * will spin indefinitely since the argument size does not change between attempts.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardE2bigFailAfter(successesBeforeFailure = 20)
 * class ArgMaxExhaustionTest {
 *   @Test
 *   void supervisorPrunesEnvironmentOnE2bigAndAlertsOperator(ConnectionInfo info) {
 *     // first 20 process calls succeed; subsequent calls return E2BIG;
 *     // verify env pruning attempted; E2BIG logged with byte count; no infinite retry;
 *     // child-tracking registry not updated on failed spawn
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * process-management calls the application makes before the environment accumulation scenario
 * occurs; values 5–200 cover typical init + steady-state phases; 0 means every call fails from
 * startup.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardE2bigFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.E2BIG)
public @interface ChaosWildcardE2bigFailAfter {

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
   * @ChaosWildcardE2bigFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardE2bigFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardE2bigFailAfter[] value();
  }
}
