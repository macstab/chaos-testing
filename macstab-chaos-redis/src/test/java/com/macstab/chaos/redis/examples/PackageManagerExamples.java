/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.examples;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.util.ContainerNetworkToolsInstaller;
import com.macstab.chaos.core.util.PackageManager;
import com.macstab.chaos.network.control.NetworkChaosController;
import com.macstab.chaos.redis.control.ControlFacade;

/**
 * Examples demonstrating the universal PackageManager usage.
 *
 * <p><strong>Purpose:</strong> Show how PackageManager auto-detects Linux distributions and
 * installs packages using the appropriate package manager.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public class PackageManagerExamples {

  // ==================== Example 1: Automatic Detection ====================

  /**
   * Example 1: Auto-detect package manager and install packages.
   *
   * <p>This shows the EASIEST way - just let it auto-detect!
   */
  @Test
  void example1_autoDetect() throws Exception {
    // Create ANY Linux container
    GenericContainer<?> container =
        new GenericContainer<>("alpine:latest")
            .withCommand("sleep", "infinity"); // Keep container running
    container.start();

    try {
      // 🎯 AUTO-DETECT and install!
      PackageManager pm = PackageManager.detect(container);

      System.out.println("Detected package manager: " + pm.getCommand()); // "apk"
      System.out.println("Distributions: " + pm.getDistributions()); // ["alpine"]

      // Install packages (automatically uses correct package manager)
      pm.install(container, "curl", "jq");

      // Verify installation
      var result = container.execInContainer("curl", "--version");
      System.out.println("curl installed: " + (result.getExitCode() == 0 ? "✅" : "❌"));

    } finally {
      container.stop();
    }
  }

  // ==================== Example 2: Cross-Distribution Network Chaos ====================

  /**
   * Example 2: Network chaos works on ANY Linux distribution!
   *
   * <p>This demonstrates the MAIN USE CASE - network chaos testing across different Redis images.
   */
  @Test
  void example2_networkChaos_Debian() throws Exception {
    // Debian-based Redis
    GenericContainer<?> redis =
        new GenericContainer<>("redis:7.4")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    redis.start();

    try {
      // Install network tools (auto-detects Debian, uses apt-get)
      ContainerNetworkToolsInstaller.install(redis);

      // Create network chaos controller
      NetworkChaosController chaos = new NetworkChaosController(List.of(redis));

      // Inject 100ms latency
      chaos.injectLatency(redis, Duration.ofMillis(100));
      System.out.println("✅ Network chaos works on Debian!");

      // Reset
      chaos.reset(redis);

    } finally {
      redis.stop();
    }
  }

  /** Example 2b: SAME CODE works on Alpine! */
  @Test
  void example2b_networkChaos_Alpine() throws Exception {
    // Alpine-based Redis
    GenericContainer<?> redis =
        new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    redis.start();

    try {
      // SAME CODE! Auto-detects Alpine, uses apk
      ContainerNetworkToolsInstaller.install(redis);

      // SAME CODE! Network chaos works!
      NetworkChaosController chaos = new NetworkChaosController(List.of(redis));
      chaos.injectLatency(redis, Duration.ofMillis(100));
      System.out.println("✅ Network chaos works on Alpine!");

      chaos.reset(redis);

    } finally {
      redis.stop();
    }
  }

  // ==================== Example 3: Manual Package Manager Selection ====================

  /** Example 3: Manually specify package manager (if you know the distribution). */
  @Test
  void example3_manualSelection() throws Exception {
    GenericContainer<?> alpine =
        new GenericContainer<>("alpine:3.19")
            .withCommand("sleep", "infinity"); // Keep container running
    alpine.start();

    try {
      // Option A: Auto-detect (RECOMMENDED)
      PackageManager.detect(alpine).install(alpine, "curl");

      // Option B: Manual selection (if you're sure about the distro)
      PackageManager.APK.install(alpine, "wget");

      // Both work!
      System.out.println("✅ curl and wget installed");

    } finally {
      alpine.stop();
    }
  }

  // ==================== Example 4: Check Package Manager Availability ====================

  /** Example 4: Check if a specific package manager is available. */
  @Test
  void example4_checkAvailability() throws Exception {
    GenericContainer<?> ubuntu =
        new GenericContainer<>("ubuntu:22.04")
            .withCommand("sleep", "infinity"); // Keep container running
    ubuntu.start();

    try {
      // Check different package managers
      System.out.println("APT available? " + PackageManager.APT.isAvailable(ubuntu)); // true
      System.out.println("APK available? " + PackageManager.APK.isAvailable(ubuntu)); // false
      System.out.println("DNF available? " + PackageManager.DNF.isAvailable(ubuntu)); // false

      // Install based on availability
      if (PackageManager.APT.isAvailable(ubuntu)) {
        PackageManager.APT.install(ubuntu, "vim");
        System.out.println("✅ vim installed via apt-get");
      }

    } finally {
      ubuntu.stop();
    }
  }

  // ==================== Example 5: Different Distributions ====================

  /** Example 5: Show it works across MANY distributions. */
  @Test
  void example5_manyDistributions() throws Exception {
    // Test with different distributions
    testDistribution("debian:12", PackageManager.APT);
    testDistribution("ubuntu:22.04", PackageManager.APT);
    testDistribution("alpine:3.19", PackageManager.APK);
    testDistribution("fedora:39", PackageManager.DNF);
  }

  private void testDistribution(String image, PackageManager expected) throws Exception {
    GenericContainer<?> container =
        new GenericContainer<>(image).withCommand("sleep", "infinity"); // Keep container running
    container.start();

    try {
      // Auto-detect
      PackageManager detected = PackageManager.detect(container);

      System.out.println(
          String.format(
              "Image: %-20s -> Detected: %-10s (Expected: %-10s) %s",
              image,
              detected.getCommand(),
              expected.getCommand(),
              detected == expected ? "✅" : "❌"));

      // Install test package
      detected.install(container, "curl");
      System.out.println("  └─ curl installed successfully!");

    } finally {
      container.stop();
    }
  }

  // ==================== Example 6: Real-World Use Case - Database Testing ====================

  /** Example 6: Install database clients for testing. */
  @Test
  void example6_databaseClients() throws Exception {
    GenericContainer<?> postgres =
        new GenericContainer<>("postgres:16")
            .withEnv("POSTGRES_PASSWORD", "test"); // Required for postgres to start
    postgres.start();

    try {
      // Auto-detect and install PostgreSQL client tools
      PackageManager pm = PackageManager.detect(postgres);
      pm.install(postgres, "postgresql-client");

      // Now you can use psql!
      var result = postgres.execInContainer("psql", "--version");
      System.out.println("PostgreSQL client version: " + result.getStdout().trim());

    } finally {
      postgres.stop();
    }
  }

  // ==================== Example 7: Installing Multiple Packages ====================

  /** Example 7: Install many packages at once. */
  @Test
  void example7_multiplePackages() throws Exception {
    GenericContainer<?> alpine =
        new GenericContainer<>("alpine:latest")
            .withCommand("sleep", "infinity"); // Keep container running
    alpine.start();

    try {
      // Install MANY packages at once
      PackageManager.detect(alpine)
          .install(
              alpine,
              "curl", // HTTP client
              "jq", // JSON processor
              "git", // Version control
              "bash", // Better shell
              "vim", // Text editor
              "htop" // Process viewer
              );

      System.out.println("✅ All tools installed!");

      // Verify
      var result = alpine.execInContainer("sh", "-c", "curl --version && jq --version");
      System.out.println(result.getStdout());

    } finally {
      alpine.stop();
    }
  }

  // ==================== Example 8: Error Handling ====================

  /** Example 8: Handle errors gracefully. */
  @Test
  void example8_errorHandling() throws Exception {
    GenericContainer<?> alpine =
        new GenericContainer<>("alpine:latest")
            .withCommand("sleep", "infinity"); // Keep container running
    alpine.start();

    try {
      PackageManager pm = PackageManager.detect(alpine);

      try {
        // Try to install non-existent package
        pm.install(alpine, "this-package-does-not-exist");
        System.out.println("❌ Should have failed!");

      } catch (RuntimeException e) {
        System.out.println("✅ Error caught correctly!");
        System.out.println("Error message: " + e.getMessage());
        // Example output:
        // "apk add failed with exit code 1
        //  stderr: ERROR: unable to select packages:
        //  this-package-does-not-exist (no such package)"
      }

    } finally {
      alpine.stop();
    }
  }

  // ==================== Example 9: Conditional Installation ====================

  /** Example 9: Install packages conditionally based on what's needed. */
  @Test
  void example9_conditionalInstallation() throws Exception {
    GenericContainer<?> container =
        new GenericContainer<>("ubuntu:22.04")
            .withCommand("sleep", "infinity"); // Keep container running
    container.start();

    try {
      PackageManager pm = PackageManager.detect(container);

      // Check if tool exists, install if not
      var checkCurl = container.execInContainer("which", "curl");
      if (checkCurl.getExitCode() != 0) {
        System.out.println("curl not found, installing...");
        pm.install(container, "curl");
      } else {
        System.out.println("curl already installed!");
      }

    } finally {
      container.stop();
    }
  }

  // ==================== Example 10: Using ContainerNetworkToolsInstaller ====================

  /**
   * Example 10: The HIGH-LEVEL API - ContainerNetworkToolsInstaller.
   *
   * <p>This is what MOST users will use!
   */
  @Test
  void example10_highLevelAPI() throws Exception {
    // Works with ANY Redis image!
    GenericContainer<?> redis =
        new GenericContainer<>("redis:7.4-alpine") // Alpine
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    redis.start();

    try {
      // 🎯 HIGH-LEVEL API - Auto-detects everything!
      ContainerNetworkToolsInstaller.install(redis);

      // Now use network chaos
      ControlFacade control = ControlFacade.create(List.of(redis), java.util.Map.of(redis, 0));

      control.network().injectLatency(redis, Duration.ofMillis(50));
      System.out.println("✅ Network chaos working on Alpine!");

      control.network().reset(redis);

    } finally {
      redis.stop();
    }
  }
}
