/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.posix_spawnp;

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
 * Adds a fixed delay of {@link #delayMs} milliseconds before each {@code posix_spawnp} call
 * intercepted by libchaos-process, causing the spawn to succeed but take longer than expected,
 * exercising timeout budgets and latency assumptions in code that invokes processes by {@code $PATH}
 * lookup.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code POSIX_SPAWNP}, effect = {@code LATENCY})
 * tuple. Unlike errno annotations, the latency primitive always delegates to the real kernel after
 * the delay — no child creation is suppressed, no errno is injected. Compile-time safety: invalid
 * selector/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code posix_spawnp} wrapper at the dynamic-linker level.</li>
 *   <li>On each {@code posix_spawnp} call the interposer sleeps for {@link #delayMs} milliseconds
 *       before issuing the real kernel call.</li>
 *   <li>The real {@code posix_spawnp} executes normally after the delay: PATH search runs, the
 *       binary is found, and the child process is created.</li>
 *   <li>The calling code receives a normal return value and a valid pid, but the elapsed wall-clock
 *       time is increased by {@link #delayMs} ms on every spawn call.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>Each {@code posix_spawnp} call succeeds but takes at least {@link #delayMs} ms; assert
 *       that the application's spawn-initiation timeout budget accounts for scheduling stall cost
 *       beyond the nominal PATH-search and fork latency — applications that set spawn timeouts
 *       based only on the nominal case will fail under realistic scheduler contention.</li>
 *   <li>Applications that invoke helper utilities in a pipeline sequence (each stage spawned via
 *       spawnp) accumulate latency: N stages × {@link #delayMs} ms; assert that the pipeline's
 *       end-to-end timeout budget is derived from the worst-case per-stage latency, not the
 *       average; also assert that concurrent spawns do not open PATH-traversal directory fds for
 *       the duration of the delay, leaking fds held open across overlapping delayed spawns.</li>
 *   <li>Assert that the application's child-readiness detection (typically poll on a pipe or
 *       socket) uses wall-clock elapsed time from the moment the spawn call returns, not from the
 *       moment the spawn call is issued — the delay occurs before the fork, so the child does not
 *       start executing until after the full delay elapses.</li>
 * </ul>
 * Production failure mode: a build system invokes compiler and linker tools by name via
 * {@code posix_spawnp}; under heavy node load the kernel scheduler delays fork calls by tens of
 * milliseconds per invocation; a build with 500 tool invocations accumulates 10+ seconds of
 * scheduling stall not counted in the individual tool timeouts; the build system times out not
 * because any individual tool is slow, but because the aggregate scheduling delay exceeds the
 * build-level wall-clock budget.
 *
 * <h2>Deep technical dive</h2>
 * <p>{@code posix_spawnp} latency in production originates from three sources that this annotation
 * models in aggregate: (1) the {@code $PATH} directory traversal — glibc opens each PATH directory
 * with {@code opendir} and calls {@code stat} on candidate filenames; under cold page-cache
 * conditions or slow storage this adds tens of milliseconds per directory; (2) the kernel fork
 * call — CoW page-table duplication cost scales with the parent's virtual memory footprint and is
 * non-trivial for JVM-based parents; (3) the exec call — dynamic linker startup and security
 * module checks add overhead proportional to the binary's import table size. The {@link #delayMs}
 * delay is inserted before the real call, blocking the calling thread and modelling the combined
 * scheduling stall.
 *
 * <p>The delay fires on every {@code posix_spawnp} call, not at a configurable probability. This
 * makes latency injection deterministic: tests do not need to account for the fraction of calls
 * that are slow versus fast. Concurrent spawns in multi-threaded applications each accumulate the
 * full delay independently; if the thread pool spawning utilities runs N threads, the effective
 * throughput is capped at {@code 1000 / delayMs} spawns per second per thread.
 *
 * <p>The PATH traversal in posix_spawnp opens directory fds during the search. Under the latency
 * delay, if another thread calls posix_spawnp concurrently and the PATH traversal runs during the
 * first thread's sleep, the concurrent traversal may hold additional directory fds open briefly.
 * Applications with high spawn concurrency and a strict RLIMIT_NOFILE should account for this
 * transient fd accumulation when calibrating their fd headroom.
 *
 * <p>Unlike errno injection, the latency primitive allows the child process to start and run
 * normally. Tests using this annotation can assert on both the latency overhead (spawn call
 * duration) and on correct child process behaviour (output, exit code). This makes latency
 * injection useful for verifying that timeout handling paths do not skip post-spawn setup steps
 * (pipe creation, pid registration) even when the spawn itself is slow.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosPosixSpawnpLatency(delayMs = 150)
 * class PosixSpawnpSchedulingStallTest {
 *   @Test
 *   void pipelineTimeoutBudgetAccountsForPerStageSpawnLatency(ConnectionInfo info) {
 *     // verify pipeline end-to-end timeout >= N * delayMs; no fd leak during concurrent spawns;
 *     // child-readiness timer starts from spawn return, not from spawn issue
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Delay guidance:</strong> 50–200 ms simulates realistic kernel scheduler stall under
 * node load; values larger than the application's per-spawn timeout will cause all spawns to
 * appear timed out, which may be intentional for testing timeout escalation paths.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessLatencyBinding
 * @see com.macstab.chaos.process.model.ProcessRule#latency(ProcessSelector, java.time.Duration)
 */
@Repeatable(ChaosPosixSpawnpLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessLatencyTranslator")
@ProcessLatencyBinding(selector = ProcessSelector.POSIX_SPAWNP)
public @interface ChaosPosixSpawnpLatency {

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
   * @ChaosPosixSpawnpLatency(id = "primary",  probability = 0.001)
   * @ChaosPosixSpawnpLatency(id = "replica",  probability = 0.01)
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
    ChaosPosixSpawnpLatency[] value();
  }
}
