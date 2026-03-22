/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.extension.RedisContainerExtension;
import com.macstab.chaos.redis.extension.SentinelContainerExtension.SentinelCluster;

/**
 * Integration tests demonstrating embedded packages attribute in Redis annotations.
 *
 * <p><strong>Purpose:</strong> Shows how to use the {@code packages} attribute directly in
 * {@code @RedisStandalone} and {@code @RedisSentinel} annotations for annotation-driven package
 * installation.
 *
 * <p><strong>Key Features Demonstrated:</strong>
 *
 * <ul>
 *   <li>Embedded packages attribute (no separate {@code @InstallPackages} needed)
 *   <li>Automatic package installation across all containers
 *   <li>Cross-distribution compatibility (Debian vs Alpine)
 *   <li>Multi-instance support with different packages
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Embedded Packages Integration Tests")
class EmbeddedPackagesIntegrationTest {

  // ==================== Example 1: Standalone with Embedded Packages ====================

  /**
   * Example 1: Install packages using embedded attribute in @RedisStandalone.
   *
   * <p><strong>What This Tests:</strong>
   *
   * <ul>
   *   <li>Embedded packages attribute (no {@code @InstallPackages} annotation needed)
   *   <li>Automatic package installation in Debian-based container
   *   <li>Package verification
   * </ul>
   */
  @RedisStandalone(packages = {"curl", "jq"})
  static class StandaloneWithPackagesTest {

    @Test
    @DisplayName("Should install packages from embedded attribute in @RedisStandalone")
    void shouldInstallPackagesFromEmbeddedAttribute() throws Exception {
      // Given: @RedisStandalone with packages = {"curl", "jq"}
      // When: Container starts (packages auto-installed by RedisContainerExtension)
      final var container = RedisContainerExtension.getContainerInstance("default");

      // Then: Packages are available
      var curlResult = container.execInContainer("which", "curl");
      assertThat(curlResult.getExitCode())
          .as("curl should be installed from embedded packages attribute")
          .isZero();

      var jqResult = container.execInContainer("which", "jq");
      assertThat(jqResult.getExitCode())
          .as("jq should be installed from embedded packages attribute")
          .isZero();

      // Verify packages work
      var curlVersion = container.execInContainer("curl", "--version");
      assertThat(curlVersion.getExitCode()).isZero();
      assertThat(curlVersion.getStdout()).contains("curl");
    }
  }

  // ==================== Example 2: Multi-Instance with Different Packages ====================

  /**
   * Example 2: Multiple Redis instances with different packages.
   *
   * <p><strong>What This Tests:</strong>
   *
   * <ul>
   *   <li>Multi-instance package installation
   *   <li>Different packages for different containers
   *   <li>Cross-distribution (Debian vs Alpine)
   * </ul>
   */
  @RedisStandalone(
      id = "debian",
      version = "7.4",
      packages = {"curl", "jq"})
  @RedisStandalone(
      id = "alpine",
      version = "7.4-alpine",
      packages = {"vim"})
  static class MultiInstanceWithPackagesTest {

    @Test
    @DisplayName("Should install different packages in multiple instances")
    void shouldInstallDifferentPackagesInMultipleInstances() throws Exception {
      // Given: Two instances with different packages
      final var debianContainer = RedisContainerExtension.getContainerInstance("debian");
      final var alpineContainer = RedisContainerExtension.getContainerInstance("alpine");

      // Then: Debian has curl and jq
      var curlResult = debianContainer.execInContainer("which", "curl");
      assertThat(curlResult.getExitCode()).as("Debian container should have curl").isZero();

      var jqResult = debianContainer.execInContainer("which", "jq");
      assertThat(jqResult.getExitCode()).as("Debian container should have jq").isZero();

      // And: Alpine has vim
      var vimResult = alpineContainer.execInContainer("which", "vim");
      assertThat(vimResult.getExitCode()).as("Alpine container should have vim").isZero();
    }
  }

  // ==================== Example 3: Sentinel Cluster with Embedded Packages ====================

  /**
   * Example 3: Install packages in all Sentinel cluster containers.
   *
   * <p><strong>What This Tests:</strong>
   *
   * <ul>
   *   <li>Packages installed in ALL containers (master + replicas + sentinels)
   *   <li>Cluster-wide package availability
   *   <li>Automatic installation across 6 containers
   * </ul>
   */
  @RedisSentinel(
      replicas = 2,
      sentinels = 3,
      packages = {"curl", "vim"})
  static class SentinelClusterWithPackagesTest {

    @Test
    @DisplayName("Should install packages in all Sentinel cluster containers")
    void shouldInstallPackagesInAllClusterContainers(SentinelCluster cluster) throws Exception {
      // Given: Sentinel cluster with packages = {"curl", "vim"}
      // When: Cluster starts (packages auto-installed in all 6 containers)

      // Then: Master has packages
      var masterCurl = cluster.getMasterContainer().execInContainer("which", "curl");
      assertThat(masterCurl.getExitCode()).as("Master should have curl").isZero();

      var masterVim = cluster.getMasterContainer().execInContainer("which", "vim");
      assertThat(masterVim.getExitCode()).as("Master should have vim").isZero();

      // And: All replicas have packages
      for (final var replica : cluster.getReplicaContainers()) {
        var replicaCurl = replica.execInContainer("which", "curl");
        assertThat(replicaCurl.getExitCode()).as("Replica should have curl").isZero();

        var replicaVim = replica.execInContainer("which", "vim");
        assertThat(replicaVim.getExitCode()).as("Replica should have vim").isZero();
      }

      // And: All sentinels have packages
      for (final var sentinel : cluster.getSentinelContainers()) {
        var sentinelCurl = sentinel.execInContainer("which", "curl");
        assertThat(sentinelCurl.getExitCode()).as("Sentinel should have curl").isZero();

        var sentinelVim = sentinel.execInContainer("which", "vim");
        assertThat(sentinelVim.getExitCode()).as("Sentinel should have vim").isZero();
      }
    }
  }

  // ==================== Example 4: Combined with Network Chaos ====================

  /**
   * Example 4: Combine packages with network chaos for comprehensive testing.
   *
   * <p><strong>What This Tests:</strong>
   *
   * <ul>
   *   <li>Both network tools (iproute2, iptables) AND custom packages
   *   <li>Full chaos engineering stack
   * </ul>
   */
  @RedisStandalone(
      packages = {"curl", "jq"},
      enableNetworkChaos = true)
  static class CombinedPackagesAndChaosTest {

    @Test
    @DisplayName("Should install both network chaos tools and custom packages")
    void shouldInstallBothNetworkToolsAndCustomPackages() throws Exception {
      // Given: @RedisStandalone with packages AND enableNetworkChaos
      final var container = RedisContainerExtension.getContainerInstance("default");

      // Then: Custom packages are installed
      var curlResult = container.execInContainer("which", "curl");
      assertThat(curlResult.getExitCode()).as("curl should be installed").isZero();

      var jqResult = container.execInContainer("which", "jq");
      assertThat(jqResult.getExitCode()).as("jq should be installed").isZero();

      // And: Network chaos tools are installed
      var tcResult = container.execInContainer("which", "tc");
      assertThat(tcResult.getExitCode())
          .as("tc (traffic control) should be installed for network chaos")
          .isZero();

      var iptablesResult = container.execInContainer("which", "iptables");
      assertThat(iptablesResult.getExitCode())
          .as("iptables should be installed for network chaos")
          .isZero();
    }
  }

  // ==================== Documentation ====================

  /*
   * 🎯 KEY FEATURES DEMONSTRATED:
   *
   * 1. Embedded Attribute:
   *    ✅ @RedisStandalone(packages = {"curl", "jq"})
   *    ✅ No separate @InstallPackages annotation needed
   *    ✅ Cleaner, more concise syntax
   *
   * 2. Universal Package Management:
   *    ✅ Works on Debian (APT)
   *    ✅ Works on Alpine (APK)
   *    ✅ Auto-detects distribution
   *
   * 3. Cluster-Wide Installation:
   *    ✅ @RedisSentinel installs in ALL containers
   *    ✅ Master + replicas + sentinels
   *    ✅ 6 containers = 6 installations
   *
   * 4. Multi-Instance Support:
   *    ✅ Different packages for different instances
   *    ✅ @RedisStandalone(id="A", packages={"curl"})
   *    ✅ @RedisStandalone(id="B", packages={"vim"})
   *
   * 5. Integration with Chaos:
   *    ✅ Combine packages + enableNetworkChaos
   *    ✅ Get both custom packages AND chaos tools
   *    ✅ Full testing stack in one annotation
   *
   * 📦 REAL-WORLD USE CASES:
   *
   * - Development Tools: curl, jq, vim for debugging containers
   * - Network Chaos: iproute2, iptables for latency/packet-loss testing
   * - Database Clients: postgresql-client, mysql-client for integration tests
   * - Monitoring Tools: htop, iostat for performance analysis
   *
   * 🚀 INDUSTRY IMPACT:
   *
   * This is the MOST CONCISE annotation-driven package installation API:
   *
   * BEFORE:
   * - Manual package detection
   * - Manual package manager selection
   * - Manual installation commands
   * - Separate @InstallPackages annotation
   *
   * AFTER:
   * - @RedisStandalone(packages = {"curl", "jq"})
   * - Done!
   *
   * This sets a NEW STANDARD for testing library ergonomics! 🎉
   */
}
