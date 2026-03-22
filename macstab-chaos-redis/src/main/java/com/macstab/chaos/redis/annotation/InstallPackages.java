/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Installs packages in a Testcontainer using the universal package manager.
 *
 * <p><strong>Purpose:</strong> Annotation-driven package installation for ANY Testcontainer,
 * automatically detecting the Linux distribution and using the appropriate package manager (APT,
 * APK, DNF, YUM, PACMAN, ZYPPER).
 *
 * <p><strong>Key Features:</strong>
 *
 * <ul>
 *   <li>Universal: Works with ANY Linux container (Debian, Alpine, Fedora, Arch, openSUSE, CentOS)
 *   <li>Automatic: Detects distribution and selects package manager
 *   <li>Verified: Optionally verifies installation with 'which' command
 *   <li>Deduplication: Automatically removes duplicate packages
 *   <li>Fail-fast: Throws exception immediately on installation failure
 * </ul>
 *
 * <p><strong>Usage with Standard @Container:</strong>
 *
 * <pre>{@code
 * @Container
 * @InstallPackages({"curl", "jq", "vim"})
 * GenericContainer<?> postgres = new GenericContainer<>("postgres:16");
 * }</pre>
 *
 * <p><strong>Usage with Multiple Containers:</strong>
 *
 * <pre>{@code
 * @Container
 * @InstallPackages({"curl", "jq"})
 * GenericContainer<?> postgres = new GenericContainer<>("postgres:16");
 *
 * @Container
 * @InstallPackages(value = {"redis-cli"}, verify = false)
 * GenericContainer<?> redis = new GenericContainer<>("redis:7.4");
 * }</pre>
 *
 * <p><strong>Supported Distributions:</strong>
 *
 * <table border="1">
 *   <caption>Supported Linux distributions and package managers</caption>
 *   <tr><th>Distribution</th><th>Package Manager</th><th>Example Image</th></tr>
 *   <tr><td>Debian/Ubuntu</td><td>apt-get</td><td>postgres:16, ubuntu:22.04</td></tr>
 *   <tr><td>Alpine</td><td>apk</td><td>redis:7.4-alpine, nginx:alpine</td></tr>
 *   <tr><td>Fedora/RHEL 8+</td><td>dnf</td><td>fedora:39, registry.access.redhat.com/ubi8</td></tr>
 *   <tr><td>CentOS 7</td><td>yum</td><td>centos:7</td></tr>
 *   <tr><td>Arch Linux</td><td>pacman</td><td>archlinux:latest</td></tr>
 *   <tr><td>openSUSE</td><td>zypper</td><td>opensuse/leap:15</td></tr>
 * </table>
 *
 * <p><strong>Installation Process:</strong>
 *
 * <ol>
 *   <li>Container starts (managed by @Container lifecycle)
 *   <li>Extension detects Linux distribution from /etc/os-release
 *   <li>Selects appropriate package manager (APT, APK, DNF, etc.)
 *   <li>Deduplicates package list (preserves order, removes duplicates)
 *   <li>Runs package manager install command
 *   <li>Verifies installation with 'which' command (if verify=true)
 * </ol>
 *
 * <p><strong>Error Handling:</strong>
 *
 * <ul>
 *   <li>Package not found → {@link com.macstab.chaos.core.exception.PackageInstallationException}
 *   <li>Container not started → {@link IllegalStateException}
 *   <li>Verification failed → {@link com.macstab.chaos.core.exception.PackageInstallationException}
 *   <li>Unsupported distribution → {@link IllegalStateException}
 * </ul>
 *
 * <p><strong>Performance:</strong>
 *
 * <ul>
 *   <li>Debian/Ubuntu: ~4-5 seconds (apt-get update + install)
 *   <li>Alpine: ~2-3 seconds (apk update + add)
 *   <li>Fedora: ~5-6 seconds (dnf install)
 *   <li>Cached in Docker layers (faster on subsequent runs)
 * </ul>
 *
 * <p><strong>Best Practices:</strong>
 *
 * <pre>{@code
 * // ✅ Good: Install minimal packages needed for testing
 * @InstallPackages({"curl", "jq"})
 *
 * // ✅ Good: Disable verification if binary name differs from package
 * @InstallPackages(value = {"iproute2"}, verify = false)  // Binary is 'ip', not 'iproute2'
 *
 * // ❌ Bad: Installing too many packages slows tests
 * @InstallPackages({"curl", "vim", "emacs", "htop", "git", ...})  // Unnecessary
 *
 * // ❌ Bad: Manual deduplication (framework handles this)
 * @InstallPackages({"curl", "jq", "curl"})  // Auto-deduplicated to ["curl", "jq"]
 * }</pre>
 *
 * <p><strong>Integration with Chaos Testing:</strong>
 *
 * <pre>{@code
 * // Combine with network chaos for comprehensive testing
 * @Container
 * @InstallPackages({"iproute2", "iptables"})  // Install tc, iptables
 * GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
 *     .withCreateContainerCmdModifier(cmd ->
 *         cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
 *
 * @Test
 * void testWithNetworkChaos() {
 *     // Now you can inject latency, packet loss, etc.
 *     redis.execInContainer("tc", "qdisc", "add", "dev", "eth0", "root", "netem", "delay", "100ms");
 * }
 * }</pre>
 *
 * <p><strong>Limitations:</strong>
 *
 * <ul>
 *   <li>Package name might differ from binary name (e.g., "iproute2" → "tc" binary)
 *   <li>Verification only checks if binary exists in PATH, not if fully functional
 *   <li>Requires container to be running (enforced by framework)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see com.macstab.chaos.core.util.PackageInstaller
 * @see com.macstab.chaos.core.util.PackageManager
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InstallPackages {

  /**
   * Packages to install in the container.
   *
   * <p><strong>Format:</strong> Package names as recognized by the distribution's package manager.
   *
   * <p><strong>Deduplication:</strong> Duplicate packages are automatically removed while
   * preserving order.
   *
   * <p><strong>Examples:</strong>
   *
   * <pre>{@code
   * @InstallPackages({"curl", "jq"})                    // Two packages
   * @InstallPackages("vim")                             // Single package (array syntax optional)
   * @InstallPackages({"curl", "jq", "curl"})            // Auto-deduplicated to ["curl", "jq"]
   * @InstallPackages({"iproute2", "iptables", "vim"})   // Network tools + editor
   * }</pre>
   *
   * <p><strong>Distribution Differences:</strong>
   *
   * <ul>
   *   <li>Debian/Ubuntu: "iproute2" (not "iproute")
   *   <li>Alpine: "iproute2" (same as Debian)
   *   <li>Fedora/RHEL: "iproute" (not "iproute2")
   * </ul>
   *
   * @return array of package names (never null, never empty)
   */
  String[] value();

  /**
   * Whether to verify installation with 'which' command.
   *
   * <p><strong>Default:</strong> {@code true} (verification enabled)
   *
   * <p><strong>Verification Process:</strong> After installation, runs {@code which <package>} for
   * each package to verify the binary exists in PATH.
   *
   * <p><strong>When to Disable:</strong>
   *
   * <ul>
   *   <li>Package name differs from binary name (e.g., "iproute2" → "tc" binary)
   *   <li>Package doesn't install binaries (libraries only)
   *   <li>Performance optimization (skip verification for trusted packages)
   * </ul>
   *
   * <p><strong>Examples:</strong>
   *
   * <pre>{@code
   * // Verification enabled (default)
   * @InstallPackages({"curl", "jq"})
   * @InstallPackages(value = {"curl"}, verify = true)
   *
   * // Verification disabled (package name != binary name)
   * @InstallPackages(value = {"iproute2"}, verify = false)  // Binary is 'tc', 'ip', etc.
   * @InstallPackages(value = {"postgresql-client"}, verify = false)  // Binary is 'psql'
   * }</pre>
   *
   * @return true to verify installation, false to skip verification
   */
  boolean verify() default true;
}
