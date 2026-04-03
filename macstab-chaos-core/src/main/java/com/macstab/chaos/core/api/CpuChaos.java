/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * CPU-level chaos operations for container-based integration tests.
 *
 * <p>All operations work in any unprivileged Linux container - no {@code --privileged}, no cgroup
 * writes, no additional kernel capabilities required.
 *
 * <h2>What This Models</h2>
 *
 * <table border="1">
 *   <caption>CPU chaos operations by scenario</caption>
 *   <tr><th>Method</th><th>Scenario modelled</th><th>Tool</th></tr>
 *   <tr><td>{@link #throttle}</td><td>Kubernetes CPU limit / noisy-neighbour quota</td><td>cpulimit</td></tr>
 *   <tr><td>{@link #stress(GenericContainer, int)}</td><td>CPU saturation, compute burst</td><td>stress-ng --cpu</td></tr>
 *   <tr><td>{@link #stressCache}</td><td>Cache eviction pressure, JVM GC amplification</td><td>stress-ng --cache</td></tr>
 *   <tr><td>{@link #stressCacheLine}</td><td>False-sharing contention</td><td>stress-ng --cacheline</td></tr>
 *   <tr><td>{@link #stressContextSwitch}</td><td>Project Loom / virtual-thread overhead</td><td>stress-ng --context</td></tr>
 *   <tr><td>{@link #stressThreadSwitch}</td><td>Lock-heavy thread-pool saturation</td><td>stress-ng --switch</td></tr>
 *   <tr><td>{@link #stressBranchPredictor}</td><td>JIT pipeline stalls, misprediction regressions</td><td>stress-ng --branch</td></tr>
 *   <tr><td>{@link #stressTimerInterrupts}</td><td>Interrupt-dense I/O, timer-wheel contention</td><td>stress-ng --hrtimers</td></tr>
 *   <tr><td>{@link #stressMatrix}</td><td>FPU/SIMD workload interference</td><td>stress-ng --matrix</td></tr>
 *   <tr><td>{@link #pinToCoreMask}</td><td>Single-vCPU deployment, taskset starvation</td><td>taskset</td></tr>
 *   <tr><td>{@link #degradePriority}</td><td>Low-priority background-job contention</td><td>renice</td></tr>
 * </table>
 *
 * <h2>Complete Examples</h2>
 *
 * <h3>CPU throttle - Kubernetes quota simulation</h3>
 *
 * <pre>{@code
 * @Test
 * void shouldHandleKubernetesCpuLimit() {
 *   CpuChaos cpu = new CpuChaosProvider();
 *
 *   long baseline = measureLatency(() -> redis.get("key"));
 *   cpu.throttle(redis, 25);  // Simulates pods.spec.resources.limits.cpu=250m
 *
 *   long throttled = measureLatency(() -> redis.get("key"));
 *   assertThat(throttled).isGreaterThan(baseline * 2);
 *
 *   cpu.reset(redis);
 * }
 * }</pre>
 *
 * <h3>Cache pressure - GC pause amplification</h3>
 *
 * <pre>{@code
 * @Test
 * void shouldHandleCachePressureDuringGc() {
 *   cpu.stressCache(app, 2);
 *
 *   // GC pause budget: must stay under SLA even with cold cache
 *   assertThat(measureGcPause()).isLessThan(Duration.ofMillis(50));
 *
 *   cpu.reset(app);
 * }
 * }</pre>
 *
 * <h3>Affinity pinning - single-core regression</h3>
 *
 * <pre>{@code
 * @Test
 * void shouldHandleSingleCorePinning() {
 *   cpu.pinToCoreMask(app, 0x1L);  // 1 core only
 *
 *   assertThat(cpu.isAffinityPinned(app)).isTrue();
 *   assertThat(cpu.getPinnedCoreMask(app)).isEqualTo(0x1L);
 *
 *   // App must handle single-core constraint without deadlock
 *   assertThat(app.processRequest()).isSuccessful();
 *
 *   cpu.reset(app);
 * }
 * }</pre>
 *
 * <h3>Priority degradation - background job starvation</h3>
 *
 * <pre>{@code
 * @Test
 * void shouldHandleLowPriorityUnderLoad() {
 *   cpu.stress(worker, 4);       // Saturate workers
 *   cpu.degradePriority(app, 19); // App has lowest possible priority
 *
 *   // App still processes - just slower
 *   assertThat(app.isAlive()).isTrue();
 *
 *   cpu.reset(worker);
 *   cpu.reset(app);
 * }
 * }</pre>
 *
 * <h2>Tool Requirements</h2>
 *
 * <ul>
 *   <li>{@code stress-ng} - {@code apt install stress-ng}
 *   <li>{@code cpulimit} - {@code apt install cpulimit}
 *   <li>{@code taskset} - {@code apt install util-linux} (present in most base images)
 *   <li>{@code renice} - {@code util-linux} / {@code bsdutils} (always present)
 * </ul>
 *
 * <p>{@link #installTools(GenericContainer)} auto-installs all of these.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosProvider
 */
public interface CpuChaos extends ChaosProvider {

  // ==================== Throttling ====================

  /**
   * Throttle container CPU to {@code percentage} of one core via {@code cpulimit -p 1}.
   *
   * <p>Throttle is permanent until {@link #reset(GenericContainer)} is called. Use {@link
   * #throttle(GenericContainer, int, Duration)} for auto-releasing throttle.
   *
   * <p><strong>Models:</strong> Kubernetes {@code resources.limits.cpu} / noisy-neighbour quota.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.throttle(redis, 25);  // Equivalent to 250m CPU limit
   *
   * long latency = measureLatency(() -> redis.get("key"));
   * assertThat(latency).isGreaterThan(50);  // ~4x baseline
   *
   * cpu.reset(redis);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param percentage CPU percentage cap for PID 1 (1–100)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code percentage} not in [1, 100]
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if cpulimit fails to start
   */
  void throttle(GenericContainer<?> container, int percentage);

  /**
   * Throttle CPU to {@code percentage} for {@code duration}, then auto-release.
   *
   * <p>The release timer runs entirely inside the container via a background shell subshell - no
   * Java thread or scheduler is involved. Returns immediately.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.throttle(redis, 50, Duration.ofSeconds(5));
   *
   * Thread.sleep(2_000);
   * assertThat(cpu.isThrottled(redis)).isTrue();
   *
   * Thread.sleep(5_000);
   * assertThat(cpu.isThrottled(redis)).isFalse();  // auto-released
   * }</pre>
   *
   * @param container target container (must be running)
   * @param percentage CPU percentage cap (1–100)
   * @param duration throttle duration (must be &gt; 0)
   * @throws NullPointerException if {@code container} or {@code duration} is null
   * @throws IllegalArgumentException if percentage not in [1, 100] or duration ≤ 0
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if cpulimit fails to start
   */
  void throttle(GenericContainer<?> container, int percentage, Duration duration);

  // ==================== CPU Compute Stress ====================

  /**
   * Spawn {@code workers} CPU compute stress processes that run until {@link #reset} is called.
   *
   * <p>Each worker executes a tight compute loop consuming 100% of one CPU core. Processes are
   * started in the background with {@code stress-ng --cpu <workers> --timeout 0}.
   *
   * <p><strong>Models:</strong> CPU saturation, compute burst, thread-pool exhaustion.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * int cores = cpu.getAvailableCores(app);
   * cpu.stress(app, cores);  // Full saturation
   *
   * assertThat(cpu.getCurrentUsage(app))
   *     .isGreaterThan(70);
   *
   * cpu.reset(app);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of stress worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stress(GenericContainer<?> container, int workers);

  /**
   * Spawn {@code workers} CPU compute stress processes that auto-terminate after {@code duration}.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stress(app, 2, Duration.ofSeconds(10));
   *
   * // Verify degraded-but-alive during stress
   * assertThat(app.processRequest()).isSuccessful();
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of stress worker processes (must be ≥ 1)
   * @param duration stress duration (must be &gt; 0)
   * @throws NullPointerException if {@code container} or {@code duration} is null
   * @throws IllegalArgumentException if workers &lt; 1 or duration ≤ 0
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stress(GenericContainer<?> container, int workers, Duration duration);

  /**
   * Spawn CPU stress workers capped at {@code percentage} CPU via {@code cpulimit}.
   *
   * <p>Starts {@code stress-ng --cpu <workers>} and immediately applies {@code cpulimit} to the
   * stress-ng parent PID. Models Kubernetes pod-level CPU limits under active compute load.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stressWithThrottle(redis, 2, 50);
   *
   * assertThat(cpu.isStressed(redis)).isTrue();
   * assertThat(cpu.isThrottled(redis)).isTrue();
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of stress-ng worker processes (must be ≥ 1)
   * @param percentage CPU cap applied via cpulimit (1–100)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if workers &lt; 1 or percentage not in [1, 100]
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng or cpulimit fail to start
   */
  void stressWithThrottle(GenericContainer<?> container, int workers, int percentage);

  // ==================== Cache Stress ====================

  /**
   * Inject L1/L2/L3 cache eviction pressure via {@code stress-ng --cache}.
   *
   * <p>Workers perform random read/write patterns across memory regions sized to exceed cache
   * capacity, causing sustained cache-line evictions at all levels.
   *
   * <p><strong>Models:</strong> Redis key-scan latency spikes, JVM GC pause amplification under
   * cache pressure, microservice response-time degradation from shared LLC contention.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stressCache(redis, 2);
   *
   * // Verify Redis pipeline latency budget holds under cache pressure
   * assertThat(measurePipelineLatency()).isLessThan(Duration.ofMillis(20));
   *
   * cpu.reset(redis);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of cache stress worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressCache(GenericContainer<?> container, int workers);

  /**
   * Inject cache eviction pressure that auto-terminates after {@code duration}.
   *
   * @param container target container (must be running)
   * @param workers number of cache stress worker processes (must be ≥ 1)
   * @param duration stress duration (must be &gt; 0)
   * @throws NullPointerException if {@code container} or {@code duration} is null
   * @throws IllegalArgumentException if workers &lt; 1 or duration ≤ 0
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressCache(GenericContainer<?> container, int workers, Duration duration);

  /**
   * Inject CPU cache-line false-sharing contention via {@code stress-ng --cacheline}.
   *
   * <p>Workers hammer adjacent cache lines within a shared cache page to trigger CPU cache
   * coherence traffic between cores. Models concurrent access to adjacent fields in shared data
   * structures - the classic false-sharing scenario.
   *
   * <p><strong>Models:</strong> {@code @Contended}-sensitive code paths, concurrent counter arrays,
   * ring-buffer head/tail contention.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Verify Disruptor ring-buffer throughput under cache-line contention
   * cpu.stressCacheLine(app, 4);
   * assertThat(measureThroughput()).isGreaterThan(MIN_OPS_PER_SEC);
   * cpu.reset(app);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of cacheline worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressCacheLine(GenericContainer<?> container, int workers);

  // ==================== Scheduler / Context Switch Stress ====================

  /**
   * Flood user-space co-routine context switches via {@code stress-ng --context}.
   *
   * <p>Each worker rapidly switches between two user-space execution contexts using POSIX {@code
   * swapcontext()}, stressing the TLB and the kernel scheduler under virtual-thread-heavy
   * workloads.
   *
   * <p><strong>Models:</strong> Project Loom virtual-thread overhead, coroutine library performance
   * under scheduler saturation.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stressContextSwitch(app, 4);
   *
   * // Verify virtual thread pool stays responsive
   * assertThat(app.submitTask()).completesWithin(Duration.ofSeconds(1));
   *
   * cpu.reset(app);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of context-switch worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressContextSwitch(GenericContainer<?> container, int workers);

  /**
   * Flood kernel thread context switches via pipe-synchronized yield pairs ({@code stress-ng
   * --switch}).
   *
   * <p>Worker pairs ping-pong across a pipe to maximise scheduler invocations per second. Models
   * highly concurrent, lock-heavy thread pools where context switches dominate CPU time.
   *
   * <p><strong>Models:</strong> Thread-pool scheduler saturation, lock-convoy scenarios, wake-up
   * latency under scheduler pressure.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stressThreadSwitch(app, 8);
   *
   * // Thread pool must not degrade below latency SLA
   * assertThat(measureP99Latency()).isLessThan(Duration.ofMillis(100));
   *
   * cpu.reset(app);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of switch worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressThreadSwitch(GenericContainer<?> container, int workers);

  // ==================== Pipeline / Interrupt Stress ====================

  /**
   * Inject CPU branch-predictor misprediction stalls via {@code stress-ng --branch}.
   *
   * <p>Forces the CPU branch predictor to mispredict on every iteration, flushing the instruction
   * pipeline. Highly architecture-sensitive - results vary significantly between
   * microarchitectures.
   *
   * <p><strong>Models:</strong> JIT hotspot pipeline stalls, decision-tree traversal on
   * unpredictable data, V8/JVM JIT regression detection under prediction pressure.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stressBranchPredictor(app, 2);
   *
   * // JVM JIT code must not regress beyond 30% throughput degradation
   * assertThat(measureThroughput())
   *     .isGreaterThan(baseline * 0.7);
   *
   * cpu.reset(app);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of branch worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressBranchPredictor(GenericContainer<?> container, int workers);

  /**
   * Flood the kernel with high-resolution timer interrupts via {@code stress-ng --hrtimers}.
   *
   * <p>Arms and cancels a high volume of {@code CLOCK_REALTIME} high-resolution timers per second,
   * driving interrupt handler overhead and timer-wheel contention.
   *
   * <p><strong>Models:</strong> Interrupt-dense I/O workloads, NIO selector tight-loop overhead,
   * timer-wheel exhaustion in Netty/Vert.x event loops.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stressTimerInterrupts(nettyApp, 4);
   *
   * // Event loop must drain within deadline under interrupt pressure
   * assertThat(measureEventLoopLag()).isLessThan(Duration.ofMillis(5));
   *
   * cpu.reset(nettyApp);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of hrtimer worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressTimerInterrupts(GenericContainer<?> container, int workers);

  /**
   * Stress FPU/SIMD pipelines via floating-point matrix operations ({@code stress-ng --matrix}).
   *
   * <p>Performs dense floating-point matrix multiplications, exercising SIMD units
   * (SSE4.2/AVX2/NEON) and the FPU pipeline. Causes thermal throttling under sustained load on real
   * hardware.
   *
   * <p><strong>Models:</strong> Numerical/ML inference workload interference, SIMD regression
   * testing, codec / signal-processing co-location degradation.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stressMatrix(inferenceApp, 2);
   *
   * // Inference SLA must hold under SIMD competition
   * assertThat(runInference()).completesWithin(Duration.ofMillis(200));
   *
   * cpu.reset(inferenceApp);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param workers number of matrix worker processes (must be ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code workers} &lt; 1
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressMatrix(GenericContainer<?> container, int workers);

  /**
   * Stress FPU/SIMD pipelines that auto-terminate after {@code duration}.
   *
   * @param container target container (must be running)
   * @param workers number of matrix worker processes (must be ≥ 1)
   * @param duration stress duration (must be &gt; 0)
   * @throws NullPointerException if {@code container} or {@code duration} is null
   * @throws IllegalArgumentException if workers &lt; 1 or duration ≤ 0
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if stress-ng fails to start
   */
  void stressMatrix(GenericContainer<?> container, int workers, Duration duration);

  // ==================== CPU Affinity ====================

  /**
   * Pin PID 1 to the CPU set represented by {@code affinityMask} via {@code taskset}.
   *
   * <p>Restricts the container's main process to a subset of available cores. Masks are hex
   * bitmasks where bit N controls CPU N.
   *
   * <p><strong>Common masks:</strong>
   *
   * <pre>
   * 0x1   → CPU 0 only         (single-core simulation)
   * 0x3   → CPUs 0-1           (dual-core simulation)
   * 0xf   → CPUs 0-3           (quad-core)
   * </pre>
   *
   * <p><strong>Models:</strong> Single-vCPU Kubernetes pod, cgroup cpuset constraint, VM with
   * reduced vCPU allocation.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.pinToCoreMask(app, 0x1L);  // Force single-core execution
   *
   * assertThat(cpu.isAffinityPinned(app)).isTrue();
   * assertThat(cpu.getPinnedCoreMask(app)).isEqualTo(0x1L);
   *
   * // Must not deadlock - single-core + thread-pool is a classic hazard
   * assertThat(app.processRequest()).isSuccessful();
   *
   * cpu.reset(app);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param affinityMask CPU bitmask for PID 1 (must be &gt; 0)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code affinityMask} ≤ 0
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if taskset fails
   */
  void pinToCoreMask(GenericContainer<?> container, long affinityMask);

  // ==================== Process Priority ====================

  /**
   * Degrade PID 1's scheduler priority to {@code niceValue} via {@code renice}.
   *
   * <p>Unprivileged containers can only increase the nice value (lower priority): {@code 0}
   * (normal) to {@code +19} (lowest). Negative values require {@code CAP_SYS_NICE} or root.
   *
   * <p><strong>Models:</strong> Background-job CPU starvation, low-priority pod starvation under
   * load, CFS scheduling weight competition.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stress(worker, 4);         // Saturate CPU with higher-priority work
   * cpu.degradePriority(app, 19);  // App is lowest-priority
   *
   * // App must still make forward progress - just slowly
   * assertThat(app.isAlive()).isTrue();
   * assertThat(cpu.getNiceValue(app)).isEqualTo(19);
   *
   * cpu.reset(worker);
   * cpu.reset(app);
   * }</pre>
   *
   * @param container target container (must be running)
   * @param niceValue new nice value for PID 1 (0–19 for unprivileged)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalArgumentException if {@code niceValue} not in [0, 19]
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if renice fails
   */
  void degradePriority(GenericContainer<?> container, int niceValue);

  /**
   * Restore PID 1's nice value to {@code 0} (normal priority).
   *
   * <p><strong>Note:</strong> Requires {@code CAP_SYS_NICE} or root to restore from a non-zero nice
   * value back to 0. In unprivileged containers, this call will fail if the nice value was
   * previously raised. Use {@link #reset(GenericContainer)} for full cleanup instead - it will
   * attempt restore and silently ignore failures.
   *
   * @param container target container (must be running)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if renice fails and container lacks capabilities
   */
  void resetPriority(GenericContainer<?> container);

  // ==================== Observability ====================

  /**
   * Returns current CPU usage calculated from a two-sample {@code /proc/stat} delta over 500 ms.
   *
   * <p>Returns the overall CPU busy percentage (0–100) summed across all available cores divided by
   * the total available time. Example: 1 worker on a 4-core system yields roughly 25%.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.stress(app, 2);
   * await().atMost(5, SECONDS)
   *        .until(() -> cpu.getCurrentUsage(app) > 40);
   * }</pre>
   *
   * @param container target container (must be running)
   * @return CPU usage percentage (0–100)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if {@code /proc/stat} read fails
   */
  int getCurrentUsage(GenericContainer<?> container);

  /**
   * Returns the number of CPU cores visible to the container via {@code nproc}.
   *
   * <p>Falls back to counting {@code processor} entries in {@code /proc/cpuinfo} when {@code nproc}
   * is unavailable.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * int cores = cpu.getAvailableCores(container);
   * cpu.stress(container, cores);  // Full saturation
   * }</pre>
   *
   * @param container target container (must be running)
   * @return number of available CPU cores (always ≥ 1)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if core count cannot be determined
   */
  int getAvailableCores(GenericContainer<?> container);

  /**
   * Returns {@code true} if {@code cpulimit} is currently running inside the container.
   *
   * <p>Returns {@code false} - rather than throwing - if the container is not running.
   *
   * @param container target container
   * @return {@code true} if cpulimit is active
   * @throws NullPointerException if {@code container} is null
   */
  boolean isThrottled(GenericContainer<?> container);

  /**
   * Returns {@code true} if any {@code stress-ng} process (parent or worker) is currently running
   * inside the container.
   *
   * <p>Uses {@code /proc/comm} prefix match - detects {@code stress-ng}, {@code stress-ng-cpu},
   * {@code stress-ng-cache}, {@code stress-ng-cacheline}, and all other stressor worker names.
   *
   * <p>Returns {@code false} - rather than throwing - if the container is not running.
   *
   * @param container target container
   * @return {@code true} if any stress-ng process is active
   * @throws NullPointerException if {@code container} is null
   */
  boolean isStressed(GenericContainer<?> container);

  /**
   * Returns {@code true} if PID 1's CPU affinity is restricted to fewer cores than available.
   *
   * <p>Reads the affinity mask via {@code taskset -p 1} and compares the number of set bits against
   * the total available core count. Returns {@code false} if the container is not running.
   *
   * @param container target container
   * @return {@code true} if affinity is pinned to a strict subset of available cores
   * @throws NullPointerException if {@code container} is null
   */
  boolean isAffinityPinned(GenericContainer<?> container);

  /**
   * Returns PID 1's current CPU affinity mask as a bitmask.
   *
   * <p>Reads the mask via {@code taskset -p 1}. A value with all bits set (e.g., {@code 0xfff} on a
   * 12-vCPU system) means unrestricted.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.pinToCoreMask(app, 0x1L);
   * assertThat(cpu.getPinnedCoreMask(app)).isEqualTo(0x1L);
   * }</pre>
   *
   * @param container target container (must be running)
   * @return CPU affinity bitmask for PID 1
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if taskset read fails
   */
  long getPinnedCoreMask(GenericContainer<?> container);

  /**
   * Returns the current nice value of PID 1.
   *
   * <p>Reads field 19 from {@code /proc/1/stat}. Values range from {@code -20} (highest priority,
   * requires root) to {@code +19} (lowest priority).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * cpu.degradePriority(app, 15);
   * assertThat(cpu.getNiceValue(app)).isEqualTo(15);
   * }</pre>
   *
   * @param container target container (must be running)
   * @return nice value of PID 1
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if container is not running
   * @throws ChaosOperationFailedException if {@code /proc/1/stat} read fails
   */
  int getNiceValue(GenericContainer<?> container);
}
