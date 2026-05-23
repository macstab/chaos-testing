/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execveat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessLatencyBinding;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * Delays every {@code execveat} call intercepted by libchaos-process by {@link #delayMs}
 * milliseconds before delegating to the real kernel call, causing the calling code to observe
 * a slow exec without receiving an error.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, effect = LATENCY) pair.
 * Unlike ERRNO variants, the LATENCY primitive always delegates to the real kernel call — it only
 * injects wall-clock cost before issuing the syscall. The exec succeeds (or fails for genuine
 * reasons); only the time taken increases. Compile-time safety: invalid selector/effect
 * combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code execveat} call the interposer sleeps for {@link #delayMs} milliseconds in
 *       the calling thread before issuing the real syscall — the sleep occurs before the kernel
 *       call, so the calling process is stalled for the full delay period.</li>
 *   <li>After the sleep the real {@code execveat} syscall is issued; the kernel processes it
 *       normally and returns its actual result (success or errno).</li>
 *   <li>The calling code receives: the real kernel return value, after a wall-clock delay of at
 *       least {@link #delayMs} ms; no spurious errno is injected; the exec either succeeds
 *       (replacing the process image) or fails for a genuine kernel reason.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>Every {@code execveat} call takes at least {@link #delayMs} ms longer than baseline;
 *       assert that the application does not treat a slow exec as a hung process — if the
 *       application monitors child processes with a fixed timeout from launch start, the delay
 *       must be accounted for in the timeout budget.</li>
 *   <li>Container runtimes using fork+exec with {@code execveat} must allocate the latency budget
 *       correctly: the parent waits on the child (via {@code waitpid}); the child's exec is delayed
 *       by {@link #delayMs} ms before the image replacement occurs; assert that the parent's
 *       {@code waitpid} timeout is set relative to exec completion, not relative to fork time, to
 *       avoid spurious timeout-before-exec errors under latency injection.</li>
 *   <li>Assert that the application's startup readiness probe uses a deadline relative to the
 *       exec's actual completion time rather than the exec call start — a fixed readiness timeout
 *       measured from the fork will fire before the exec completes when {@link #delayMs} exceeds
 *       the slack in the probe budget.</li>
 * </ul>
 * Production failure mode: a container runtime uses {@code execveat} with {@code AT_EMPTY_PATH}
 * to launch the entrypoint binary; the node is under I/O pressure and the kernel's VFS path
 * cache is cold, causing each exec to wait for binary loading from a remote storage backend;
 * the runtime's exec-to-ready probe timeout is calibrated for warm-cache conditions and fires
 * before the binary is loaded, causing the container to be marked as failed and restarted;
 * the restart triggers another exec under the same I/O pressure, creating a restart loop.
 *
 * <h2>Deep technical dive</h2>
 * <p>The {@code execveat} latency profile has two distinct phases: the pre-exec wait and the
 * exec itself. The LATENCY primitive injects delay in the pre-exec wait phase — the interposer
 * sleeps before issuing the syscall, simulating scheduler stalls, VFS path resolution delays, and
 * lock contention on the directory fd. The actual exec (binary loading, bprm setup, argument
 * copying) runs at kernel speed after the sleep. This models the scenario where the delay is in
 * the runtime's bookkeeping (opening the dirfd, preparing the argument vector) rather than in
 * the kernel's binary loading path.
 *
 * <p>The {@code AT_EMPTY_PATH} pattern opens the binary fd before exec, which means the dirfd
 * is held open during the entire delay period. Under high latency (e.g. 500ms+), concurrent
 * exec attempts can accumulate many open dirfds simultaneously — one per pending exec — consuming
 * fd slots and potentially triggering {@code EMFILE} from the open call on the next exec attempt.
 * Applications that do not enforce a concurrency limit on simultaneous exec attempts should be
 * tested with latency values that exceed the expected maximum concurrency × fd-per-exec to
 * verify that fd exhaustion is handled correctly.
 *
 * <p>The fork+exec + latency combination requires careful timeout budget allocation. In a
 * fork+exec pattern, the parent forks, the child calls {@code execveat} (which is delayed by
 * the interposer), and the parent calls {@code waitpid}. The {@code waitpid} does not return
 * until the child either completes the exec (image is replaced and the child starts running)
 * or exits. Under latency injection, the child is stalled in the interposer for {@link #delayMs}
 * ms before the exec proceeds — the parent's {@code waitpid} timeout must be at least
 * {@link #delayMs} ms larger than the baseline exec duration to avoid premature timeout.
 *
 * <p>Credential-delivery patterns that use exec for secret injection (AWS EKS Pod Identity,
 * GCP Workload Identity Federation, HashiCorp Vault agent injection) are particularly sensitive
 * to exec latency: the exec call delivers credentials into the new process image, and any
 * timeout on the credential delivery pipeline is reduced by the exec delay. Applications must
 * assert that credential-delivery timeouts include exec latency headroom rather than assuming
 * instantaneous exec.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatLatency(delayMs = 150)
 * class ExecveatSlowBinaryLoadTest {
 *   @Test
 *   void runtimeWaitsForExecWithSufficientTimeoutAndDoesNotLeakDirfd(ConnectionInfo info) {
 *     // verify exec completes successfully despite 150ms delay;
 *     // parent waitpid timeout includes exec latency; readiness probe not prematurely fired
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 50–200ms simulates realistic I/O-pressure exec stalls and
 * scheduler delays; values above the application's exec-to-ready timeout expose the timeout
 * calibration gap; values in the 500ms–2s range simulate cold-cache binary loading from
 * network-attached storage; combine with concurrent exec calls to surface fd-exhaustion risks.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosExecveatLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.EXECVEAT)
public @interface ChaosExecveatLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

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
   * @ChaosExecveatLatency(id = "primary",  probability = 0.001)
   * @ChaosExecveatLatency(id = "replica",  probability = 0.01)
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
    ChaosExecveatLatency[] value();
  }
}
