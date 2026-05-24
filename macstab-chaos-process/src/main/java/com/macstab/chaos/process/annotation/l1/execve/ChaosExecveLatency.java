/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execve;

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
 * Adds {@link #delayMs} milliseconds of latency before every {@code execve} call intercepted by
 * libchaos-process, making all process-image replacement operations succeed but take longer than
 * expected.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVE}, effect = LATENCY) tuple. The
 * {@code EXECVE} selector intercepts {@code execve} calls only, leaving {@code fork}, {@code
 * pthread_create}, {@code posix_spawn}, {@code posix_spawnp}, {@code execveat}, and {@code waitpid}
 * unaffected. Unlike the errno variants, the latency primitive always delegates to the kernel and
 * the operation succeeds; only wall-clock time is affected.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execve} wrapper at the dynamic-linker level.
 *   <li>On each {@code execve} call the interposer sleeps for {@link #delayMs} milliseconds before
 *       issuing the real kernel call.
 *   <li>The kernel call is issued normally and its result is returned to the caller unchanged.
 *   <li>Every {@code execve} call succeeds but takes at least {@link #delayMs} ms longer than
 *       without the rule; the spawned process starts correctly but with a delayed launch.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>All {@code execve} calls are delayed by at least {@link #delayMs} ms; no errors are
 *       injected, so error-handling code is not exercised — only timing-dependent code paths are
 *       stressed.
 *   <li>Applications that spawn helper subprocesses on the critical request path (e.g. invoking a
 *       shell script, an external tool, or a sidecar binary synchronously) will observe request
 *       latency increase by at least {@link #delayMs} ms for every exec on the hot path; assert
 *       that request SLOs remain within bounds and that exec calls are not on the critical path.
 *   <li>Process-pool managers that pre-spawn worker processes must account for exec latency in
 *       their worker-readiness timeout; assert that the manager does not mark a worker as failed
 *       due to slow exec during pool warm-up under realistic spawn latency.
 * </ul>
 *
 * Production failure mode: a container orchestrator uses exec to deliver secrets to a new process
 * via a credential-provider binary; under node resource pressure, the exec stalls for seconds due
 * to scheduler latency and VFS pressure; the orchestrator's credential-delivery timeout fires and
 * the container fails to start, even though the exec would have eventually succeeded — a
 * latency-induced startup failure with no errno to diagnose.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code execve} latency model simulates the wall-clock cost increase that occurs when the
 * kernel's exec path stalls due to resource pressure: VFS path resolution slowdowns (network
 * filesystem latency), binary loading I/O waits (cold page cache, slow storage), and scheduler
 * delays (CPU contention) all add latency to exec without returning an errno. Applications that
 * assume exec completes quickly and use tight timeouts for subprocess readiness will fail under
 * these conditions.
 *
 * <p>The latency primitive is particularly valuable for testing subprocess-based credential and
 * configuration injection patterns: many Kubernetes admission controllers and secret management
 * systems use exec-based delivery (e.g. the AWS EKS token provider, GCP's workload identity
 * credential helper, Vault agent injector). These delivery mechanisms are on the critical startup
 * path and must tolerate exec latency spikes without causing container startup failures. A 200 ms
 * exec latency can push a credential-delivery timeout if the timeout is set to 500 ms — revealing a
 * misconfigured timeout before production deployment.
 *
 * <p>Unlike the fork latency (which stalls at the clone point) or the waitpid latency (which stalls
 * at the harvest point), exec latency affects the interval between the fork and the child becoming
 * runnable in its new image. Applications that use fork+exec+waitpid must budget for exec latency
 * in their end-to-end spawn timeout, not just the fork and wait steps.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveLatency(delayMs = 200)
 * class ExecveLatencyTest {
 *   @Test
 *   void workerSpawnTimeoutAccountsForExecLatencyUnderNodePressure(ConnectionInfo info) {
 *     // verify pool warm-up timeout is above 200ms; exec not on request critical path
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 50–200 ms mirrors realistic VFS and scheduler stall events
 * during node resource pressure; values above 1000 ms will exceed most subprocess-readiness
 * timeouts and may prevent container startup if exec is on the init path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosExecveLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.EXECVE)
public @interface ChaosExecveLatency {

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
   * @ChaosExecveLatency(id = "primary",  probability = 0.001)
   * @ChaosExecveLatency(id = "replica",  probability = 0.01)
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
    ChaosExecveLatency[] value();
  }
}
