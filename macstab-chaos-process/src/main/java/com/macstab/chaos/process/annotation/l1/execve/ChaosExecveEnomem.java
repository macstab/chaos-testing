/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

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
 * Injects {@code ENOMEM} into {@code execve} calls intercepted by libchaos-process, causing the
 * calling code to observe an out-of-memory failure when attempting to replace the process image.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, errno = {@code ENOMEM}) tuple.
 * The {@code EXECVE} selector intercepts {@code execve} calls only, leaving {@code fork}, {@code
 * pthread_create}, {@code posix_spawn}, {@code posix_spawnp}, {@code execveat}, and {@code waitpid}
 * unaffected. Compile-time safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.
 *   <li>On each {@code execve} call the interposer runs a Bernoulli trial with probability {@link
 *       #probability}.
 *   <li>When the trial fires, the interposer sets {@code errno = ENOMEM} and returns {@code -1}
 *       without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 12, {@code strerror}: "Out of
 *       memory"; no new process image is loaded and the calling process remains unchanged.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code execve} returns {@code -1}; {@code errno = ENOMEM} (12); the kernel cannot allocate
 *       the memory structures needed to set up the new process image — the calling process remains
 *       in its current state (unlike a successful exec, which replaces the image non-atomically).
 *   <li>Process launchers and shell interpreters must handle {@code ENOMEM} from {@code execve} as
 *       a transient condition — assert that the launcher retries the exec after a brief delay or
 *       reports the failure to the orchestrator for rescheduling rather than treating it as a
 *       permanent binary-not-found error.
 *   <li>Assert that the application correctly preserves all pre-exec state on {@code ENOMEM}: file
 *       descriptors, signal dispositions, and memory mappings remain from the calling process since
 *       the exec failed before the kernel committed to replacing the image.
 * </ul>
 *
 * Production failure mode: a Kubernetes node approaches its memory limit during a rolling
 * deployment; the orchestrator launches new pod instances while old ones are still running — the
 * combination of existing processes and the exec overhead (kernel allocating argument pages, stack,
 * and binfmt setup) pushes the node's committed memory above the OOM threshold; new process images
 * fail to load with {@code ENOMEM}, causing a wave of pod startup failures that the orchestrator
 * retries, further increasing memory pressure.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies {@code ENOMEM} for {@code execve} when there is insufficient memory to set up
 * the new process image. On Linux, the kernel's exec path allocates several structures before
 * committing to the new image: the argument page array ({@code bprm->page}), a new stack, and VMA
 * structures for the text, data, BSS, and stack segments. If any of these allocations fail, the
 * kernel returns {@code -ENOMEM} and the calling process image is left unchanged.
 *
 * <p>The exec-ENOMEM scenario is asymmetric with fork-ENOMEM: a failed {@code fork} produces no
 * child process and the parent continues normally; a failed {@code execve} leaves the calling
 * process in its current state, which is the correct recovery path — the caller can retry, use a
 * different binary, or propagate the error upstream. Applications that assume {@code execve} either
 * succeeds or is non-recoverable will not correctly handle the ENOMEM case.
 *
 * <p>A nuance specific to Linux: if the application uses {@code fork} + {@code execve} (the classic
 * process-spawn pattern), and the {@code execve} in the child fails with {@code ENOMEM}, the child
 * is still alive in the parent's image state. The child must exit (ideally with a specific exit
 * code that the parent detects via {@code waitpid}) rather than continuing execution in the
 * parent's image; applications that ignore the {@code execve} return value in the fork+exec idiom
 * will create a zombie child that continues executing the parent's code.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveEnomem(probability = 0.001)
 * class ExecveMemoryPressureTest {
 *   @Test
 *   void launcherRetriesExecveAfterEnomemRatherThanTreatingAsMissing(ConnectionInfo info) {
 *     // verify fork+exec child exits cleanly on ENOMEM; parent does not create zombie
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3; exec-ENOMEM is a rare event in
 * well-provisioned environments but the fork+exec zombie risk makes even low-probability coverage
 * valuable.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessErrnoBinding
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Repeatable(ChaosExecveEnomem.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.EXECVE, errno = ProcessErrno.ENOMEM)
public @interface ChaosExecveEnomem {

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
   * @ChaosExecveEnomem(id = "primary",  probability = 0.001)
   * @ChaosExecveEnomem(id = "replica",  probability = 0.01)
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
    ChaosExecveEnomem[] value();
  }
}
