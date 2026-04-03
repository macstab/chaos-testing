/**
 * CPU chaos injection via userspace tools ({@code cpulimit} and {@code stress-ng}).
 *
 * <h2>Entry Point</h2>
 *
 * <p>{@link com.macstab.chaos.cpu.CgroupsCpuChaos} is the single public class in this package. It
 * is registered as the {@code CpuChaos} SPI implementation via {@code
 * META-INF/services/com.macstab.chaos.core.api.CpuChaos} and is discovered automatically by {@code
 * ChaosProviderRegistry} when {@code macstab-chaos-cpu} is on the classpath.
 *
 * <h2>Capabilities</h2>
 *
 * <ul>
 *   <li>CPU throttling via {@code cpulimit} (targets PID 1 or a named process)
 *   <li>CPU stress injection via {@code stress-ng --cpu}
 *   <li>Combined stress + throttle for Kubernetes pod-limit simulation
 *   <li>Timed throttle with automatic container-internal reset
 *   <li>CPU usage measurement via two-sample {@code /proc/stat} delta
 * </ul>
 *
 * <h2>Platform Requirements</h2>
 *
 * <ul>
 *   <li>Linux container (any distro supported by {@code PackageInstaller})
 *   <li>No privileged mode or cgroup write access required
 *   <li>Tools auto-installed on first use (Debian, Alpine, Fedora, RHEL)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.cpu;
