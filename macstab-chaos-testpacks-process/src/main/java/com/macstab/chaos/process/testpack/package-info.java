/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L2 chaos scenario annotations for the process module.
 *
 * <p>This package contains twelve named, pre-tuned {@code @CompositeChaos<X>} annotations covering
 * the canonical process-lifecycle failure modes encountered in cloud-native production systems. Each
 * annotation composes one or more libchaos-process rules via a
 * {@link com.macstab.chaos.core.extension.L2Composer} implementation and ships with
 * industry-canonical documentation, severity classification, and sane defaults.
 *
 * <h2>Scenario catalogue</h2>
 *
 * <table>
 *   <caption>Process L2 scenarios by severity</caption>
 *   <tr><th>Annotation</th><th>Severity</th><th>What it simulates</th></tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosForkBomb}</td>
 *     <td>CRITICAL</td>
 *     <td>fork() EAGAIN at 95% — process-table saturation post fork-bomb</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosHardKill}</td>
 *     <td>CRITICAL</td>
 *     <td>waitpid() ESRCH — child process not found; PID recycled or double-reaped</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosProcessLimitHit}</td>
 *     <td>SEVERE</td>
 *     <td>fork() EAGAIN at 90% — RLIMIT_NPROC or kernel max_threads exhaustion</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosThreadPoolExhaustion}</td>
 *     <td>SEVERE</td>
 *     <td>pthread_create() EAGAIN at 90% — OS thread-count limit hit</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosOomFork}</td>
 *     <td>SEVERE</td>
 *     <td>fork() ENOMEM at 50% — OOM during process creation</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosExecvePermissionDenied}</td>
 *     <td>SEVERE</td>
 *     <td>execve() EACCES — noexec mount or AppArmor/SELinux policy denial</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosExecveMemoryDenied}</td>
 *     <td>SEVERE</td>
 *     <td>execve() ENOMEM at 50% — OOM during binary image load</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosGracefulShutdown}</td>
 *     <td>MODERATE</td>
 *     <td>waitpid() latency — delayed process cleanup exceeds orchestrator grace period</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosSpawnFailure}</td>
 *     <td>MODERATE</td>
 *     <td>posix_spawn() ENOMEM at 50% — memory-constrained subprocess launch</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosZombieAccumulation}</td>
 *     <td>MODERATE</td>
 *     <td>waitpid() ECHILD at 70% — un-reaped zombie accumulation</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosThreadCreateSlow}</td>
 *     <td>MODERATE</td>
 *     <td>pthread_create() 200ms latency — NUMA or memory-controller-induced creation delay</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.process.testpack.CompositeChaosSignalInterruption}</td>
 *     <td>MILD</td>
 *     <td>waitpid() EINTR at 30% — exercises EINTR retry loops without SA_RESTART</td>
 *   </tr>
 * </table>
 *
 * <h2>Prerequisites</h2>
 *
 * <p>All scenarios in this package require a container prepared with libchaos-process before
 * {@code container.start()}. Annotate the test class with
 * {@code @SyscallLevelChaos(LibchaosLib.PROCESS)}; {@code ChaosTestingExtension} drives
 * preparation automatically.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.process.testpack;
