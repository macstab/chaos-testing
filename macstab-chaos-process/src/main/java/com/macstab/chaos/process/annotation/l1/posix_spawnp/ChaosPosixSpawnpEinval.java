/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawnp;

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
 * Injects {@code EINVAL} into {@code posix_spawnp} calls intercepted by libchaos-process, causing
 * the calling code to observe an invalid-argument failure when attempting to spawn a new process
 * via {@code $PATH} lookup.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, errno = {@code EINVAL})
 * tuple. The {@code POSIX_SPAWNP} selector intercepts {@code posix_spawnp} calls only, leaving
 * {@code posix_spawn}, {@code fork}, and all other process syscalls unaffected. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.
 *   <li>On each {@code posix_spawnp} call the interposer runs a Bernoulli trial with probability
 *       {@link #probability}.
 *   <li>When the trial fires, the interposer returns {@code EINVAL} directly (POSIX spawn returns
 *       the error code, not -1) without issuing the real kernel call.
 *   <li>The calling code receives: return value {@code EINVAL} (22), {@code strerror}: "Invalid
 *       argument"; no child process is created; the pid output parameter is not set to a valid
 *       value.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code posix_spawnp} returns {@code EINVAL}; no child process is created; assert that the
 *       application treats EINVAL as a non-retryable programming error — the spawn attribute
 *       structure or file-actions structure contains an invalid value that must be fixed in code.
 *   <li>Applications building spawn attribute structures ({@code posix_spawnattr_t}) and file
 *       action sequences ({@code posix_spawn_file_actions_t}) dynamically must validate their
 *       structures before calling spawnp — assert that EINVAL from spawnp triggers a diagnostic
 *       that includes the attribute values for operator debugging.
 *   <li>Assert that the application does not call {@code waitpid} on an uninitialised pid after an
 *       EINVAL failure — POSIX does not define the pid output parameter value when spawn fails.
 * </ul>
 *
 * Production failure mode: a command executor builds spawn attribute structures using a generic
 * serialisation framework; a version upgrade changes the encoding of a scheduling policy flag,
 * producing an attribute structure that the kernel rejects with EINVAL; the executor does not
 * validate attributes before spawning and surfaces a generic "spawn failed" error without the
 * invalid attribute value, making root cause analysis difficult.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EINVAL} from {@code posix_spawnp} originates from the same sources as {@code
 * posix_spawn}: invalid {@code posix_spawnattr_t} (invalid scheduling policy, parameter out of
 * range, invalid signal number in signal mask) or invalid {@code posix_spawn_file_actions_t}
 * (invalid fd number, invalid flags). The {@code $PATH} search that distinguishes spawnp from spawn
 * occurs before the fork/exec sequence — if the PATH search succeeds (binary found) but the
 * attributes are invalid, EINVAL is returned from the spawn's internal exec or
 * attribute-application step. The interposer fires at the API boundary. POSIX spawn returns the
 * error code directly; checking {@code if (ret == -1)} silently misses EINVAL (22).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpEinval(probability = 0.01)
 * class PosixSpawnpInvalidAttributeTest {
 *   @Test
 *   void executorReportsInvalidAttributeValuesOnEinvalAndDoesNotRetry(ConnectionInfo info) {
 *     // verify EINVAL treated as programming error; attribute values logged; no retry loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EINVAL represents a configuration error;
 * any non-zero probability exercises the non-retryable error path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnpEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWNP, errno = ProcessErrno.EINVAL)
public @interface ChaosPosixSpawnpEinval {

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
   * @ChaosPosixSpawnpEinval(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnpEinval(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnpEinval[] value();
  }
}
