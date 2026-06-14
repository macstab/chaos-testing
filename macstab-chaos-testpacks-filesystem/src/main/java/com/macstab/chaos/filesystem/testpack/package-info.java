/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L2 chaos scenario annotations for the filesystem module.
 *
 * <p>This package contains ten named, pre-tuned {@code @CompositeChaos<X>} annotations covering the
 * canonical filesystem failure modes encountered in cloud-native production systems. Each
 * annotation composes one or more libchaos-io rules via a {@link
 * com.macstab.chaos.core.extension.L2Composer} implementation and ships with industry-canonical
 * documentation, severity classification, and sane defaults.
 *
 * <h2>Scenario catalogue</h2>
 *
 * <table>
 *   <caption>Filesystem L2 scenarios by severity</caption>
 *   <tr><th>Annotation</th><th>Severity</th><th>What it simulates</th></tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosReadCorruption}</td>
 *     <td>CRITICAL</td>
 *     <td>Single-bit flip on read — DRAM soft-ECC error, NVMe flash-cell wear</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosWriteCorruption}</td>
 *     <td>CRITICAL</td>
 *     <td>Torn write — power-fail mid-sector, NVMe controller reset mid-command</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosDiskFull}</td>
 *     <td>SEVERE</td>
 *     <td>ENOSPC on write — log volume, WAL partition or PVC full</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosReadOnlyFilesystem}</td>
 *     <td>SEVERE</td>
 *     <td>EACCES on write/rename/unlink — kernel remounted read-only after media error</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosEioOnRead}</td>
 *     <td>SEVERE</td>
 *     <td>EIO on read — bad HDD sector, NVMe flash-cell fault, SAN transient</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosFdExhaustion}</td>
 *     <td>SEVERE</td>
 *     <td>EMFILE on open — fd leak, connection-pool overrun, cgroup prlimit</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosWalFsyncDelay}</td>
 *     <td>MODERATE</td>
 *     <td>fsync latency — EBS burst-credit exhaustion, NVMe queue saturation</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosSlowDisk}</td>
 *     <td>MODERATE</td>
 *     <td>read + write latency — HDD seek contention, EBS throughput throttle</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosRenameRace}</td>
 *     <td>MODERATE</td>
 *     <td>ENOENT on rename — WAL rotation race, LSM compaction concurrent delete</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.filesystem.testpack.CompositeChaosMetadataFailure}</td>
 *     <td>MODERATE</td>
 *     <td>EIO on open — inode block error, journal replay damage, RAID degraded member</td>
 *   </tr>
 * </table>
 *
 * <h2>Prerequisites</h2>
 *
 * <p>All scenarios in this package require a container prepared with libchaos-io before {@code
 * container.start()}. Annotate the test class with {@code @SyscallLevelChaos(LibchaosLib.IO)};
 * {@code ChaosTestingExtension} drives preparation automatically.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.filesystem.testpack;
