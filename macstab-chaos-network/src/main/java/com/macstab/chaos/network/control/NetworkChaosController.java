/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network.control;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.ContainerIdFormatter;
import com.macstab.chaos.core.util.ContainerNetworkUtils;
import com.macstab.chaos.network.exception.NetworkChaosException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NetworkChaosController {
  /**
   * Default network interface for traffic control.
   *
   * <p>Standard container network interface. Override via constructor if using custom networking.
   */
  private static final String DEFAULT_networkInterface = "eth0";

  private final List<GenericContainer<?>> allContainers;
  private final String networkInterface;

  // Track which containers have chaos applied (for cleanup)
  private final Map<String, Boolean> chaosApplied = new ConcurrentHashMap<>();

  // Track current chaos state for observability
  private final Map<String, ChaosState> chaosState = new ConcurrentHashMap<>();

  /**
   * Creates a network chaos controller with default network interface (eth0).
   *
   * @param allContainers all containers in the cluster
   * @throws NullPointerException if allContainers is null
   */
  public NetworkChaosController(final List<GenericContainer<?>> allContainers) {
    this(allContainers, DEFAULT_networkInterface);
  }

  /**
   * Creates a network chaos controller with custom network interface.
   *
   * <p><strong>Use Case:</strong> Custom container networking (e.g., multiple NICs, custom
   * bridges).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // For containers with custom network interface
   * NetworkChaosController chaos = new NetworkChaosController(containers, "eth1");
   * }</pre>
   *
   * @param allContainers all containers in the cluster
   * @param networkInterface network interface name (e.g., "eth0", "eth1", "wlan0")
   * @throws NullPointerException if allContainers or networkInterface is null
   * @throws IllegalArgumentException if networkInterface is blank
   */
  public NetworkChaosController(
      final List<GenericContainer<?>> allContainers, final String networkInterface) {
    this.allContainers = List.copyOf(Objects.requireNonNull(allContainers, "allContainers"));
    this.networkInterface = Objects.requireNonNull(networkInterface, "networkInterface");

    if (networkInterface.isBlank()) {
      throw new IllegalArgumentException("Network interface cannot be blank");
    }
  }

  /**
   * Injects network latency to a container.
   *
   * <p><strong>Use Case:</strong> Simulate slow networks, cross-region replication, WAN latency.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Simulate US-East to EU-West latency (80ms)
   * chaos.injectLatency(replicaEU, Duration.ofMillis(80));
   *
   * // Test if replication lag is acceptable
   * redisTemplate.opsForValue().set("key", "value");
   * Thread.sleep(100);
   * assertThat(getFromReplica("key")).isEqualTo("value");
   * }</pre>
   *
   * @param container the container to inject latency into
   * @param delay network delay (e.g., 100ms for cross-datacenter)
   * @throws NullPointerException if container or delay is null
   * @throws IllegalArgumentException if delay is negative
   * @throws NetworkChaosException if operation fails (missing tc, NET_ADMIN, etc.)
   */
  public void injectLatency(final GenericContainer<?> container, final Duration delay) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(delay, "delay");

    if (delay.isNegative()) {
      throw new IllegalArgumentException("Delay cannot be negative: " + delay);
    }

    final String containerId = container.getContainerId();
    final long delayMs = delay.toMillis();

    log.info(
        "🐌 Injecting {}ms latency to container: {}",
        delayMs,
        ContainerIdFormatter.truncate(containerId));

    try {
      // Remove existing tc rules first
      resetInternal(container);

      // Add latency using tc netem
      final var result =
          container.execInContainer(
              "tc",
              "qdisc",
              "add",
              "dev",
              networkInterface,
              "root",
              "netem",
              "delay",
              delayMs + "ms");

      if (result.getExitCode() != 0) {
        final String stdout = result.getStdout().trim();
        final String stderr = result.getStderr().trim();
        final String errorMsg =
            "tc command failed (exit code "
                + result.getExitCode()
                + ")"
                + (stderr.isEmpty() ? "" : "\nstderr: " + stderr)
                + (stdout.isEmpty() ? "" : "\nstdout: " + stdout);
        throw new NetworkChaosException("injectLatency", containerId, errorMsg);
      }

      chaosApplied.put(containerId, true);
      chaosState.put(containerId, ChaosState.latency(delay));
      log.info("✓ Latency injection successful: {}", ContainerIdFormatter.truncate(containerId));

    } catch (Exception e) {
      log.error("✗ Failed to inject latency: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new NetworkChaosException("injectLatency", containerId, getErrorMessage(e), e);
    }
  }

  /**
   * Injects packet loss to a container.
   *
   * <p><strong>Use Case:</strong> Simulate unreliable networks, WiFi, mobile networks.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Simulate 5% packet loss (typical of poor WiFi)
   * chaos.injectPacketLoss(sentinel1, 0.05);
   *
   * // Test if Sentinel can still reach quorum
   * control.triggerFailover();
   * assertThat(control.getMaster()).isNotNull();
   * }</pre>
   *
   * @param container the container to inject packet loss into
   * @param lossPercentage packet loss percentage (0.0 to 1.0, e.g., 0.05 = 5%)
   * @throws NullPointerException if container is null
   * @throws IllegalArgumentException if lossPercentage is not in [0.0, 1.0]
   * @throws NetworkChaosException if operation fails
   */
  public void injectPacketLoss(final GenericContainer<?> container, final double lossPercentage) {
    Objects.requireNonNull(container, "container");

    if (lossPercentage < 0.0 || lossPercentage > 1.0) {
      throw new IllegalArgumentException(
          "Loss percentage must be in [0.0, 1.0]: " + lossPercentage);
    }

    final String containerId = container.getContainerId();
    final double lossPercent = lossPercentage * 100;

    log.info(
        "📉 Injecting {}% packet loss to container: {}",
        lossPercent, ContainerIdFormatter.truncate(containerId));

    try {
      // Remove existing tc rules first
      resetInternal(container);

      // Add packet loss using tc netem
      final var result =
          container.execInContainer(
              "tc",
              "qdisc",
              "add",
              "dev",
              networkInterface,
              "root",
              "netem",
              "loss",
              lossPercent + "%");

      if (result.getExitCode() != 0) {
        throw new NetworkChaosException(
            "injectPacketLoss", containerId, "tc command failed: " + result.getStderr().trim());
      }

      chaosApplied.put(containerId, true);
      chaosState.put(containerId, ChaosState.packetLoss(lossPercentage));
      log.info(
          "✓ Packet loss injection successful: {}", ContainerIdFormatter.truncate(containerId));

    } catch (Exception e) {
      log.error(
          "✗ Failed to inject packet loss: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new NetworkChaosException("injectPacketLoss", containerId, getErrorMessage(e), e);
    }
  }

  /**
   * Injects network jitter (latency variance) to a container.
   *
   * <p><strong>Use Case:</strong> Simulate variable latency, congested networks.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Simulate 50ms base latency with ±25ms jitter
   * chaos.injectLatencyWithJitter(replica, Duration.ofMillis(50), Duration.ofMillis(25));
   *
   * // Actual latency will vary between 25ms-75ms
   * // Test if system handles variable response times
   * }</pre>
   *
   * @param container the container to inject jitter into
   * @param baseLatency base network delay
   * @param jitter latency variance (±jitter around baseLatency)
   * @throws NullPointerException if any parameter is null
   * @throws IllegalArgumentException if baseLatency or jitter is negative
   * @throws NetworkChaosException if operation fails
   */
  public void injectLatencyWithJitter(
      final GenericContainer<?> container, final Duration baseLatency, final Duration jitter) {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(baseLatency, "baseLatency");
    Objects.requireNonNull(jitter, "jitter");

    if (baseLatency.isNegative()) {
      throw new IllegalArgumentException("Base latency cannot be negative: " + baseLatency);
    }
    if (jitter.isNegative()) {
      throw new IllegalArgumentException("Jitter cannot be negative: " + jitter);
    }

    final String containerId = container.getContainerId();
    final long delayMs = baseLatency.toMillis();
    final long jitterMs = jitter.toMillis();

    log.info(
        "📊 Injecting {}ms latency ±{}ms jitter to container: {}",
        delayMs,
        jitterMs,
        ContainerIdFormatter.truncate(containerId));

    try {
      // Remove existing tc rules first
      resetInternal(container);

      // Add latency with jitter using tc netem
      final var result =
          container.execInContainer(
              "tc",
              "qdisc",
              "add",
              "dev",
              networkInterface,
              "root",
              "netem",
              "delay",
              delayMs + "ms",
              jitterMs + "ms");

      if (result.getExitCode() != 0) {
        throw new NetworkChaosException(
            "injectLatencyWithJitter",
            containerId,
            "tc command failed: " + result.getStderr().trim());
      }

      chaosApplied.put(containerId, true);
      chaosState.put(containerId, ChaosState.jitter(baseLatency, jitter));
      log.info("✓ Jitter injection successful: {}", ContainerIdFormatter.truncate(containerId));

    } catch (Exception e) {
      log.error("✗ Failed to inject jitter: {}", ContainerIdFormatter.truncate(containerId), e);
      throw new NetworkChaosException(
          "injectLatencyWithJitter", containerId, getErrorMessage(e), e);
    }
  }

  /**
   * Creates a network partition between two containers.
   *
   * <p><strong>Use Case:</strong> Simulate split-brain scenarios, network partitions.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Replica cannot reach master (but clients can reach both)
   * chaos.partitionFrom(replica, master);
   *
   * // Test: Does Sentinel prevent split-brain?
   * // Replica should NOT promote itself to master
   * assertThat(countMasters()).isEqualTo(1);
   * }</pre>
   *
   * @param source the container that cannot reach target
   * @param target the container that source cannot reach
   * @throws NullPointerException if any parameter is null
   * @throws NetworkChaosException if operation fails
   */
  public void partitionFrom(final GenericContainer<?> source, final GenericContainer<?> target) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");

    final String sourceId = source.getContainerId();
    final String targetIp = getContainerIp(target);

    log.info(
        "🚧 Creating network partition: {} cannot reach {}",
        ContainerIdFormatter.truncate(sourceId),
        ContainerIdFormatter.truncate(target.getContainerId()));

    try {
      // Block all traffic to target IP using iptables
      final var result =
          source.execInContainer("iptables", "-A", "OUTPUT", "-d", targetIp, "-j", "DROP");

      if (result.getExitCode() != 0) {
        throw new NetworkChaosException(
            "partitionFrom", sourceId, "iptables command failed: " + result.getStderr().trim());
      }

      chaosApplied.put(sourceId, true);
      chaosState.put(sourceId, ChaosState.partition(target.getContainerId()));
      log.info("✓ Network partition created: {}", ContainerIdFormatter.truncate(sourceId));

    } catch (Exception e) {
      log.error("✗ Failed to create partition: {}", ContainerIdFormatter.truncate(sourceId), e);
      throw new NetworkChaosException("partitionFrom", sourceId, getErrorMessage(e), e);
    }
  }

  /**
   * Removes all network chaos from a container.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * // Inject chaos
   * chaos.injectLatency(replica, Duration.ofMillis(100));
   *
   * // Test something...
   *
   * // Clean up
   * chaos.reset(replica);  // Container back to normal
   * }</pre>
   *
   * @param container the container to reset
   * @throws NullPointerException if container is null
   * @throws NetworkChaosException if operation fails
   */
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");

    final String containerId = container.getContainerId();

    if (!chaosApplied.containsKey(containerId)) {
      log.debug(
          "No chaos applied to container, skipping reset: {}",
          ContainerIdFormatter.truncate(containerId));
      return;
    }

    log.info(
        "🔄 Resetting network chaos for container: {}", ContainerIdFormatter.truncate(containerId));
    resetInternal(container);
    chaosApplied.remove(containerId);
    chaosState.remove(containerId);
    log.info("✓ Network chaos reset successful: {}", ContainerIdFormatter.truncate(containerId));
  }

  /**
   * Removes all network chaos from all containers.
   *
   * <p><strong>Use Case:</strong> Cleanup after test suite, reset all containers to normal state.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @AfterEach
   * void cleanup() {
   *     chaos.resetAll();  // Clean up after each test
   * }
   * }</pre>
   */
  public void resetAll() {
    log.info("🔄 Resetting network chaos for all containers");
    int resetCount = 0;

    for (final GenericContainer<?> container : allContainers) {
      try {
        if (container.isRunning() && chaosApplied.containsKey(container.getContainerId())) {
          resetInternal(container);
          resetCount++;
        }
      } catch (Exception e) {
        log.warn(
            "Failed to reset chaos for container: {}",
            ContainerIdFormatter.truncate(container.getContainerId()),
            e);
      }
    }

    chaosApplied.clear();
    chaosState.clear();
    log.info("✓ Network chaos reset for {} container(s)", resetCount);
  }

  /**
   * Gets the current chaos state for a container.
   *
   * <p><strong>Use Case:</strong> Observe and verify what chaos is currently active on a container.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * chaos.injectLatency(redis, Duration.ofMillis(100));
   *
   * Optional<ChaosState> state = chaos.getChaosState(redis);
   * if (state.isPresent()) {
   *     System.out.println("Type: " + state.get().getType());
   *     System.out.println("Details: " + state.get().getDetails());
   *     System.out.println("Active for: " + state.get().getDurationSinceApplied());
   * }
   * }</pre>
   *
   * @param container the container to check
   * @return current chaos state, or empty if no chaos is active
   * @throws NullPointerException if container is null
   */
  public Optional<ChaosState> getChaosState(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");
    return Optional.ofNullable(chaosState.get(container.getContainerId()));
  }

  /**
   * Checks if any chaos is currently active on a container.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * if (chaos.hasChaos(redis)) {
   *     System.out.println("Chaos is active!");
   * }
   * }</pre>
   *
   * @param container the container to check
   * @return {@code true} if chaos is active, {@code false} otherwise
   * @throws NullPointerException if container is null
   */
  public boolean hasChaos(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container");
    return chaosApplied.containsKey(container.getContainerId());
  }

  // ==================== Internal Helpers ====================

  /**
   * Internal reset without logging (used by other methods).
   *
   * @param container container to reset
   */
  private void resetInternal(final GenericContainer<?> container) {
    try {
      // Remove tc rules (may fail if no rules exist - that's OK)
      container.execInContainer("tc", "qdisc", "del", "dev", networkInterface, "root");
    } catch (Exception e) {
      // Ignore - tc fails if no rules exist (expected)
      log.trace("tc qdisc del failed (expected if no rules): {}", e.getMessage());
    }

    try {
      // Flush iptables OUTPUT chain
      container.execInContainer("iptables", "-F", "OUTPUT");
    } catch (Exception e) {
      // Ignore - iptables may not be available or no rules exist
      log.trace("iptables flush failed (expected if no rules): {}", e.getMessage());
    }
  }

  /**
   * Gets the Docker bridge network IP of a container.
   *
   * <p>Delegates to {@link ContainerNetworkUtils#getContainerBridgeIp(GenericContainer)} which uses
   * Docker inspect to obtain the correct in-network IP — the address that is reachable from inside
   * other containers on the same bridge.
   *
   * @param container container whose bridge IP is needed
   * @return bridge IP address (e.g., "172.18.0.5")
   * @throws NetworkChaosException if resolution fails
   */
  private String getContainerIp(final GenericContainer<?> container) {
    try {
      return ContainerNetworkUtils.getContainerBridgeIp(container);
    } catch (final Exception e) {
      throw new NetworkChaosException(
          "getContainerIp",
          container.getContainerId(),
          "Failed to get container bridge IP: " + e.getMessage(),
          e);
    }
  }

  /**
   * Extracts error message from exception.
   *
   * @param e exception
   * @return error message
   */
  private String getErrorMessage(final Exception e) {
    if (e.getMessage() != null && e.getMessage().contains("tc: not found")) {
      return "Container missing 'tc' command. Ensure iproute2 package is installed in Redis image.";
    }
    if (e.getMessage() != null && e.getMessage().contains("Operation not permitted")) {
      return "Container missing NET_ADMIN capability. Add .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(NET_ADMIN))";
    }
    return e.getMessage() != null ? e.getMessage() : "Unknown error";
  }
}
