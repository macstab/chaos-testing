/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.NetworkChaos;
import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.ContainerNetworkUtils;
import com.macstab.chaos.core.util.PackageInstaller;

import lombok.extern.slf4j.Slf4j;

/**
 * Network chaos using Linux Traffic Control ({@code tc}) and {@code iptables}.
 *
 * <p>Registered as the default {@link NetworkChaos} SPI implementation via {@code
 * META-INF/services/com.macstab.chaos.core.api.NetworkChaos}.
 *
 * <p><strong>REQUIRES NET_ADMIN CAPABILITY</strong> — add to container:
 *
 * <pre>{@code
 * .withCreateContainerCmdModifier(cmd ->
 *     cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN))
 * }</pre>
 *
 * <h2>Qdisc Exclusivity</h2>
 *
 * <p>A network interface supports only one root qdisc at a time. The two qdisc types used here are
 * mutually exclusive:
 *
 * <ul>
 *   <li><strong>netem</strong> — used by latency, jitter, packet loss operations
 *   <li><strong>tbf</strong> (token bucket filter) — used by bandwidth limiting
 * </ul>
 *
 * <p>Mixing them on the same container requires a {@link #reset(GenericContainer)} call between
 * uses. Attempting to mix without reset throws {@link ChaosConfigurationException}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is thread-safe. Instance state is held in a {@link ConcurrentHashMap} keyed by
 * container ID. Multiple threads may call methods concurrently on the same instance, and multiple
 * instances are fully isolated from each other.
 *
 * <p><strong>Note on instance scope:</strong> Create one {@code TcNetworkChaos} instance per test
 * class (or per test suite). Do not share instances across unrelated test classes — each instance
 * maintains its own qdisc state tracking.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class TcNetworkChaos implements NetworkChaos {

  /** Default network interface inside Linux containers. */
  private static final String INTERFACE = "eth0";

  /**
   * Tracks the active qdisc type per container ID.
   *
   * <p>Instance-scoped (not static) to ensure full isolation between {@code TcNetworkChaos}
   * instances. Keyed by container ID; value is the qdisc type currently installed.
   */
  private final ConcurrentHashMap<String, QdiscType> activeQdiscs = new ConcurrentHashMap<>();

  /**
   * Represents the qdisc type currently installed on a container's network interface.
   *
   * <p>Used internally to detect and prevent incompatible qdisc mixing.
   */
  private enum QdiscType {
    /** {@code tc netem} — supports latency, jitter, packet loss. */
    NETEM,
    /** {@code tc tbf} (token bucket filter) — supports bandwidth limiting. */
    TBF
  }

  @Override
  public void injectLatency(final GenericContainer<?> container, final Duration latency) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(latency, "latency must not be null");

    if (latency.isNegative()) {
      throw new ChaosConfigurationException("latency must not be negative");
    }

    validateContainerRunning(container);
    requireQdiscType(container, QdiscType.NETEM);
    installTools(container);
    ensureNetemQdisc(container);

    final long ms = latency.toMillis();

    try {
      final var result =
          container.execInContainer(
              "tc", "qdisc", "change", "dev", INTERFACE, "root", "netem", "delay", ms + "ms");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to inject latency: " + result.getStderr());
      }

      log.info("Injected {}ms latency on {}", ms, INTERFACE);
    } catch (final ChaosOperationFailedException e) {
      throw e;
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

    if (latency.isNegative()) {
      throw new ChaosConfigurationException("latency must not be negative");
    }
    if (jitter.isNegative()) {
      throw new ChaosConfigurationException("jitter must not be negative");
    }

    validateContainerRunning(container);
    requireQdiscType(container, QdiscType.NETEM);
    installTools(container);
    ensureNetemQdisc(container);

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
    } catch (final ChaosOperationFailedException e) {
      throw e;
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
    requireQdiscType(container, QdiscType.NETEM);
    installTools(container);
    ensureNetemQdisc(container);

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

      log.info("Injected {:.1f}% packet loss on {}", String.format("%.1f", percent), INTERFACE);
    } catch (final ChaosOperationFailedException e) {
      throw e;
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
    requireQdiscType(container, QdiscType.NETEM);
    installTools(container);
    ensureNetemQdisc(container);

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
          "Injected {}% packet loss (corr: {}%) on {}",
          String.format("%.1f", percent), String.format("%.1f", corr), INTERFACE);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to inject correlated packet loss", e);
    }
  }

  @Override
  public void limitBandwidth(final GenericContainer<?> container, final String rate) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rate, "rate must not be null");

    validateContainerRunning(container);
    requireQdiscType(container, QdiscType.TBF);
    installTools(container);

    try {
      final var result =
          container.execInContainer(
              "tc", "qdisc", "add", "dev", INTERFACE, "root", "tbf", "rate", rate, "burst",
              "32kbit", "latency", "400ms");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to limit bandwidth: " + result.getStderr());
      }

      activeQdiscs.put(container.getContainerId(), QdiscType.TBF);
      log.info("Limited bandwidth to {} on {}", rate, INTERFACE);
    } catch (final ChaosOperationFailedException e) {
      throw e;
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

    // Resolve the target's bridge network IP — the address reachable from inside
    // the source container via kernel network namespace routing. This is the container's
    // Docker bridge IP (e.g. 172.18.0.5), NOT container.getHost() which returns the
    // Docker host address from the test JVM's perspective.
    final String targetIp = ContainerNetworkUtils.getContainerBridgeIp(target);

    try {
      final var result =
          container.execInContainer("iptables", "-A", "OUTPUT", "-d", targetIp, "-j", "DROP");

      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "Failed to partition from target: " + result.getStderr());
      }

      log.info(
          "Partitioned {} from {} (blocked bridge IP {})",
          container.getContainerId().substring(0, 12),
          target.getContainerId().substring(0, 12),
          targetIp);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to partition from target", e);
    }
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    PackageInstaller.ensureInstalled(container, Tool.IPROUTE, Tool.IPTABLES);
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (!container.isRunning()) {
      return;
    }

    try {
      final var delQdisc =
          container.execInContainer("tc", "qdisc", "del", "dev", INTERFACE, "root");

      if (delQdisc.getExitCode() != 0 && !delQdisc.getStderr().contains("Cannot delete qdisc")) {
        log.warn("Failed to remove qdisc: {}", delQdisc.getStderr());
      }

      final var flush = container.execInContainer("iptables", "-F", "OUTPUT");

      if (flush.getExitCode() != 0) {
        log.warn("Failed to flush iptables: {}", flush.getStderr());
      }

      activeQdiscs.remove(container.getContainerId());
      log.info("Reset network chaos (removed qdisc, flushed iptables)");
    } catch (final Exception e) {
      log.warn("Failed to reset network chaos", e);
    }
  }

  @Override
  public boolean isSupported() {
    return true;
  }

  // ==================== Private Helpers ====================

  /**
   * Ensures the netem qdisc is installed on the container's network interface.
   *
   * <p>Checks whether netem is already present (via the {@code activeQdiscs} cache). If not,
   * queries {@code tc qdisc show} to detect the current state, then installs netem if the interface
   * is using the default {@code noqueue} discipline.
   *
   * <p>On success, registers {@link QdiscType#NETEM} in {@code activeQdiscs}.
   *
   * @param container target container (must be running)
   * @throws ChaosOperationFailedException if qdisc setup fails or NET_ADMIN is missing
   */
  private void ensureNetemQdisc(final GenericContainer<?> container) {
    if (activeQdiscs.containsKey(container.getContainerId())) {
      return; // Already set up — skip (idempotent)
    }

    try {
      final var check = container.execInContainer("tc", "qdisc", "show", "dev", INTERFACE);

      if (check.getExitCode() != 0) {
        throw new ChaosOperationFailedException("Failed to check qdisc: " + check.getStderr());
      }

      if (check.getStdout().contains("noqueue")) {
        final var add =
            container.execInContainer("tc", "qdisc", "add", "dev", INTERFACE, "root", "netem");

        if (add.getExitCode() != 0) {
          if (add.getStderr().contains("Operation not permitted")) {
            throw new ChaosOperationFailedException(
                "Network chaos requires NET_ADMIN capability"
                    + " — add .withCreateContainerCmdModifier(cmd ->"
                    + " cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN))");
          }
          throw new ChaosOperationFailedException("Failed to add netem qdisc: " + add.getStderr());
        }

        log.debug("Installed netem qdisc on {}", INTERFACE);
      }

      activeQdiscs.put(container.getContainerId(), QdiscType.NETEM);
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException("Failed to setup netem qdisc", e);
    }
  }

  /**
   * Validates that the container does not already have an incompatible qdisc installed.
   *
   * <p>Since a network interface supports only one root qdisc, mixing {@code netem} and {@code tbf}
   * operations without a {@link #reset(GenericContainer)} between them is a configuration error.
   *
   * @param container target container
   * @param required qdisc type required by the calling operation
   * @throws ChaosConfigurationException if an incompatible qdisc is already active
   */
  private void requireQdiscType(final GenericContainer<?> container, final QdiscType required) {
    final QdiscType active = activeQdiscs.get(container.getContainerId());

    if (active != null && active != required) {
      throw new ChaosConfigurationException(
          String.format(
              "Cannot apply %s operation: %s qdisc is already active on this container."
                  + " Call reset() before switching between bandwidth limiting and netem operations.",
              required.name().toLowerCase(), active.name().toLowerCase()));
    }
  }

  /**
   * Validates that the container is running before executing any operation.
   *
   * @param container target container
   * @throws IllegalStateException if the container is not running
   */
  private void validateContainerRunning(final GenericContainer<?> container) {
    if (!container.isRunning()) {
      throw new IllegalStateException("Container must be running");
    }
  }
}
