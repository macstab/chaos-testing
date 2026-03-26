/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.NetworkChaos;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.util.PackageInstaller;

/**
 * Network chaos using tc (traffic control).
 *
 * <p><strong>REQUIRES NET_ADMIN CAPABILITY</strong> for tc/iptables.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class TcNetworkChaos implements NetworkChaos {
  private static final String INTERFACE = "eth0";
  private static final Set<String> activeContainers = new HashSet<>();

  @Override
  public void injectLatency(final GenericContainer<?> container, final Duration latency) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(latency, "latency must not be null");

    if (latency.isNegative()) {
      throw new ChaosConfigurationException("latency must not be negative");
    }

    validateContainerRunning(container);
    installTools(container);
    ensureQdiscSetup(container);

    final long ms = latency.toMillis();

    try {
      final var result =
          container.execInContainer(
              "tc", "qdisc", "change", "dev", INTERFACE, "root", "netem", "delay", ms + "ms");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to inject latency: " + result.getStderr());
      }

      log.info("Injected {}ms latency on {}", ms, INTERFACE);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to inject latency", e);
    }
  }

  @Override
  public void injectLatencyWithJitter(
      final GenericContainer<?> container, final Duration latency, final Duration jitter) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(latency, "latency must not be null");
    Objects.requireNonNull(jitter, "jitter must not be null");

    if (latency.isNegative() || jitter.isNegative()) {
      throw new ChaosConfigurationException("latency and jitter must not be negative");
    }

    validateContainerRunning(container);
    installTools(container);
    ensureQdiscSetup(container);

    final long ms = latency.toMillis();
    final long jitterMs = jitter.toMillis();

    try {
      final var result =
          container.execInContainer(
              "tc",
              "qdisc",
              "change",
              "dev",
              INTERFACE,
              "root",
              "netem",
              "delay",
              ms + "ms",
              jitterMs + "ms");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to inject latency with jitter: " + result.getStderr());
      }

      log.info("Injected {}ms ± {}ms latency on {}", ms, jitterMs, INTERFACE);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to inject latency with jitter", e);
    }
  }

  @Override
  public void injectPacketLoss(final GenericContainer<?> container, final double percentage) {
    Objects.requireNonNull(container, "container must not be null");

    if (percentage < 0.0 || percentage > 1.0) {
      throw new ChaosConfigurationException(
          String.format("percentage must be in [0.0, 1.0], got: %.2f", percentage));
    }

    validateContainerRunning(container);
    installTools(container);
    ensureQdiscSetup(container);

    final double percent = percentage * 100.0;

    try {
      final var result =
          container.execInContainer(
              "tc",
              "qdisc",
              "change",
              "dev",
              INTERFACE,
              "root",
              "netem",
              "loss",
              String.format("%.2f%%", percent));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to inject packet loss: " + result.getStderr());
      }

      log.info("Injected {:.0%} packet loss on {}", percentage, INTERFACE);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to inject packet loss", e);
    }
  }

  @Override
  public void injectCorrelatedPacketLoss(
      final GenericContainer<?> container, final double percentage, final double correlation) {
    Objects.requireNonNull(container, "container must not be null");

    if (percentage < 0.0 || percentage > 1.0) {
      throw new ChaosConfigurationException(
          String.format("percentage must be in [0.0, 1.0], got: %.2f", percentage));
    }

    if (correlation < 0.0 || correlation > 1.0) {
      throw new ChaosConfigurationException(
          String.format("correlation must be in [0.0, 1.0], got: %.2f", correlation));
    }

    validateContainerRunning(container);
    installTools(container);
    ensureQdiscSetup(container);

    final double percent = percentage * 100.0;
    final double corr = correlation * 100.0;

    try {
      final var result =
          container.execInContainer(
              "tc",
              "qdisc",
              "change",
              "dev",
              INTERFACE,
              "root",
              "netem",
              "loss",
              String.format("%.2f%% %.2f%%", percent, corr));

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to inject correlated packet loss: " + result.getStderr());
      }

      log.info(
          "Injected {:.0%} packet loss (corr: {:.0%}) on {}", percentage, correlation, INTERFACE);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to inject correlated packet loss", e);
    }
  }

  @Override
  public void limitBandwidth(final GenericContainer<?> container, final String rate) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rate, "rate must not be null");

    validateContainerRunning(container);
    installTools(container);

    try {
      // Use tbf (token bucket filter) for bandwidth limiting
      final var result =
          container.execInContainer(
              "tc", "qdisc", "add", "dev", INTERFACE, "root", "tbf", "rate", rate, "burst",
              "32kbit", "latency", "400ms");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to limit bandwidth: " + result.getStderr());
      }

      activeContainers.add(container.getContainerId());
      log.info("Limited bandwidth to {} on {}", rate, INTERFACE);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to limit bandwidth", e);
    }
  }

  @Override
  public void partitionFrom(final GenericContainer<?> container, final GenericContainer<?> target) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(target, "target must not be null");

    validateContainerRunning(container);
    installTools(container);

    final String targetIp = target.getHost();

    try {
      // Block traffic to target using iptables
      final var result =
          container.execInContainer("iptables", "-A", "OUTPUT", "-d", targetIp, "-j", "DROP");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to partition from target: " + result.getStderr());
      }

      log.info(
          "Partitioned {} from {} (blocked {})",
          container.getContainerId().substring(0, 12),
          target.getContainerId().substring(0, 12),
          targetIp);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to partition from target", e);
    }
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    PackageInstaller.install(container, "iproute2", "iptables");
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      // Remove qdisc
      final var delQdisc =
          container.execInContainer("tc", "qdisc", "del", "dev", INTERFACE, "root");

      if (delQdisc.getExitCode() != 0 && !delQdisc.getStderr().contains("Cannot delete qdisc")) {
        log.warn("Failed to remove qdisc: {}", delQdisc.getStderr());
      }

      // Flush iptables OUTPUT chain
      final var flush = container.execInContainer("iptables", "-F", "OUTPUT");

      if (flush.getExitCode() != 0) {
        log.warn("Failed to flush iptables: {}", flush.getStderr());
      }

      activeContainers.remove(container.getContainerId());
      log.info("Reset network chaos (removed qdisc, flushed iptables)");
    } catch (final Exception e) {
      log.warn("Failed to reset network chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  /** Ensure netem qdisc is set up. */
  private void ensureQdiscSetup(final GenericContainer<?> container) {
    if (activeContainers.contains(container.getContainerId())) {
      return; // Already setup
    }

    try {
      // Check if qdisc exists
      final var check = container.execInContainer("tc", "qdisc", "show", "dev", INTERFACE);

      if (check.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to check qdisc: " + check.getStderr());
      }

      if (check.getStdout().contains("noqueue")) {
        // Add netem qdisc
        final var add =
            container.execInContainer("tc", "qdisc", "add", "dev", INTERFACE, "root", "netem");

        if (add.getExitCode() != 0) {
          if (add.getStderr().contains("Operation not permitted")) {
            throw new ChaosOperationFailedException(
                "Network chaos requires NET_ADMIN capability - add .withCapAdd(Capability.NET_ADMIN)");
          }
          throw new ChaosOperationFailedException("Failed to add qdisc: " + add.getStderr());
        }

        log.debug("Added netem qdisc on {}", INTERFACE);
      }

      activeContainers.add(container.getContainerId());
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to setup qdisc", e);
    }
  }

  /** Validate container is running. */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }
}
