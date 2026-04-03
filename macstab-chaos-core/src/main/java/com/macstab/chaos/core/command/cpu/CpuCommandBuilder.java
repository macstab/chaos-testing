/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.cpu;

/**
 * Platform-specific CPU chaos command builder.
 *
 * <p>Builds shell command strings for CPU stress injection, throttling, affinity pinning, and
 * priority control. All methods return pure shell command strings - no I/O, no execution. Command
 * construction is completely separated from execution.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@code StressNgCommandBuilder} - {@code stress-ng} + {@code cpulimit} + {@code taskset} +
 *       {@code renice}; works in any unprivileged Linux container without extra capabilities.
 * </ul>
 *
 * <p><strong>Process detection strategy:</strong>
 *
 * <p>All process lifecycle commands use {@code /proc/comm} exclusively - no dependency on
 * {@code pgrep}, {@code pkill}, or {@code ps}, which may be absent in minimal container images
 * (e.g., {@code redis:7.4}).
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.cpu.command.StressNgCommandBuilder
 */
public interface CpuCommandBuilder {

  // ==================== Process Lifecycle (via /proc/comm) ====================

  /**
   * Build command to find the lowest PID of a process by its exact {@code comm} name.
   *
   * <p>Scans {@code /proc/[0-9]* /comm} for an exact name match and outputs the first (lowest)
   * numeric PID found. The lowest PID corresponds to the parent process when multiple workers are
   * running (e.g., {@code stress-ng} parent + worker children).
   *
   * <p><strong>Output:</strong> Single decimal PID line, or empty string if not found.
   *
   * <p><strong>Example (stress-ng parent):</strong>
   *
   * <pre>
   * buildFindLowestPidByCommCommand("stress-ng")
   * → "for pid in /proc/[0-9]* /comm; do ..."
   * → stdout: "743"
   * </pre>
   *
   * @param exactCommName exact {@code comm} name to match (e.g., {@code "stress-ng"},
   *     {@code "cpulimit"})
   * @return shell command string
   */
  String buildFindLowestPidByCommCommand(String exactCommName);

  /**
   * Build command to test whether any process with exactly the given {@code comm} name is running.
   *
   * <p>Exits {@code 0} if at least one match exists, non-zero otherwise.
   *
   * <p><strong>Example (check cpulimit):</strong>
   *
   * <pre>
   * buildIsRunningByCommExactCommand("cpulimit")
   * → exits 0 if cpulimit is running, 1 if not
   * </pre>
   *
   * @param exactCommName exact {@code comm} name to match
   * @return shell command string
   */
  String buildIsRunningByCommExactCommand(String exactCommName);

  /**
   * Build command to test whether any process whose {@code comm} name starts with the given prefix
   * is currently running.
   *
   * <p>Exits {@code 0} if at least one match exists, non-zero otherwise.
   *
   * <p><strong>Use case:</strong> Detects any stress-ng stressor. Worker processes rename
   * themselves to {@code "stress-ng-cpu"}, {@code "stress-ng-cache"}, etc. - a prefix check covers
   * all variants.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildIsRunningByCommPrefixCommand("stress-ng")
   * → matches "stress-ng", "stress-ng-cpu", "stress-ng-cache", "stress-ng-cacheline", …
   * </pre>
   *
   * @param commPrefix {@code comm} name prefix (e.g., {@code "stress-ng"})
   * @return shell command string
   */
  String buildIsRunningByCommPrefixCommand(String commPrefix);

  /**
   * Build command to send {@code SIGKILL (-9)} to all processes matching the exact {@code comm}
   * name.
   *
   * <p>Iterates all matching {@code /proc/[0-9]* /comm} entries and kills every match. Safe to
   * call when no matching process exists - exits {@code 0} in all cases.
   *
   * <p><strong>Use case:</strong> Kill all {@code cpulimit} instances (typically only one, but
   * defensive).
   *
   * @param exactCommName exact {@code comm} name to match
   * @return shell command string
   */
  String buildKillAllByCommSigKillCommand(String exactCommName);

  /**
   * Build command to send SIGKILL to all processes whose comm name starts with {@code commPrefix}.
   *
   * <p>Covers parent + all worker variants (e.g., stress-ng, stress-ng-cpu, stress-ng-cache).
   *
   * @param commPrefix comm name prefix to match
   * @return shell command string
   */
  String buildKillAllByCommPrefixSigKillCommand(String commPrefix);

  /**
   * Build command to send {@code SIGTERM (-15)} to the lowest-PID process matching the exact
   * {@code comm} name.
   *
   * <p>Only the parent process (lowest PID) is signalled. Worker children are expected to
   * terminate as a result of parent-coordinated shutdown. Safe to call when no matching process
   * exists.
   *
   * <p><strong>Use case:</strong> Gracefully stop {@code stress-ng} - SIGTERM allows the parent to
   * clean up worker PIDs before exiting. SIGKILL on workers causes them to be respawned by the
   * parent.
   *
   * @param exactCommName exact {@code comm} name to match
   * @return shell command string
   */
  String buildKillParentByCommSigTermCommand(String exactCommName);

  // ==================== stress-ng Stressor Commands ====================

  /**
   * Build command to start CPU compute stress workers that run until explicitly killed.
   *
   * <p>Runs {@code stress-ng --cpu <workers> --timeout 0} in the background. Each worker executes
   * a tight compute loop, consuming 100% of one CPU core.
   *
   * <p><strong>Use cases:</strong> CPU saturation, noisy-neighbor simulation, thread-pool
   * exhaustion testing.
   *
   * @param workers number of CPU worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressCpuCommand(int workers);

  /**
   * Build command to start CPU compute stress workers that auto-terminate after {@code seconds}.
   *
   * @param workers number of CPU worker processes (must be ≥ 1)
   * @param seconds stress duration in seconds (must be ≥ 1)
   * @return shell command string
   */
  String buildStressCpuWithTimeoutCommand(int workers, long seconds);

  /**
   * Build command to start L1/L2/L3 cache-pressure workers that run until explicitly killed.
   *
   * <p>Runs {@code stress-ng --cache <workers>} - exercises all cache levels with mixed read/write
   * patterns, causing frequent cache-line evictions.
   *
   * <p><strong>Use cases:</strong> JVM GC pause amplification, Redis key-scan latency spikes,
   * cache-sensitive microservice degradation.
   *
   * @param workers number of cache worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressCacheCommand(int workers);

  /**
   * Build command to start cache-pressure workers that auto-terminate after {@code seconds}.
   *
   * @param workers number of cache worker processes (must be ≥ 1)
   * @param seconds stress duration in seconds (must be ≥ 1)
   * @return shell command string
   */
  String buildStressCacheWithTimeoutCommand(int workers, long seconds);

  /**
   * Build command to start cache-line false-sharing contention workers.
   *
   * <p>Runs {@code stress-ng --cacheline <workers>} - each worker hammers adjacent cache lines in
   * a shared cache page to trigger CPU coherence traffic. Models concurrent multi-threaded
   * false-sharing scenarios at the hardware level.
   *
   * <p><strong>Use cases:</strong> False-sharing regressions in concurrent data structures, JVM
   * object layout analysis under cache coherence pressure.
   *
   * @param workers number of cacheline worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressCacheLineCommand(int workers);

  /**
   * Build command to flood user-space co-routine context switches.
   *
   * <p>Runs {@code stress-ng --context <workers>} - rapidly switches between two user-space
   * execution contexts using POSIX {@code swapcontext()}. Stresses the kernel scheduler and TLB
   * under co-routine-heavy workloads.
   *
   * <p><strong>Use cases:</strong> Virtual-thread / Project Loom testing, co-routine library
   * overhead under load.
   *
   * @param workers number of context-switch worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressContextSwitchCommand(int workers);

  /**
   * Build command to flood kernel thread context switches via pipe-synchronized yield pairs.
   *
   * <p>Runs {@code stress-ng --switch <workers>} - pairs of threads ping-pong across a pipe to
   * maximise scheduler invocations and kernel scheduling overhead. Models lock-heavy, highly
   * concurrent multi-threaded applications.
   *
   * <p><strong>Use cases:</strong> Thread-pool scheduler saturation, lock contention amplification,
   * wake-up latency testing.
   *
   * @param workers number of switch worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressThreadSwitchCommand(int workers);

  /**
   * Build command to inject CPU branch-predictor misprediction stalls.
   *
   * <p>Runs {@code stress-ng --branch <workers>} - forces the CPU branch predictor to mispredict
   * repeatedly, flushing the instruction pipeline on each iteration. Highly architecture-sensitive.
   *
   * <p><strong>Use cases:</strong> JIT-compiled hotspot regressions (JVM, V8), CPU pipeline stall
   * simulation, performance-sensitive decision-tree traversal testing.
   *
   * @param workers number of branch worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressBranchPredictorCommand(int workers);

  /**
   * Build command to flood the kernel with high-resolution timer interrupts.
   *
   * <p>Runs {@code stress-ng --hrtimers <workers>} - arms and cancels a high volume of
   * {@code CLOCK_REALTIME} high-resolution timers per second, driving interrupt overhead.
   *
   * <p><strong>Use cases:</strong> Interrupt-dense I/O simulation, timer wheel contention,
   * {@code SCHED_DEADLINE} interference testing.
   *
   * @param workers number of hrtimer worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressTimerInterruptsCommand(int workers);

  /**
   * Build command to stress FPU/SIMD pipelines via floating-point matrix operations.
   *
   * <p>Runs {@code stress-ng --matrix <workers>} - performs dense floating-point matrix
   * multiplications, exercising SIMD units (SSE/AVX/NEON) and the FPU pipeline.
   *
   * <p><strong>Use cases:</strong> Numerical/ML workload interference, SIMD regression testing,
   * FPU-heavy codec / signal-processing degradation.
   *
   * @param workers number of matrix worker processes (must be ≥ 1)
   * @return shell command string
   */
  String buildStressMatrixCommand(int workers);

  /**
   * Build command to stress FPU/SIMD pipelines that auto-terminate after {@code seconds}.
   *
   * @param workers number of matrix worker processes (must be ≥ 1)
   * @param seconds stress duration in seconds (must be ≥ 1)
   * @return shell command string
   */
  String buildStressMatrixWithTimeoutCommand(int workers, long seconds);

  // ==================== cpulimit Commands ====================

  /**
   * Build command to throttle a process to {@code percentage} of one CPU core via
   * {@code cpulimit}.
   *
   * <p>Runs {@code cpulimit -l <percentage> -p <pid>} in the background. cpulimit enforces the
   * limit by periodically sending {@code SIGSTOP}/{@code SIGCONT} to the target process.
   *
   * <p><strong>Note:</strong> cpulimit is a userspace approximation. For kernel-enforced hard
   * quotas, cgroups v2 {@code cpu.max} is required (needs {@code --privileged}).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildThrottleCommand(1, 50)
   * → "cpulimit -l 50 -p 1 >/dev/null 2>&1 &"
   * </pre>
   *
   * @param pid target process ID (must be ≥ 1)
   * @param percentage CPU percentage cap (1–100)
   * @return shell command string
   */
  String buildThrottleCommand(int pid, int percentage);

  /**
   * Build command to throttle a process for {@code seconds}, then auto-release.
   *
   * <p>Starts {@code cpulimit} in a background subshell, captures its PID, sleeps
   * {@code seconds}, then kills cpulimit. The release is container-internal - no Java-side
   * scheduler or thread is involved.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildThrottleWithDurationCommand(1, 50, 5)
   * → "(cpulimit -l 50 -p 1 >/dev/null 2>&1 & CPID=$!; sleep 5; kill $CPID 2>/dev/null) &"
   * </pre>
   *
   * @param pid target process ID (must be ≥ 1)
   * @param percentage CPU percentage cap (1–100)
   * @param seconds throttle duration in seconds (must be ≥ 1)
   * @return shell command string
   */
  String buildThrottleWithDurationCommand(int pid, int percentage, long seconds);

  // ==================== taskset Commands ====================

  /**
   * Build command to pin a process to the CPUs represented by {@code affinityMask} via
   * {@code taskset}.
   *
   * <p>Applies the affinity mask to an already-running process. Mask bits correspond to CPU
   * indices: bit 0 = CPU 0, bit 1 = CPU 1, etc.
   *
   * <p><strong>Example masks:</strong>
   *
   * <pre>
   * 0x1   → CPU 0 only     (single-core simulation)
   * 0x3   → CPUs 0-1       (dual-core simulation)
   * 0xf   → CPUs 0-3       (quad-core)
   * 0xfff → CPUs 0-11      (12-core - "allow all" on a 12-vCPU system)
   * </pre>
   *
   * @param pid target process ID (must be ≥ 1)
   * @param affinityMask bitmask of allowed CPUs (must be &gt; 0)
   * @return shell command string
   */
  String buildPinToMaskCommand(int pid, long affinityMask);

  /**
   * Build command to read the current CPU affinity mask of a process.
   *
   * <p>Runs {@code taskset -p <pid>}. Output format:
   * {@code "pid <N>'s current affinity mask: <hexmask>"}.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildGetAffinityMaskCommand(1)
   * → "taskset -p 1"
   * → stdout: "pid 1's current affinity mask: fff"
   * </pre>
   *
   * @param pid target process ID (must be ≥ 1)
   * @return shell command string
   */
  String buildGetAffinityMaskCommand(int pid);

  // ==================== System Info Commands ====================

  /**
   * Build command to retrieve the number of CPU cores visible to the process via {@code nproc}.
   *
   * <p><strong>Output:</strong> Single decimal integer line.
   *
   * @return shell command string
   */
  String buildGetCoreCountCommand();

  /**
   * Build fallback command to count CPU cores via {@code /proc/cpuinfo} when {@code nproc} is
   * unavailable.
   *
   * <p>Counts lines starting with {@code "processor"} - works on x86, ARM, RISC-V, and all other
   * Linux-supported architectures.
   *
   * <p><strong>Output:</strong> Single decimal integer line.
   *
   * @return shell command string
   */
  String buildGetCoreCountFallbackCommand();

  /**
   * Build command to read the full aggregate CPU tick counters from {@code /proc/stat}.
   *
   * <p>The first line of the output is the aggregate {@code "cpu"} line used for two-sample delta
   * CPU usage calculation.
   *
   * <p><strong>Output:</strong> Full contents of {@code /proc/stat}.
   *
   * @return shell command string
   */
  String buildReadCpuStatCommand();

  // ==================== renice Commands ====================

  /**
   * Build command to set the nice value of a process via {@code renice}.
   *
   * <p>Unprivileged containers may only increase the nice value (lower priority), i.e., values
   * {@code 0} to {@code +19}. Negative values (higher priority) require {@code CAP_SYS_NICE} or
   * root.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildSetNiceValueCommand(1, 19)
   * → "renice 19 -p 1"
   * → "1 (process ID) old priority 0, new priority 19"
   * </pre>
   *
   * @param pid target process ID (must be ≥ 1)
   * @param niceValue new nice value (0–19 for unprivileged; negative values require root)
   * @return shell command string
   */
  String buildSetNiceValueCommand(int pid, int niceValue);

  /**
   * Build command to read the current nice value of a process from {@code /proc/pid/stat}.
   *
   * <p>Reads field 19 (1-indexed) from {@code /proc/<pid>/stat} - the kernel-reported nice value.
   * This is the canonical, guaranteed-present source for nice values on any Linux version.
   *
   * <p><strong>Output:</strong> Single decimal integer line (e.g., {@code "0"} or {@code "19"}).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * buildGetNiceValueCommand(1)
   * → "awk '{print $19}' /proc/1/stat"
   * → stdout: "0"
   * </pre>
   *
   * @param pid target process ID (must be ≥ 1)
   * @return shell command string
   */
  String buildGetNiceValueCommand(int pid);
}
