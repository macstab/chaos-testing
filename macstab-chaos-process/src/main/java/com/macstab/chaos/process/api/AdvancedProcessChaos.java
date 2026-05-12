/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.api;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ProcessChaos;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Capability-tier interface exposing libchaos-process's process-lifecycle fault-injection surface —
 * including the unique {@code FAIL_AFTER} counter that lets N calls succeed before failure begins.
 *
 * <p><strong>Pre-flight contract.</strong> Every method on this interface requires that the target
 * container has been prepared with libchaos-process <em>before</em> {@code container.start()} — the
 * {@code .so} is hooked via {@code LD_PRELOAD}, which the dynamic loader only honours at process
 * launch. Skipping preparation raises {@link LibchaosNotPreparedException} loudly. Annotate the
 * test class with {@code @SyscallLevelChaos(LibchaosLib.PROCESS)} to let {@code
 * ChaosTestingExtension} drive preparation.
 *
 * <p><strong>Capability uplift over {@link ProcessChaos}.</strong> The portable parent interface
 * ({@code kill} / {@code pause} / {@code limitProcesses} / {@code listProcesses}) models
 * whole-container resource governance and signal delivery. This interface adds per-syscall failure
 * injection that cgroups cannot reach:
 *
 * <ul>
 *   <li><strong>Thread-creation failure</strong> — make {@code pthread_create()} return {@code
 *       EAGAIN}. Models the "we hit the OS thread limit and silently dropped requests"
 *       production-incident class.
 *   <li><strong>Thread-pool exhaustion</strong> — {@code FAIL_AFTER:EAGAIN,N} lets the first N
 *       thread creations succeed then fails the rest. This is the closest tests get to simulating
 *       RLIMIT_NPROC behaviour without actually invoking the kernel limit.
 *   <li><strong>Fork failure</strong> — server-side process spawn fails with {@code EAGAIN} /
 *       {@code ENOMEM}.
 *   <li><strong>Exec failure</strong> — {@code execve()} fails with {@code ENOENT} (missing
 *       binary), {@code EACCES} (noexec mount), {@code ENOEXEC} (bad ELF), {@code E2BIG} (argv
 *       overflow), {@code ETXTBSY}, {@code ELOOP}, {@code EPERM}, {@code ENOMEM} — the full 8-errno
 *       palette.
 *   <li><strong>Wait failure</strong> — {@code waitpid()} returns {@code EINTR} regardless of
 *       SA_RESTART (reveals latent EINTR-handling bugs), or {@code ECHILD} (phantom-wait test).
 * </ul>
 *
 * <p><strong>FAIL_AFTER counter reset.</strong> libchaos-process resets the per-rule counter on
 * config reload. The {@code apply()} / {@code remove()} verbs go through that reload path —
 * applying a new rule resets all counters. For a single-shot exhaustion test, apply the rule once,
 * drive the test until the N-th call fails, then remove the rule.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * class MyTest {
 *   @Test
 *   void serverDegradesGracefullyOnThreadPoolExhaustion(ProcessChaos chaos, GenericContainer<?> app) {
 *     AdvancedProcessChaos adv = (AdvancedProcessChaos) chaos;
 *
 *     // First 128 thread creations succeed; subsequent ones return EAGAIN
 *     RuleHandle h = adv.exhaustThreadPool(app, 128);
 *
 *     driveLoadUntilFailureSurfaces(app);
 *
 *     adv.remove(app, h);
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface AdvancedProcessChaos extends ProcessChaos {

  // ==================== Generic rule API ====================

  /**
   * Apply a single libchaos-process rule.
   *
   * @return handle for later removal
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-process is not active on {@code container}
   */
  RuleHandle apply(GenericContainer<?> container, ProcessRule rule);

  /**
   * Apply a batch of rules in a single round-trip. Validates every rule before committing any of
   * them (fail-fast).
   */
  List<RuleHandle> applyAll(GenericContainer<?> container, List<ProcessRule> rules);

  /** Surgically remove a single previously-applied rule. Idempotent. */
  void remove(GenericContainer<?> container, RuleHandle handle);

  /** Remove every rule this strategy has applied to {@code container}. */
  void removeAll(GenericContainer<?> container);

  // ==================== Raw-rule escape hatches ====================

  /**
   * Apply an errno fault to an arbitrary selector — escape hatch when the typed convenience verbs
   * do not fit.
   *
   * @throws IllegalArgumentException if {@code errno} is not valid for {@code selector}
   */
  RuleHandle errno(
      GenericContainer<?> container,
      ProcessSelector selector,
      ProcessErrno errno,
      double probability);

  /** Apply a latency effect to an arbitrary selector. */
  RuleHandle latency(GenericContainer<?> container, ProcessSelector selector, Duration delay);

  /**
   * Apply a {@code FAIL_AFTER} effect to an arbitrary selector — escape hatch. Lets the first
   * {@code count} matched calls succeed, then fails subsequent calls with {@code errno}.
   *
   * @throws IllegalArgumentException if {@code errno} is not valid for {@code selector} or {@code
   *     count < 0}
   */
  RuleHandle failAfter(
      GenericContainer<?> container, ProcessSelector selector, ProcessErrno errno, long count);

  // ==================== THREAD CREATION ====================

  /** Inject {@code EAGAIN} on {@code pthread_create()} at the given probability. */
  RuleHandle failThreadCreation(GenericContainer<?> container, double probability);

  /**
   * Inject an arbitrary errno on {@code pthread_create()} — gives access to {@code EAGAIN}
   * (resource limit), {@code EINVAL} (bad attr), {@code EPERM} (scheduling permission).
   */
  RuleHandle failThreadCreation(
      GenericContainer<?> container, ProcessErrno errno, double probability);

  /**
   * <strong>FAIL_AFTER variant:</strong> let the first {@code maxThreads} thread creations succeed,
   * then fail every subsequent call with {@code EAGAIN}. Models the kernel hitting the OS-wide
   * thread limit (RLIMIT_NPROC) without actually configuring the limit.
   *
   * @param maxThreads number of successful creations before failure ({@code >= 0})
   * @return handle for later removal
   */
  RuleHandle exhaustThreadPool(GenericContainer<?> container, long maxThreads);

  /** Delay {@code pthread_create()} calls — simulate slow thread initialisation. */
  RuleHandle slowThreadCreation(GenericContainer<?> container, Duration delay);

  // ==================== FORK / PROCESS CREATION ====================

  /** Inject {@code EAGAIN} on {@code fork()}. */
  RuleHandle failFork(GenericContainer<?> container, double probability);

  /** Inject {@code EAGAIN} or {@code ENOMEM} on {@code fork()}. */
  RuleHandle failFork(GenericContainer<?> container, ProcessErrno errno, double probability);

  /**
   * <strong>FAIL_AFTER variant:</strong> let the first {@code maxForks} forks succeed, then fail
   * every subsequent call with {@code EAGAIN}. Models RLIMIT_NPROC exhaustion.
   */
  RuleHandle exhaustProcessLimit(GenericContainer<?> container, long maxForks);

  /** Delay {@code fork()} calls. */
  RuleHandle slowFork(GenericContainer<?> container, Duration delay);

  // ==================== POSIX SPAWN ====================

  /** Inject {@code ENOENT} on {@code posix_spawn()}. */
  RuleHandle failSpawn(GenericContainer<?> container, double probability);

  /** Inject an arbitrary errno on {@code posix_spawn()}. */
  RuleHandle failSpawn(GenericContainer<?> container, ProcessErrno errno, double probability);

  /**
   * Inject {@code ENOENT} on {@code posix_spawnp()} — the PATH-search variant. Models "executable
   * not in PATH".
   */
  RuleHandle failSpawnByPath(GenericContainer<?> container, double probability);

  /** Delay {@code posix_spawn()} calls. */
  RuleHandle slowSpawn(GenericContainer<?> container, Duration delay);

  // ==================== EXEC ====================

  /** Inject {@code ENOENT} on {@code execve()} — the canonical "binary not found". */
  RuleHandle failExec(GenericContainer<?> container, double probability);

  /** Inject an arbitrary errno on {@code execve()}. */
  RuleHandle failExec(GenericContainer<?> container, ProcessErrno errno, double probability);

  /** Inject {@code EACCES} on {@code execve()} — models noexec mount or missing permission. */
  RuleHandle failExecPermission(GenericContainer<?> container, double probability);

  /**
   * Inject {@code ENOENT} on {@code execve()} — semantic alias targeting "missing binary" tests.
   */
  RuleHandle failExecMissingBinary(GenericContainer<?> container, double probability);

  /** Inject {@code E2BIG} on {@code execve()} — argv+envp overflow. */
  RuleHandle failExecTooLarge(GenericContainer<?> container, double probability);

  /** Inject {@code ENOEXEC} on {@code execve()} — bad ELF magic / unknown binary format. */
  RuleHandle failExecBadFormat(GenericContainer<?> container, double probability);

  /** Inject {@code ENOENT} on {@code execveat()} — fd-relative exec failure. */
  RuleHandle failExecRelative(GenericContainer<?> container, double probability);

  /** Delay {@code execve()} calls. */
  RuleHandle slowExec(GenericContainer<?> container, Duration delay);

  // ==================== WAIT ====================

  /** Inject {@code ECHILD} on {@code waitpid()} — phantom-wait (no children) test. */
  RuleHandle failWait(GenericContainer<?> container, double probability);

  /** Inject an arbitrary errno on {@code waitpid()}. */
  RuleHandle failWait(GenericContainer<?> container, ProcessErrno errno, double probability);

  /**
   * Inject {@code EINTR} on {@code waitpid()} — exercises the EINTR retry loop. This bypasses the
   * kernel's SA_RESTART logic (no real signal is delivered), so applications that rely on
   * SA_RESTART to silently swallow EINTR will surface latent bugs.
   */
  RuleHandle signalInterruptWait(GenericContainer<?> container, double probability);

  /** Inject {@code ECHILD} on {@code waitpid()} — semantic alias of {@link #failWait}. */
  RuleHandle phantomWait(GenericContainer<?> container, double probability);

  /** Delay {@code waitpid()} calls. */
  RuleHandle slowWait(GenericContainer<?> container, Duration delay);
}
