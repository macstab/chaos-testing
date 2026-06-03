/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Every {@code waitpid()} call returns {@code ESRCH}, simulating the kernel reporting that the
 * waited-for process does not exist. From the application's perspective the child process
 * disappeared without producing a wait-collectible exit status — the PID was never valid or was
 * already reaped by another waiter. Applications that maintain a PID registry and blindly wait
 * on recorded PIDs will receive {@code ESRCH} for every entry once those PIDs are recycled by
 * the kernel.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.WAITPID, ProcessErrno.ESRCH, 1.0)} via
 * libchaos-process. In production {@code ESRCH} from {@code waitpid} occurs when a process
 * sends {@code SIGKILL} to a pid that has already been reaped, when a supervisor restarts a
 * child and the old PID is recycled before the parent calls wait, or when PID-namespace
 * boundaries cause the caller to wait on a PID that is not its descendant.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * With probability {@code 1.0} every wait-call returns {@code ESRCH}. Process supervisors and
 * job dispatch frameworks that depend on wait-based completion signalling cannot determine whether
 * child jobs finished successfully. PID registries leak entries. Health-check loops that wait
 * for child processes block forever or spin on ESRCH. Immediate operator intervention and PID
 * registry reconciliation are required.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code ESRCH} from {@code waitpid} is documented in the Linux {@code waitpid(2)} man-page:
 * "No process in the process group of the calling process has the same PGID as pid, or pid does
 * not exist." PID recycling in containerised environments is a known hazard discussed in the
 * Docker and Kubernetes documentation on init processes and zombie reaping.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosHardKill
 * class HardKillTest {
 *   @Test
 *   void supervisorHandlesEsrchWithoutLeakingPidEntry() {
 *     // assert: ESRCH logged; PID entry removed from registry; no hang
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosHardKill.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.HardKillComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosHardKill {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code ESRCH} fires on each {@code waitpid()} call.
   * Defaults to {@code 1.0} (every wait call reports no-such-process).
   */
  double toxicity() default 1.0;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosHardKill[] value();
  }
}
