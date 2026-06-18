/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Two-tier process chaos: cgroups governors / signals plus libchaos-process libc-symbol precision,
 * including the unique {@code FAIL_AFTER} counter for resource-exhaustion testing.
 *
 * <h2>Two backends, one facade</h2>
 *
 * <p>{@link com.macstab.chaos.process.CompositeProcessChaos} aggregates two strategies behind a
 * unified {@link com.macstab.chaos.core.api.ProcessChaos} interface.
 *
 * <table>
 *   <caption>Backend comparison</caption>
 *   <tr>
 *     <th></th>
 *     <th>{@link com.macstab.chaos.process.strategy.cgroups.CgroupsProcessChaos
 *       CgroupsProcessChaos}</th>
 *     <th>{@link com.macstab.chaos.process.strategy.libchaos.LibchaosProcessChaos
 *       LibchaosProcessChaos}</th>
 *   </tr>
 *   <tr>
 *     <td><strong>Mechanism</strong></td>
 *     <td>cgroups v2 {@code pids.max} + {@code kill} / {@code ps} via shell exec</td>
 *     <td>{@code LD_PRELOAD} hook of pthread_create / fork / posix_spawn(p) / execve(at) /
 *         waitpid</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Installation</strong></td>
 *     <td>Lazy — needs procps for ps/pgrep</td>
 *     <td>Pre-start — {@code @SyscallLevelChaos(LibchaosLib.PROCESS)}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Granularity</strong></td>
 *     <td>Whole container — total PID count, signal by name</td>
 *     <td>Per libc-symbol × per errno × per probability OR per FAIL_AFTER counter</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Test capability</strong></td>
 *     <td>"Container can't fork more than N processes", "Send SIGTERM to nginx"</td>
 *     <td>"This specific pthread_create returns EAGAIN", "After 128 forks fail with EAGAIN",
 *         "execve returns ENOENT", "waitpid returns EINTR (bypassing SA_RESTART)"</td>
 *   </tr>
 * </table>
 *
 * <h2>Capability uplift via {@code advanced()}</h2>
 *
 * <p>{@link com.macstab.chaos.process.CompositeProcessChaos#advanced()} returns the
 * libchaos-process strategy as an {@link com.macstab.chaos.process.api.AdvancedProcessChaos}. This
 * exposes ~28 typed verbs grouped by failure class:
 *
 * <ul>
 *   <li><strong>Thread creation</strong> — {@code failThreadCreation}, {@code exhaustThreadPool(N)}
 *       (FAIL_AFTER), {@code slowThreadCreation}
 *   <li><strong>Fork</strong> — {@code failFork}, {@code exhaustProcessLimit(N)} (FAIL_AFTER),
 *       {@code slowFork}
 *   <li><strong>posix_spawn</strong> — {@code failSpawn}, {@code failSpawnByPath}, {@code
 *       slowSpawn}
 *   <li><strong>exec</strong> — {@code failExec}, {@code failExecPermission}, {@code
 *       failExecMissingBinary}, {@code failExecTooLarge}, {@code failExecFdLimit}, {@code
 *       failExecRelative}, {@code slowExec}
 *   <li><strong>wait</strong> — {@code failWait}, {@code signalInterruptWait} (EINTR bypassing
 *       SA_RESTART), {@code phantomWait}, {@code slowWait}
 *   <li><strong>Raw escape hatches</strong> — {@code errno}, {@code latency}, {@code failAfter}
 *   <li><strong>Generic</strong> — {@code apply}, {@code applyAll}, {@code remove}, {@code
 *       removeAll}
 * </ul>
 *
 * <h2>The FAIL_AFTER unique capability</h2>
 *
 * <p>libchaos-process is the only libchaos library with a counter-gated effect. {@code
 * FAIL_AFTER:EAGAIN,128} lets the first 128 matched calls succeed, then fails every subsequent call
 * with {@code EAGAIN}. Atomically counted across threads. This models resource exhaustion
 * (RLIMIT_NPROC, thread-pool limits) without actually configuring kernel limits.
 *
 * <p><strong>Counter reset:</strong> libchaos-process resets the per-rule counter on config reload.
 * Applying a new rule (or re-applying the same rule) resets the budget. For a single-shot
 * exhaustion test, apply the rule, drive the test until the {@code (N+1)}-th call fails, then
 * remove the rule.
 *
 * <h2>Verb-to-backend routing</h2>
 *
 * <table>
 *   <caption>Which strategy handles which verb</caption>
 *   <tr><th>Verb</th><th>libchaos-process</th><th>cgroups</th></tr>
 *   <tr><td>{@code kill} / {@code pause}</td><td>—</td><td>✓ (signals)</td></tr>
 *   <tr><td>{@code limitProcesses}</td><td>—</td><td>✓ (pids.max)</td></tr>
 *   <tr><td>{@code listProcesses}</td><td>—</td><td>✓ (ps)</td></tr>
 *   <tr><td>All advanced verbs (failThreadCreation, exhaustThreadPool, …)</td><td>✓</td><td>—</td></tr>
 * </table>
 *
 * <h2>Probability guidance</h2>
 *
 * <p>Many process rules need a low probability to avoid breaking the container itself — {@code
 * pthread_create:ERRNO:EAGAIN@1.0} prevents the JVM from creating GC threads, {@code
 * execve:ERRNO:ENOENT@1.0} stops every {@code Runtime.exec}, and so on. For sustained chaos use
 * {@code 0.001}–{@code 0.01}; for deterministic single-shot tests use {@code 1.0}. {@code
 * FAIL_AFTER} is generally safe because it gives the runtime time to start up before failures
 * begin.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/PROCESS.md">libchaos-process
 *     technical reference</a>
 */
package com.macstab.chaos.process;
