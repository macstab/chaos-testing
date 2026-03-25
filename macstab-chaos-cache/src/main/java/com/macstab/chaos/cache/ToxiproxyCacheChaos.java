/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.CacheChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.model.ContainerArchitecture;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Cache chaos using Toxiproxy for Redis.
 *
 * <p><strong>REQUIRES NET_ADMIN CAPABILITY</strong> for iptables redirect.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxiproxyCacheChaos implements CacheChaos {

  // Configuration constants
  private static final String TOXIPROXY_API = "http://localhost:8474";
  private static final String TOXIPROXY_BINARY = "toxiproxy-server";
  private static final String REDIS_PROXY_NAME = "redis_cache";
  private static final int REDIS_PORT = 6379;
  private static final int REDIS_PROXY_PORT = 16379;
  private static final int REDIS_INTERNAL_PORT = 6380;
  private static final int TOXIPROXY_STARTUP_TIMEOUT_MS = 10000;
  private static final int TOXIPROXY_POLL_INTERVAL_MS = 100;

  // Toxiproxy download
  private static final String TOXIPROXY_VERSION = "v2.9.0";
  private static final String TOXIPROXY_DOWNLOAD_URL_TEMPLATE =
      "https://github.com/Shopify/toxiproxy/releases/download/%s/toxiproxy-server-linux-%s";

  // Curl commands (no -f to see HTTP error responses)
  private static final String CURL_POST_JSON =
      "curl -s -X POST %s -H 'Content-Type: application/json'";
  private static final String CURL_DELETE = "curl -s -X DELETE %s";

  // Validation
  private static final Pattern SHELL_METACHAR = Pattern.compile("[;&|`$(){}\\[\\]<>'\"]");

  @Override
  public void injectMisses(
      final GenericContainer<?> container, final String keyPattern, final double rate) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(keyPattern, "keyPattern must not be null");

    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException(
          String.format("rate must be in [0.0, 1.0], got: %.2f", rate));
    }

    validateContainerRunning(container);
    ensureToxiproxyRunning(container);
    ensureRedisProxyExists(container);

    try {
      // Use "timeout" toxic with probability to simulate cache miss/timeout
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " -d '{\"type\":\"timeout\",\"attributes\":{\"timeout\":0},\"toxicity\":%.2f}' 2>&1",
                  String.format("%s/proxies/%s/toxics", TOXIPROXY_API, REDIS_PROXY_NAME),
                  rate));

      if (result.getExitCode() != 0) {
        log.error(
            "Toxiproxy toxic creation failed (exit {}): stdout='{}', stderr='{}'",
            result.getExitCode(),
            result.getStdout(),
            result.getStderr());
        throw new ChaosOperationFailedException(
            String.format(
                "Failed to inject cache misses (exit %d): %s",
                result.getExitCode(),
                result.getStdout().isEmpty() ? result.getStderr() : result.getStdout()));
      }

      log.info("Injected {:.0%} cache misses for pattern: {}", rate, keyPattern);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to inject cache misses", e);
    }
  }

  @Override
  public void slowResponse(final GenericContainer<?> container, final Duration delay) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(delay, "delay must not be null");

    validateContainerRunning(container);
    ensureToxiproxyRunning(container);
    ensureRedisProxyExists(container);

    final long ms = delay.toMillis();

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " -d '{\"type\":\"latency\",\"attributes\":{\"latency\":%d}}' 2>&1",
                  String.format("%s/proxies/%s/toxics", TOXIPROXY_API, REDIS_PROXY_NAME),
                  ms));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            String.format("Failed to slow cache responses: %s", result.getStderr()));
      }

      log.info("Added {}ms latency to cache responses (cumulative)", ms);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to slow cache responses", e);
    }
  }

  @Override
  public void forceEviction(final GenericContainer<?> container, final int percentage) {
    Objects.requireNonNull(container, "container must not be null");

    if (percentage < 1 || percentage > 100) {
      throw new IllegalArgumentException(
          String.format("percentage must be in [1, 100], got: %d", percentage));
    }

    validateContainerRunning(container);

    try {
      // Calculate number of keys to delete (use REDIS_PORT, not REDIS_INTERNAL_PORT)
      final var dbsize =
          container.execInContainer("redis-cli", "-p", String.valueOf(REDIS_PORT), "DBSIZE");

      if (dbsize.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to get Redis DBSIZE: " + dbsize.getStderr());
      }

      // Use redis-cli to evict percentage of keys
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "redis-cli -p %d --scan --pattern '*' 2>/dev/null | "
                      + "head -n $(redis-cli -p %d DBSIZE 2>/dev/null | awk '{print int($1 * %d / 100)}') | "
                      + "xargs -r redis-cli -p %d DEL >/dev/null 2>&1",
                  REDIS_PORT, REDIS_PORT, percentage, REDIS_PORT));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to evict keys: " + result.getStderr());
      }

      log.info("Evicted ~{}% of cache keys", percentage);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to force eviction", e);
    }
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      // Remove iptables redirect
      container.execInContainer(
          Shell.SH,
          Shell.FLAG_C,
          String.format(
              "iptables -t nat -D OUTPUT -p tcp --dport %d -j REDIRECT --to-port %d 2>/dev/null || true",
              REDIS_PORT, REDIS_PROXY_PORT));

      // Stop Toxiproxy - use /proc (always available in Linux)
      container.execInContainer(
          Shell.SH,
          Shell.FLAG_C,
          String.format(
              "for pid in $(grep -l '%s' /proc/*/cmdline 2>/dev/null | cut -d/ -f3); do "
                  + "kill -9 $pid 2>/dev/null || true; "
                  + "done; sleep 0.2",
              TOXIPROXY_BINARY));

      log.info("Reset cache chaos (stopped Toxiproxy, removed iptables rules)");
    } catch (final Exception e) {
      log.warn("Failed to fully reset cache chaos", e);
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
        return;
      }
    } catch (final Exception ignored) {
      // Not installed
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
    // Check if Toxiproxy is running by testing API endpoint
    try {
      final var check = container.execInContainer("curl", "-s", TOXIPROXY_API + "/proxies");
      if (check.getExitCode() == 0) {
        return; // Already running
      }
    } catch (final Exception e) {
      // Not running, continue
    }

    installTools(container);

    try {
      container.execInContainer(
          Shell.SH, Shell.FLAG_C, TOXIPROXY_BINARY + " -host 0.0.0.0 >/dev/null 2>&1 &");

      // Wait for API readiness
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
          // Continue
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

  /** Ensure Redis proxy exists. */
  private void ensureRedisProxyExists(final GenericContainer<?> container) {
    try {
      // Check if proxy exists
      final var check =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format("curl -s -f %s/proxies/%s 2>&1", TOXIPROXY_API, REDIS_PROXY_NAME));

      if (check.getExitCode() == 0) {
        return;
      }

      // Create Redis proxy
      final var create =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  CURL_POST_JSON
                      + " -d '{\"name\":\"%s\",\"listen\":\"0.0.0.0:%d\","
                      + "\"upstream\":\"localhost:%d\",\"enabled\":true}' 2>&1",
                  String.format("%s/proxies", TOXIPROXY_API),
                  REDIS_PROXY_NAME,
                  REDIS_PROXY_PORT,
                  REDIS_INTERNAL_PORT));

      if (create.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to create Redis proxy: " + create.getStderr());
      }

      // Redirect traffic
      final var iptables =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "iptables -t nat -A OUTPUT -p tcp --dport %d -j REDIRECT --to-port %d 2>&1",
                  REDIS_PORT, REDIS_PROXY_PORT));

      if (iptables.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to setup iptables redirect: " + iptables.getStderr());
      }

      // Validate proxy is working (with retry, 2s timeout)
      final long deadline = System.currentTimeMillis() + 2000;
      boolean proxyReady = false;

      while (System.currentTimeMillis() < deadline) {
        try {
          final var validate =
              container.execInContainer(
                  "redis-cli", "-p", String.valueOf(REDIS_PROXY_PORT), "PING");

          if (validate.getExitCode() == 0) {
            proxyReady = true;
            break;
          }
        } catch (final Exception ignored) {
          // Continue
        }
        Thread.sleep(50);
      }

      if (!proxyReady) {
        throw new ChaosOperationFailedException("Redis proxy did not become ready within 2000ms");
      }

      log.info(
          "Created Redis proxy (port {} → proxy {} → internal {})",
          REDIS_PORT,
          REDIS_PROXY_PORT,
          REDIS_INTERNAL_PORT);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to create Redis proxy", e);
    }
  }

  /** Validate container is running. */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }
}
