/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.DnsChaos;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * DNS chaos using CoreDNS inside container.
 *
 * <p><strong>REQUIRES NET_ADMIN CAPABILITY</strong> for iptables redirect.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class IptablesDnsChaos implements DnsChaos {

  // Configuration
  private static final String COREDNS_PORT = "1053";
  private static final String DNS_PORT = "53";
  private static final String CHAOS_HOSTS_FILE = "/tmp/chaos-dns-hosts";
  private static final String CHAOS_NXDOMAIN_FILE = "/tmp/chaos-dns-nxdomain";
  private static final String CHAOS_SERVFAIL_FILE = "/tmp/chaos-dns-servfail";
  private static final String CHAOS_REFUSED_FILE = "/tmp/chaos-dns-refused";
  private static final String COREFILE_PATH = "/etc/coredns/Corefile";
  private static final int COREDNS_STARTUP_TIMEOUT_MS = 5000;
  private static final int COREDNS_POLL_INTERVAL_MS = 100;

  // Validation
  private static final Pattern VALID_HOSTNAME =
      Pattern.compile(
          "^([a-zA-Z0-9*]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9*]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
  private static final Pattern VALID_IP =
      Pattern.compile(
          "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");

  @Override
  public void blockResolution(final GenericContainer<?> container, final String hostname) {
    returnNXDOMAIN(container, hostname);
  }

  /** Return NXDOMAIN for hostname. */
  public void returnNXDOMAIN(final GenericContainer<?> container, final String hostname) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(hostname, "hostname must not be null");

    validateHostname(hostname);
    validateContainerRunning(container);

    installTools(container);
    ensureCoreDnsRunning(container);
    ensureDnsRedirect(container);

    try {
      // Write hostname safely (no shell injection)
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "printf '%%s\\n' %s >> %s", escapeForPrintf(hostname), CHAOS_NXDOMAIN_FILE));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to write NXDOMAIN config: " + result.getStderr());
      }

      log.info("DNS: {} → NXDOMAIN (host unknown)", hostname);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to configure NXDOMAIN for " + hostname, e);
    }
  }

  /** Return SERVFAIL for hostname. */
  public void returnSERVFAIL(final GenericContainer<?> container, final String hostname) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(hostname, "hostname must not be null");

    validateHostname(hostname);
    validateContainerRunning(container);

    installTools(container);
    ensureCoreDnsRunning(container);
    ensureDnsRedirect(container);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "printf '%%s\\n' %s >> %s", escapeForPrintf(hostname), CHAOS_SERVFAIL_FILE));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to write SERVFAIL config: " + result.getStderr());
      }

      log.info("DNS: {} → SERVFAIL (server failure)", hostname);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to configure SERVFAIL for " + hostname, e);
    }
  }

  /** Return REFUSED for hostname. */
  public void returnREFUSED(final GenericContainer<?> container, final String hostname) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(hostname, "hostname must not be null");

    validateHostname(hostname);
    validateContainerRunning(container);

    installTools(container);
    ensureCoreDnsRunning(container);
    ensureDnsRedirect(container);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "printf '%%s\\n' %s >> %s", escapeForPrintf(hostname), CHAOS_REFUSED_FILE));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to write REFUSED config: " + result.getStderr());
      }

      log.info("DNS: {} → REFUSED (query refused)", hostname);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to configure REFUSED for " + hostname, e);
    }
  }

  @Override
  public void delayResolution(final GenericContainer<?> container, final Duration delay) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(delay, "delay must not be null");

    log.warn("delayResolution() not yet implemented - requires CoreDNS delay plugin");
    log.info("Requested DNS delay: {}ms (pending implementation)", delay.toMillis());
  }

  /** Rewrite hostname to target IP. */
  public void rewriteHost(
      final GenericContainer<?> container, final String hostname, final String targetIp) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(hostname, "hostname must not be null");
    Objects.requireNonNull(targetIp, "targetIp must not be null");

    validateHostname(hostname);
    validateIpAddress(targetIp);
    validateContainerRunning(container);

    installTools(container);
    ensureCoreDnsRunning(container);
    ensureDnsRedirect(container);

    try {
      final var result =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              String.format(
                  "printf '%%s %%s\\n' %s %s >> %s",
                  escapeForPrintf(targetIp), escapeForPrintf(hostname), CHAOS_HOSTS_FILE));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to write host rewrite: " + result.getStderr());
      }

      log.info("Rewrote DNS: {} → {}", hostname, targetIp);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to rewrite DNS for " + hostname + " → " + targetIp, e);
    }
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    log.debug("Installing DNS chaos tools (coredns, iptables)");
    PackageInstaller.ensureInstalled(container, Tool.COREDNS, Tool.IPTABLES);

    try {
      // Create directory
      final var mkdir = container.execInContainer("mkdir", "-p", "/etc/coredns");
      if (mkdir.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to create /etc/coredns");
      }

      // Create Corefile
      final String corefile =
          ".:1053 {\n"
              + "  template IN A "
              + CHAOS_NXDOMAIN_FILE
              + " {\n"
              + "    rcode NXDOMAIN\n"
              + "    reload 500ms\n"
              + "  }\n"
              + "  template IN A "
              + CHAOS_SERVFAIL_FILE
              + " {\n"
              + "    rcode SERVFAIL\n"
              + "    reload 500ms\n"
              + "  }\n"
              + "  template IN A "
              + CHAOS_REFUSED_FILE
              + " {\n"
              + "    rcode REFUSED\n"
              + "    reload 500ms\n"
              + "  }\n"
              + "  hosts "
              + CHAOS_HOSTS_FILE
              + " {\n"
              + "    ttl 1\n"
              + "    reload 500ms\n"
              + "    fallthrough\n"
              + "  }\n"
              + "  forward . 8.8.8.8 1.1.1.1\n"
              + "  log\n"
              + "}\n";

      final var write =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              "cat > " + COREFILE_PATH + " <<'CHAOS_EOF'\n" + corefile + "CHAOS_EOF");

      if (write.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to write Corefile");
      }

      // Create empty chaos files
      for (String file :
          new String[] {
            CHAOS_HOSTS_FILE, CHAOS_NXDOMAIN_FILE, CHAOS_SERVFAIL_FILE, CHAOS_REFUSED_FILE
          }) {
        final var touch = container.execInContainer("touch", file);
        if (touch.getExitCode() != 0) {
          throw new ChaosOperationFailedException("Failed to create " + file);
        }
      }

      log.debug("Created CoreDNS configuration");
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to configure CoreDNS", e);
    }
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      // Kill CoreDNS
      container.execInContainer("pkill", "-9", "coredns");

      // Clear chaos files
      for (String file :
          new String[] {
            CHAOS_HOSTS_FILE, CHAOS_NXDOMAIN_FILE, CHAOS_SERVFAIL_FILE, CHAOS_REFUSED_FILE
          }) {
        container.execInContainer(Shell.SH, Shell.FLAG_C, "> " + file);
      }

      // Remove iptables rule
      container.execInContainer(
          "iptables",
          "-t",
          "nat",
          "-D",
          "OUTPUT",
          "-p",
          "udp",
          "--dport",
          DNS_PORT,
          "-j",
          "REDIRECT",
          "--to-ports",
          COREDNS_PORT);

      log.info("Reset DNS chaos (stopped CoreDNS, cleared files, removed iptables)");
    } catch (final Exception e) {
      log.warn("Failed to fully reset DNS chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  /** Ensure CoreDNS is running. */
  private void ensureCoreDnsRunning(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer("pgrep", "coredns");
      if (result.getExitCode() == 0) {
        return;
      }
    } catch (final Exception ignored) {
      // Not running
    }

    try {
      // Start CoreDNS
      container.execInContainer(
          Shell.SH, Shell.FLAG_C, "coredns -conf " + COREFILE_PATH + " >/dev/null 2>&1 &");

      // Wait for startup
      final long deadline = System.currentTimeMillis() + COREDNS_STARTUP_TIMEOUT_MS;
      boolean ready = false;

      while (System.currentTimeMillis() < deadline) {
        try {
          final var check = container.execInContainer("pgrep", "coredns");
          if (check.getExitCode() == 0) {
            ready = true;
            break;
          }
        } catch (final Exception ignored) {
          // Continue
        }
        Thread.sleep(COREDNS_POLL_INTERVAL_MS);
      }

      if (!ready) {
        throw new ChaosOperationFailedException(
            "CoreDNS did not start within " + COREDNS_STARTUP_TIMEOUT_MS + "ms");
      }

      log.info("Started CoreDNS on localhost:{}", COREDNS_PORT);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted while starting CoreDNS", e);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start CoreDNS", e);
    }
  }

  /** Ensure DNS redirect is configured. */
  private void ensureDnsRedirect(final GenericContainer<?> container) {
    try {
      final var check =
          container.execInContainer(
              "iptables",
              "-t",
              "nat",
              "-C",
              "OUTPUT",
              "-p",
              "udp",
              "--dport",
              DNS_PORT,
              "-j",
              "REDIRECT",
              "--to-ports",
              COREDNS_PORT);

      if (check.getExitCode() == 0) {
        return;
      }
    } catch (final Exception ignored) {
      // Rule doesn't exist
    }

    try {
      final var add =
          container.execInContainer(
              "iptables",
              "-t",
              "nat",
              "-A",
              "OUTPUT",
              "-p",
              "udp",
              "--dport",
              DNS_PORT,
              "-j",
              "REDIRECT",
              "--to-ports",
              COREDNS_PORT);

      if (add.getExitCode() != 0) {
        if (add.getStderr().contains("Permission denied")
            || add.getStderr().contains("Operation not permitted")) {
          throw new ChaosOperationFailedException(
              "DNS chaos requires NET_ADMIN capability - add .withCapAdd(Capability.NET_ADMIN)");
        }
        throw new ChaosOperationFailedException("Failed to add iptables rule: " + add.getStderr());
      }

      log.info("Installed DNS redirect: port {} → {}", DNS_PORT, COREDNS_PORT);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to install DNS redirect", e);
    }
  }

  /** Validate hostname format. */
  private void validateHostname(final String hostname) {
    if (!VALID_HOSTNAME.matcher(hostname).matches()) {
      throw new IllegalArgumentException("Invalid hostname format: " + hostname);
    }
  }

  /** Validate IP address format. */
  private void validateIpAddress(final String ip) {
    if (!VALID_IP.matcher(ip).matches()) {
      throw new IllegalArgumentException("Invalid IP address format: " + ip);
    }
  }

  /** Escape string for printf (prevent injection). */
  private String escapeForPrintf(final String input) {
    // Quote for printf safety
    return "'" + input.replace("'", "'\\''") + "'";
  }

  /** Validate container is running. */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }

  /** DNS error types. */
  public enum DnsErrorType {
    NXDOMAIN,
    SERVFAIL,
    REFUSED
  }
}
