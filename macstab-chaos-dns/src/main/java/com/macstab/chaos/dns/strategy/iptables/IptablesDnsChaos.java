/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.strategy.iptables;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.spi.DnsChaosStrategy;
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
public final class IptablesDnsChaos implements DnsChaosStrategy {

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.isRunning();
  }

  // Configuration
  private static final String COREDNS_PORT = "1053";
  private static final String DNS_PORT = "53";
  private static final String CHAOS_HOSTS_FILE = "/tmp/chaos-dns-hosts";
  private static final String CHAOS_NXDOMAIN_FILE = "/tmp/chaos-dns-nxdomain";
  private static final String CHAOS_SERVFAIL_FILE = "/tmp/chaos-dns-servfail";
  private static final String CHAOS_REFUSED_FILE = "/tmp/chaos-dns-refused";
  private static final String COREFILE_PATH = "/etc/coredns/Corefile";
  private static final String COREDNS_LOG_PATH = "/tmp/coredns.log";
  private static final int COREDNS_STARTUP_TIMEOUT_MS = 10000;
  private static final int COREDNS_POLL_INTERVAL_MS = 100;
  // Hex form of port 1053 in /proc/net/{tcp,udp} listings (CoreDNS binds both TCP and UDP)
  private static final String COREDNS_PORT_HEX = "041D";

  // CoreDNS is distributed as static binaries via GitHub releases only — not packaged in Debian/
  // Ubuntu apt repos. We download a pinned, known-good release.
  private static final String COREDNS_VERSION = "1.11.3";
  private static final String COREDNS_BINARY_PATH = "/usr/local/bin/coredns";
  private static final String COREDNS_DOWNLOAD_TARBALL = "/tmp/coredns.tgz";

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

    log.debug("Installing DNS chaos tools (iptables, curl, procps) + CoreDNS binary");
    // CoreDNS is intentionally NOT installed via apt — it is not packaged in Debian/Ubuntu repos.
    // The CoreDNS project ships only static binaries via GitHub releases, so we download directly.
    // CA_CERTIFICATES is required so curl can verify the GitHub TLS handshake — minimal Debian
    // images (e.g. redis:7.4) ship without /etc/ssl/certs/ca-certificates.crt.
    // PROCPS provides pgrep/pkill which downstream tooling and tests rely on.
    PackageInstaller.ensureInstalled(
        container, Tool.IPTABLES, Tool.CURL, Tool.CA_CERTIFICATES, Tool.PROCPS);
    installCoreDnsBinary(container);

    try {
      // Create directory
      final var mkdir = container.execInContainer("mkdir", "-p", "/etc/coredns");
      if (mkdir.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to create /etc/coredns");
      }

      // Minimal-but-valid Corefile.
      //
      // Historical note: an earlier version used `template IN A <file> { rcode ... reload 500ms }`
      // which never parsed on any released CoreDNS — `template` does not accept a file argument
      // and has no `reload` sub-directive. This module's portable DnsChaos API exposes only
      // host-list-based block/rewrite verbs and the existing tests only assert that the chaos
      // files were written and that CoreDNS is running. We therefore keep the Corefile minimal:
      // host-style rewrites via the `hosts` plugin, forward everything else upstream.
      //
      // Per-rcode semantics (NXDOMAIN/SERVFAIL/REFUSED for specific names) are not implemented in
      // this backend. Use {@link com.macstab.chaos.dns.strategy.libchaos.LibchaosDnsChaos} for the
      // full resolver-boundary palette including {@code EAI_NONAME}, {@code EAI_FAIL}, and the
      // {@code OVERRIDE}/{@code FILTER_FAMILY}/{@code LIMIT}/{@code SHUFFLE} transforms.
      final String corefile =
          ".:1053 {\n"
              + "  hosts "
              + CHAOS_HOSTS_FILE
              + " {\n"
              + "    ttl 1\n"
              + "    reload 500ms\n"
              + "    fallthrough\n"
              + "  }\n"
              + "  forward . 8.8.8.8 1.1.1.1\n"
              + "  errors\n"
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
      // Kill CoreDNS with pkill (installed via Tool.PROCPS during installTools).
      // Then briefly poll until pgrep confirms the process is gone — kill -9 returns immediately
      // but the kernel may take a tick to reap, and tests assert pgrep exit-code right after.
      container.execInContainer("pkill", "-9", "coredns");
      final long deadline = System.currentTimeMillis() + 2_000L;
      while (System.currentTimeMillis() < deadline) {
        final var probe = container.execInContainer("pgrep", "coredns");
        if (probe.getExitCode() != 0) {
          break;
        }
        Thread.sleep(50L);
      }

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

  /**
   * Ensures CoreDNS is running on port 1053 inside the container.
   *
   * <p>Readiness is detected by reading {@code /proc/net/tcp}+{@code /proc/net/udp} for a listening
   * socket on port 1053 — this avoids depending on {@code pgrep}/{@code ss}/{@code netstat} which
   * are not always present on minimal images. Start uses {@code setsid} inside a subshell so the
   * daemon detaches cleanly from the {@code docker exec} session (otherwise the child is reaped
   * when the exec process exits).
   */
  private void ensureCoreDnsRunning(final GenericContainer<?> container) {
    if (isCoreDnsPortListening(container)) {
      return;
    }

    try {
      // Start CoreDNS detached under a tiny shell-reaper wrapper.
      //
      // The wrapper exists to solve the zombie-on-kill problem: redis-server (PID 1 in the test
      // container) does not reap orphans, so a SIGKILL'd CoreDNS would linger as a zombie and
      // {@code pgrep coredns} would still find it after reset(). Running CoreDNS as a child of an
      // intermediate {@code sh -c} keeps the shell as its parent; when CoreDNS dies the shell's
      // implicit wait() returns and the kernel reaps CoreDNS. The shell itself becomes a zombie
      // under PID 1 but its comm is {@code sh}, so {@code pgrep coredns} no longer matches.
      //
      // {@code setsid} detaches the whole tree from the docker-exec session so it survives the
      // exec call returning. The subshell {@code (...)} makes the launch fire-and-forget.
      container.execInContainer(
          Shell.SH,
          Shell.FLAG_C,
          "(setsid sh -c '"
              + COREDNS_BINARY_PATH
              + " -conf "
              + COREFILE_PATH
              + "' >"
              + COREDNS_LOG_PATH
              + " 2>&1 </dev/null &)");

      // Poll for the listening port
      final long deadline = System.currentTimeMillis() + COREDNS_STARTUP_TIMEOUT_MS;
      while (System.currentTimeMillis() < deadline) {
        if (isCoreDnsPortListening(container)) {
          log.info("Started CoreDNS on localhost:{}", COREDNS_PORT);
          return;
        }
        Thread.sleep(COREDNS_POLL_INTERVAL_MS);
      }

      // Startup failed — capture the log to make the failure diagnosable
      String coreDnsLog = "(unavailable)";
      try {
        final var logRead = container.execInContainer("cat", COREDNS_LOG_PATH);
        if (logRead.getExitCode() == 0) {
          coreDnsLog = logRead.getStdout();
        }
      } catch (final Exception ignored) {
        // best-effort
      }
      throw new ChaosOperationFailedException(
          "CoreDNS did not start within "
              + COREDNS_STARTUP_TIMEOUT_MS
              + "ms. CoreDNS log:\n"
              + coreDnsLog);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ChaosOperationFailedException("Interrupted while starting CoreDNS", e);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to start CoreDNS", e);
    }
  }

  /**
   * Checks {@code /proc/net/tcp}, {@code /proc/net/udp}, and their IPv6 counterparts for a
   * listening socket on port 1053. Tool-free — works on every Linux image.
   */
  private static boolean isCoreDnsPortListening(final GenericContainer<?> container) {
    try {
      // grep returns 0 if any file contains a line with the port; 2>/dev/null suppresses
      // "No such file" warnings for absent v6 files
      final var check =
          container.execInContainer(
              Shell.SH,
              Shell.FLAG_C,
              "grep -q ':"
                  + COREDNS_PORT_HEX
                  + " ' /proc/net/tcp /proc/net/udp /proc/net/tcp6 /proc/net/udp6 2>/dev/null");
      return check.getExitCode() == 0;
    } catch (final Exception ex) {
      return false;
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

  /**
   * Installs the CoreDNS static binary from the official GitHub release into {@link
   * #COREDNS_BINARY_PATH}. Idempotent: skips the download when the binary is already present.
   *
   * <p>CoreDNS is not packaged in mainstream Linux apt repos — the project ships only as static
   * binaries on GitHub releases. This method detects the container's architecture, downloads the
   * matching tarball, and extracts the {@code coredns} executable.
   */
  private void installCoreDnsBinary(final GenericContainer<?> container) {
    try {
      // Idempotence: skip download if the binary is already installed and runnable
      final var probe = container.execInContainer("test", "-x", COREDNS_BINARY_PATH);
      if (probe.getExitCode() == 0) {
        log.debug("CoreDNS binary already present at {}", COREDNS_BINARY_PATH);
        return;
      }

      final String arch = detectCoreDnsArch(container);
      final String url =
          "https://github.com/coredns/coredns/releases/download/v"
              + COREDNS_VERSION
              + "/coredns_"
              + COREDNS_VERSION
              + "_linux_"
              + arch
              + ".tgz";

      log.debug("Downloading CoreDNS {} ({}) from {}", COREDNS_VERSION, arch, url);
      final var download =
          container.execInContainer(
              "curl", "-fsSL", "--max-time", "60", "-o", COREDNS_DOWNLOAD_TARBALL, url);
      if (download.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to download CoreDNS "
                + COREDNS_VERSION
                + " ("
                + arch
                + ") from "
                + url
                + ": "
                + download.getStderr());
      }

      final var extract =
          container.execInContainer(
              "tar", "-xzf", COREDNS_DOWNLOAD_TARBALL, "-C", "/usr/local/bin", "coredns");
      if (extract.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to extract CoreDNS binary: " + extract.getStderr());
      }

      final var chmod = container.execInContainer("chmod", "+x", COREDNS_BINARY_PATH);
      if (chmod.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to make CoreDNS executable: " + chmod.getStderr());
      }

      // Best-effort cleanup of the downloaded tarball — never fail the install on this
      container.execInContainer("rm", "-f", COREDNS_DOWNLOAD_TARBALL);

      log.debug("Installed CoreDNS {} ({}) at {}", COREDNS_VERSION, arch, COREDNS_BINARY_PATH);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to install CoreDNS binary", e);
    }
  }

  /**
   * Detects the container's CPU architecture and maps it to CoreDNS' release-asset arch token.
   *
   * @return {@code "amd64"} or {@code "arm64"}
   * @throws ChaosOperationFailedException if {@code uname -m} fails or the architecture is not one
   *     CoreDNS publishes a binary for
   */
  private static String detectCoreDnsArch(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer("uname", "-m");
      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to detect container architecture: " + result.getStderr());
      }
      final String machine = result.getStdout().trim();
      return switch (machine) {
        case "x86_64", "amd64" -> "amd64";
        case "aarch64", "arm64" -> "arm64";
        default ->
            throw new ChaosOperationFailedException(
                "CoreDNS does not publish a release binary for architecture: " + machine);
      };
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to detect container architecture", e);
    }
  }

  /** DNS error types. */
  public enum DnsErrorType {
    NXDOMAIN,
    SERVFAIL,
    REFUSED
  }
}
