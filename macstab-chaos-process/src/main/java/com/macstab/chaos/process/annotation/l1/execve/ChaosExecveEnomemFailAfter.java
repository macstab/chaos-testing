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
 * {@code ENOMEM} on every subsequent call, simulating memory exhaustion at the exec path that takes
 * effect after a bounded number of successful process image replacements.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code ENOMEM}, effect =
 * FAIL_AFTER) tuple. FAIL_AFTER is libchaos-process's counter-gated effect: the first {@link
 * #successesBeforeFailure} matched calls succeed normally; every call after that returns {@code -1}
 * with {@code errno = ENOMEM} until the rule is removed. This models progressive memory exhaustion
 * scenarios where each exec consumes memory that is not released, and the system eventually runs
 * out of capacity to allocate the argument pages or stack for new images.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule atomic counter of successful {@code execve} calls.
 *   <li>Once the counter reaches {@link #successesBeforeFailure}, it trips and every subsequent
 *       intercepted call sets {@code errno = ENOMEM} and returns {@code -1} without executing.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12, {@code strerror}: "Out of
 *       memory"; the calling process remains unchanged.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} {@code execve} calls succeed; subsequent calls
 *       fail with {@code ENOMEM} — the application must treat this as a transient resource
 *       exhaustion and implement a backoff/retry strategy rather than marking the binary as
 *       permanently absent.
 *   <li>Fork+exec patterns must handle the ENOMEM case in the child process: the child that fails
 *       {@code execve} with {@code ENOMEM} must exit cleanly with a specific exit code so that the
 *       parent can detect the exec failure via {@code waitpid} and distinguish it from a successful
 *       process that exited with a non-zero code.
 *   <li>Assert that the application's process-pool manager stops accepting new spawn requests when
 *       exec-ENOMEM persists across retries, surfaces a memory-pressure alert, and begins
 *       backpressure rather than accumulating a queue of unserviced spawn requests.
 * </ul>
 *
 * Production failure mode: a node approaches its cgroup memory limit during a burst of request
 * processing; each new request requires spawning a helper subprocess via exec; as the node fills
 * up, exec calls for new helpers fail with {@code ENOMEM}; the application retries in a tight loop,
 * which itself increases memory pressure by keeping the calling thread alive and consuming stack,
 * creating a memory-pressure spiral that prevents recovery.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The FAIL_AFTER effect for exec-ENOMEM captures the progressive memory exhaustion scenario that
 * the probabilistic ERRNO effect cannot: real ENOMEM from exec follows an availability curve where
 * initially execs succeed and later ones fail as memory is consumed — never randomly scattered
 * throughout a sequence. The N-success-then-fail pattern directly models this temporal profile,
 * with N representing the number of successful exec calls before the node's memory is exhausted.
 *
 * <p>The critical fork+exec zombie risk is amplified under FAIL_AFTER: in the fork+exec pattern,
 * the child process attempts {@code execve}; when it fails with {@code ENOMEM}, the child is still
 * running in the parent's image. If the child does not check the exec return value and call {@code
 * _exit}, it will continue executing the parent's code as a zombie copy, potentially holding locks,
 * consuming resources, and producing incorrect state. The FAIL_AFTER threshold makes this failure
 * deterministic and reproducible, which aids in verifying the exit-on-exec- failure code path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEnomemFailAfter(successesBeforeFailure = 20)
 * class ExecMemoryExhaustionTest {
 *   @Test
 *   void processPoolBackpressuresWhenExecEnomemPersists(ConnectionInfo info) {
 *     // verify pool stops spawning, surfaces alert, and backpressures upstream after threshold
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of exec
 * calls that occur during normal request processing before memory pressure is reached; use the
 * observed spawn rate multiplied by expected-time-to-exhaustion in seconds.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveEnomemFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.ENOMEM)
public @interface ChaosExecveEnomemFailAfter {

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
   * @ChaosExecveEnomemFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveEnomemFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveEnomemFailAfter[] value();
  }
}
