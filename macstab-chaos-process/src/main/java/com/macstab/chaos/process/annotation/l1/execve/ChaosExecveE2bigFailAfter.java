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
 * {@code E2BIG} on every subsequent call, simulating argument-list exhaustion after a bounded
 * number of successful process image replacements.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code E2BIG},
 * effect = FAIL_AFTER) tuple. FAIL_AFTER is libchaos-process's counter-gated effect: the first
 * {@link #successesBeforeFailure} matched calls succeed normally; every call after that returns
 * {@code -1} with the encoded errno until the rule is removed. This models resource-exhaustion
 * scenarios where capacity is finite and depletes deterministically rather than probabilistically.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule atomic counter of successful {@code execve} calls.</li>
 *   <li>Once the counter reaches {@link #successesBeforeFailure}, it trips and every subsequent
 *       intercepted call sets {@code errno = E2BIG} and returns {@code -1} without executing.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 7,
 *       {@code strerror}: "Argument list too long"; the counter remains tripped until the rule is
 *       removed or the container restarts.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} {@code execve} calls succeed; subsequent calls
 *       fail with {@code E2BIG} — the application must detect the transition and stop spawning
 *       child processes until the underlying condition is resolved.</li>
 *   <li>Process-pool managers and job schedulers that spawn worker processes via {@code execve}
 *       must handle the fail-after threshold gracefully — assert that the manager stops accepting
 *       new work items when child spawning fails rather than accumulating a backlog of unserviced
 *       requests that eventually causes queue overflow.</li>
 *   <li>Assert that the fail-after transition does not produce a visible discontinuity in
 *       application behaviour from the perspective of upstream callers — requests submitted before
 *       the threshold should complete normally; requests submitted after should be rejected with
 *       a clear backpressure signal rather than timing out silently.</li>
 * </ul>
 * Production failure mode: a container orchestrator's argument-accumulation bug gradually grows
 * the environment size across successive re-deployments; after N successful starts the environment
 * crosses {@code ARG_MAX} and every subsequent restart fails with {@code E2BIG} — the service
 * enters a permanent crash-loop with no self-recovery path until the environment is manually
 * trimmed.
 *
 * <h2>Deep technical dive</h2>
 * <p>The FAIL_AFTER effect differs fundamentally from the probabilistic ERRNO effect: ERRNO fires
 * on any call with probability {@code p}, so failures are scattered randomly throughout the
 * call sequence; FAIL_AFTER fires deterministically after exactly {@code N} successes, so
 * failures cluster at the end of the sequence. For {@code execve}, the deterministic model is
 * more realistic than the probabilistic one for argument-limit scenarios: the environment size
 * grows monotonically with each deployment and the limit is crossed at a predictable point.
 *
 * <p>The counter-trip semantic is permanent until rule removal — there is no auto-reset after
 * the container makes additional calls. This means the failing state persists for the lifetime
 * of the test unless the rule is explicitly removed via {@code AdvancedProcessChaos.remove}.
 * Test methods that share a class-scoped annotation must account for this: if {@code test1}
 * exhausts the counter, {@code test2} will also see failures from the first call. Use method-
 * scoped annotations to isolate counter state between test methods.
 *
 * <p>Setting {@link #successesBeforeFailure} to zero makes the very first {@code execve} call
 * fail, which is useful for testing startup-time argument validation — verifying that the
 * application's own launch sequence handles exec failure gracefully before any worker process
 * is started. Setting it to the expected number of worker spawns per test scenario exercises
 * the exact saturation boundary that production environments cross during overload.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveE2bigFailAfter(successesBeforeFailure = 10)
 * class ExecveExhaustionTest {
 *   @Test
 *   void workerPoolStopsAcceptingWorkAfterExecveE2bigThreshold(ConnectionInfo info) {
 *     // verify pool drains in-flight requests and rejects new ones when execve fails
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * {@code execve} calls the application makes during normal test-scenario execution; zero triggers
 * failure on the first exec (useful for startup-path testing); values above the expected spawn
 * count make the annotation a no-op for that test.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveE2bigFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.E2BIG)
public @interface ChaosExecveE2bigFailAfter {

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
   * @ChaosExecveE2bigFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveE2bigFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveE2bigFailAfter[] value();
  }
}
