/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawn;

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
 * Injects {@code EINVAL} into {@code posix_spawn} calls intercepted by libchaos-process, causing
 * the calling code to observe an invalid-argument failure when attempting to spawn a new process.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWN}, errno = {@code EINVAL})
 * tuple. The {@code POSIX_SPAWN} selector intercepts {@code posix_spawn} calls only, leaving {@code
 * posix_spawnp}, {@code fork}, {@code execve}, and all other process syscalls unaffected.
 * Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawn} wrapper at the dynamic-linker level.
 *   <li>On each {@code posix_spawn} call the interposer runs a Bernoulli trial with probability
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
 *   <li>{@code posix_spawn} returns {@code EINVAL}; no child process is created; assert that the
 *       application treats EINVAL as a non-retryable programming error — the spawn attribute
 *       structure or file-actions structure contains an invalid value that must be fixed in the
 *       calling code, not by retrying the spawn.
 *   <li>Applications that build spawn attribute structures ({@code posix_spawnattr_t}) and file
 *       action sequences ({@code posix_spawn_file_actions_t}) dynamically must validate their
 *       structures before calling spawn — assert that EINVAL from spawn triggers a diagnostic that
 *       includes the attribute values or file-action sequence for operator debugging.
 *   <li>Assert that the application does not attempt to call {@code waitpid} on an uninitialised
 *       pid after an EINVAL failure — POSIX does not define the pid output parameter value when
 *       spawn fails, and waiting on an uninitialised pid is undefined behaviour.
 * </ul>
 *
 * Production failure mode: a process management library builds spawn attribute structures using a
 * generic serialisation framework; a version upgrade changes the encoding of a scheduling policy
 * flag, producing an attribute structure that the kernel rejects with EINVAL; the library does not
 * validate the attribute structure before spawning and surfaces a generic "spawn failed" error
 * without including the invalid attribute value, making root cause analysis difficult.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code EINVAL} from {@code posix_spawn} can originate from two sources: the attribute
 * structure validation ({@code posix_spawnattr_t}) and the file-actions validation ({@code
 * posix_spawn_file_actions_t}). The attribute structure carries scheduling policy, scheduler
 * parameters, signal masks, and process group settings; any invalid combination of these (e.g. a
 * scheduling priority outside the range for the specified policy, or an invalid signal number in
 * the signal mask) causes EINVAL before the fork/exec sequence begins. The file-actions structure
 * carries a sequence of fd operations (open, close, dup2) to perform in the child; an invalid fd
 * number or flag combination causes EINVAL.
 *
 * <p>The POSIX specification for {@code posix_spawn} allows the implementation to detect EINVAL in
 * the child process after fork, in which case the error is communicated back to the parent through
 * an implementation-defined mechanism. On Linux's glibc implementation, EINVAL from invalid spawn
 * attributes is detected before the fork when possible, and in the child after the fork when the
 * error is only detectable in the child context (e.g. a dup2 to a fd number that does not exist
 * yet). Applications that catch EINVAL must handle both cases.
 *
 * <p>Unlike EAGAIN and ENOMEM (transient resource failures), EINVAL is a programming error that
 * cannot be resolved by retrying. Applications should log the spawn attribute values that caused
 * EINVAL and escalate to a configuration alert rather than entering a retry loop. The chaos
 * annotation exercises the error path that is almost never exercised in normal testing because
 * spawn attributes are typically set correctly by the application code.
 *
 * <p>The return-value convention difference from errno-based APIs is critical: code that checks
 * {@code if (errno == EINVAL)} after {@code posix_spawn} without first checking the return value is
 * incorrect — the function returns the error code directly and may not set errno at all. The
 * interposer returns the error code directly, matching the POSIX specification.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnEinval(probability = 0.01)
 * class PosixSpawnInvalidAttributeTest {
 *   @Test
 *   void libraryReportsInvalidAttributeValuesOnEinvalAndDoesNotRetry(ConnectionInfo info) {
 *     // verify EINVAL treated as programming error; attribute values logged; no retry loop
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; EINVAL represents a configuration error
 * rather than a transient condition; any non-zero probability exercises the error path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosPosixSpawnEinval.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.POSIX_SPAWN, errno = ProcessErrno.EINVAL)
public @interface ChaosPosixSpawnEinval {

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
   * @ChaosPosixSpawnEinval(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnEinval(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnEinval[] value();
  }
}
