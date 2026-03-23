/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.ConnectionChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.ContainerArchitecture;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Connection chaos using Toxiproxy inside container.
 *
 * <p><strong>REQUIRES NET_ADMIN CAPABILITY</strong> for iptables redirect.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyConnectionChaos implements ConnectionChaos {

  // Configuration constants
  private static final String TOXIPROXY_API = "http://localhost:8474";
  private static final String TOXIPROXY_BINARY = "toxiproxy-server";
  private static final int TOXIPROXY_STARTUP_TIMEOUT_MS = 10000;
  private static final int TOXIPROXY_POLL_INTERVAL_MS = 100;
  private static final int PROXY_PORT_OFFSET = 10000;
  private static final int MAX_PORT = 65535;

  // Toxiproxy download
  private static final String TOXIPROXY_VERSION = "v2.9.0";
  private static final String TOXIPROXY_DOWNLOAD_URL_TEMPLATE =
      "https://github.com/Shopify/toxiproxy/releases/download/%s/toxiproxy-server-linux-%s";

  // Curl commands
  private static final String CURL_POST_JSON =
      "curl -s -X POST %s -H 'Content-Type: application/json'";
  private static final String CURL_DELETE = "curl -s -X DELETE %s";

  // Validation patterns
  private static final Pattern VALID_HOSTNAME = Pattern.compile("^[a-zA-Z0-9.-]+$");
  private static final Pattern SHELL_METACHAR = Pattern.compile("[;&|`$(){}\\[\\]<>'\"]");

  // Track allocated ports to avoid collisions
  private final Set<Integer> allocatedPorts = new HashSet<>();

  @Override
  public void addLatency(
      final GenericContainer<?> container, final String target, final Duration latency) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(latency, "latency must not be null");

    validateContainerRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final String proxyName = getProxyName(addr);

    ensureToxiproxyRunning(container);
    ensureProxyExists(container, addr);

    final long ms = latency.toMillis();

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " "
                      + "-d '{\"type\":\"latency\",\"attributes\":{\"latency\":%d}}' 2>&1",
                  TOXIPROXY_API,
                  sanitize(proxyName),
                  ms));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to add latency to %s: %s", target, result.getStderr()));
      }

      log.info("Added {}ms latency to {} (cumulative with existing toxics)", ms, target);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to add latency to " + target, e);
    }
  }

  @Override
  public void dropPackets(
      final GenericContainer<?> container, final String target, final double rate) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");

    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException(
          String.format("rate must be in [0.0, 1.0], got: %.2f", rate));
    }

    validateContainerRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final String proxyName = getProxyName(addr);

    ensureToxiproxyRunning(container);
    ensureProxyExists(container, addr);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " "
                      + "-d '{\"type\":\"down\",\"attributes\":{\"rate\":%.2f}}' 2>&1",
                  TOXIPROXY_API,
                  sanitize(proxyName),
                  rate));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to drop packets for %s: %s", target, result.getStderr()));
      }

      log.info("Added {:.0%} packet loss to {}", rate, target);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to drop packets for " + target, e);
    }
  }

  @Override
  public void limitBandwidth(
      final GenericContainer<?> container, final String target, final long bytesPerSecond) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");

    if (bytesPerSecond < 1) {
      throw new IllegalArgumentException(
          String.format("bytesPerSecond must be >= 1, got: %d", bytesPerSecond));
    }

    validateContainerRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final String proxyName = getProxyName(addr);

    ensureToxiproxyRunning(container);
    ensureProxyExists(container, addr);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " "
                      + "-d '{\"type\":\"bandwidth\",\"attributes\":{\"rate\":%d}}' 2>&1",
                  TOXIPROXY_API,
                  sanitize(proxyName),
                  bytesPerSecond));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to limit bandwidth for %s: %s", target, result.getStderr()));
      }

      log.info("Limited bandwidth to {} bytes/s for {}", bytesPerSecond, target);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to limit bandwidth for " + target, e);
    }
  }

  @Override
  public void timeoutConnections(
      final GenericContainer<?> container, final String target, final Duration timeout) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");

    validateContainerRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final String proxyName = getProxyName(addr);

    ensureToxiproxyRunning(container);
    ensureProxyExists(container, addr);

    final long ms = timeout.toMillis();

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " "
                      + "-d '{\"type\":\"timeout\",\"attributes\":{\"timeout\":%d}}' 2>&1",
                  TOXIPROXY_API,
                  sanitize(proxyName),
                  ms));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to add timeout for %s: %s", target, result.getStderr()));
      }

      log.info("Added {}ms timeout to {}", ms, target);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to add timeout for " + target, e);
    }
  }

  @Override
  public void slowClose(
      final GenericContainer<?> container, final String target, final Duration delay) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(delay, "delay must not be null");

    validateContainerRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final String proxyName = getProxyName(addr);

    ensureToxiproxyRunning(container);
    ensureProxyExists(container, addr);

    final long ms = delay.toMillis();

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " "
                      + "-d '{\"type\":\"slow_close\",\"attributes\":{\"delay\":%d}}' 2>&1",
                  TOXIPROXY_API,
                  sanitize(proxyName),
                  ms));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to add slow close for %s: %s", target, result.getStderr()));
      }

      log.info("Added {}ms slow close to {}", ms, target);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to add slow close for " + target, e);
    }
  }

  @Override
  public void rejectConnections(final GenericContainer<?> container, final String target) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");

    validateContainerRunning(container);

    final TargetAddress addr = TargetAddress.parse(target);
    final String proxyName = getProxyName(addr);

    ensureToxiproxyRunning(container);
    ensureProxyExists(container, addr);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON + " " + "-d '{\"enabled\":false}' 2>&1",
                  TOXIPROXY_API,
                  sanitize(proxyName)));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to reject connections for %s: %s", target, result.getStderr()));
      }

      log.info("Rejected all connections to {}", target);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to reject connections for " + target, e);
    }
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      // Remove all iptables rules with REDIRECT (chaos rules)
      container.execInContainer(
          Shell.SH,
          Shell.FLAG_C,
          "iptables -t nat -L OUTPUT --line-numbers 2>/dev/null | "
              + "grep REDIRECT | tac | awk '{print $1}' | "
              + "xargs -r -I {} iptables -t nat -D OUTPUT {} 2>/dev/null || true");

      // Stop Toxiproxy (pkill might not be available, use killall)
      container.execInContainer(
          Shell.SH, Shell.FLAG_C, "killall -9 " + TOXIPROXY_BINARY + " 2>/dev/null || true");

      // Clear allocated ports
      allocatedPorts.clear();

      log.info("Reset connection chaos (stopped Toxiproxy, removed iptables rules)");
    } catch (final Exception e) {
      log.warn("Failed to fully reset connection chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    // Check if already installed
    try {
      final var check = container.execInContainer("which", TOXIPROXY_BINARY);
      if (check.getExitCode() == 0) {
        return; // Already installed
      }
    } catch (final Exception ignored) {
      // Not installed, continue
    }

    // Download Toxiproxy binary (detect architecture)
    try {
      // ca-certificates has no binary, skip verification
      PackageInstaller.install(container, List.of("ca-certificates"), false);
      PackageInstaller.install(container, "curl", "iptables");

      // Detect container architecture
      final ContainerArchitecture arch = ContainerArchitecture.detect(container);

      final String downloadUrl =
          String.format(TOXIPROXY_DOWNLOAD_URL_TEMPLATE, TOXIPROXY_VERSION, arch.getBinaryName());

      final String downloadCmd =
          String.format(
              "curl -sL %s -o /usr/local/bin/%s && chmod +x /usr/local/bin/%s",
              downloadUrl, TOXIPROXY_BINARY, TOXIPROXY_BINARY);

      final var download = container.execInContainer(Shell.SH, Shell.FLAG_C, downloadCmd);

      if (download.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to download Toxiproxy: " + download.getStderr());
      }

      log.debug("Installed Toxiproxy {} ({})", TOXIPROXY_VERSION, arch.getBinaryName());
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to install Toxiproxy", e);
    }
  }

  /** Ensure Toxiproxy server is running. */
  private void ensureToxiproxyRunning(final GenericContainer<?> container) {
    try {
      // Check if already running
      final var result = container.execInContainer("curl", "-s", TOXIPROXY_API + "/proxies);
      if (result.getExitCode() == 0) {
        return;
      }
    } catch (final Exception e) {
      // Not running, continue to start
    }

    installTools(container);

    try {
      // Start Toxiproxy
      container.execInContainer(
          Shell.SH, Shell.FLAG_C, TOXIPROXY_BINARY + " -host 0.0.0.0 >/dev/null 2>&1 &");

      // Wait for API to be ready (with timeout)
      final long deadline = System.currentTimeMillis() + TOXIPROXY_STARTUP_TIMEOUT_MS;
      boolean ready = false;

      while (System.currentTimeMillis() < deadline) {
        try {
          final var check =
              container.execInContainer("curl", "-s", "-f", TOXIPROXY_API + "/proxies");
          if (check.getExitCode() == 0) {
            ready = true;
            break;
          }
        } catch (final Exception ignored) {
          // Continue waiting
        }
        Thread.sleep(TOXIPROXY_POLL_INTERVAL_MS);
      }

      if (!ready) {
        throw new ChaosOperationFailedException(
            "Toxiproxy did not start within " + TOXIPROXY_STARTUP_TIMEOUT_MS + "ms");
      }

      log.info("Started Toxiproxy server");
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted while starting Toxiproxy", e);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start Toxiproxy", e);
    }
  }

  /** Ensure proxy exists for target. */
  private void ensureProxyExists(final GenericContainer<?> container, final TargetAddress addr) {
    final String proxyName = getProxyName(addr);
    final int proxyPort = allocateProxyPort(addr.port());

    try {
      // Check if proxy exists
      final var check =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("curl -s -f %s/proxies/%s 2>&1", TOXIPROXY_API, sanitize(proxyName)));

      if (check.getExitCode() == 0) {
        return; // Proxy exists
      }

      // Create proxy
      final var create =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " "
                      + "-d '{\"name\":\"%s\",\"listen\":\"0.0.0.0:%d\","
                      + "\"upstream\":\"%s:%d\",\"enabled\":true}' 2>&1",
                  TOXIPROXY_API,
                  sanitize(proxyName),
                  proxyPort,
                  sanitize(addr.host()),
                  addr.port()));

      if (create.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to create proxy: " + create.getStderr());
      }

      // Add iptables redirect
      final var iptables =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "iptables -t nat -A OUTPUT -p tcp --dport %d -j REDIRECT --to-port %d 2>&1",
                  addr.port(), proxyPort));

      if (iptables.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to setup iptables redirect: " + iptables.getStderr());
      }

      log.info(
          "Created proxy {} for {}:{} (proxy port: {})",
          proxyName,
          addr.host(),
          addr.port(),
          proxyPort);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to create proxy for " + addr.host() + ":" + addr.port(), e);
    }
  }

  /** Allocate proxy port with collision detection. */
  private synchronized int allocateProxyPort(final int targetPort) {
    int proxyPort = PROXY_PORT_OFFSET + targetPort;

    if (proxyPort > MAX_PORT) {
      throw new ChaosOperationFailedException(
          String.format("Calculated proxy port %d exceeds max port %d", proxyPort, MAX_PORT));
    }

    // Check for collision
    if (allocatedPorts.contains(proxyPort)) {
      // Find next available port
      for (int i = PROXY_PORT_OFFSET; i <= MAX_PORT; i++) {
        if (!allocatedPorts.contains(i)) {
          proxyPort = i;
          break;
        }
      }

      if (allocatedPorts.contains(proxyPort)) {
        throw new ChaosOperationFailedException("No available proxy ports");
      }
    }

    allocatedPorts.add(proxyPort);
    return proxyPort;
  }

  /** Get proxy name from target address. */
  private String getProxyName(final TargetAddress addr) {
    return "proxy_" + addr.host().replace(".", "_") + "_" + addr.port();
  }

  /** Sanitize string for shell safety. */
  private String sanitize(final String input) {
    if (SHELL_METACHAR.matcher(input).find()) {
      throw new IllegalArgumentException("Invalid characters in input: " + input);
    }
    return input;
  }

  /** Validate container is running. */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }

  /** Validated target address (host:port). */
  private record TargetAddress(String host, int port) {

    private TargetAddress {
      Objects.requireNonNull(host, "host must not be null");

      if (host.isEmpty()) {
        throw new IllegalArgumentException("host must not be empty");
      }

      if (!VALID_HOSTNAME.matcher(host).matches()) {
        throw new IllegalArgumentException("Invalid hostname format: " + host);
      }

      if (port < 1 || port > MAX_PORT) {
        throw new IllegalArgumentException(
            String.format("port must be 1-%d, got: %d", MAX_PORT, port));
      }
    }

    /** Parse target string (host:port). */
    static TargetAddress parse(final String target) {
      Objects.requireNonNull(target, "target must not be null");

      final String[] parts = target.split(":");

      if (parts.length != 2) {
        throw new IllegalArgumentException("target must be host:port format, got: " + target);
      }

      try {
        final int port = Integer.parseInt(parts[1]);
        return new TargetAddress(parts[0], port);
      } catch (final NumberFormatException e) {
        throw new IllegalArgumentException("Invalid port in target: " + target, e);
      }
    }
  }
}
