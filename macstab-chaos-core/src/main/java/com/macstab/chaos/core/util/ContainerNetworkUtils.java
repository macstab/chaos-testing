/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Map;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Utility for resolving Docker container network addresses.
 *
 * <p><strong>Purpose:</strong> Provide correct container IP resolution for operations that execute
 * <em>inside</em> a container and need to address another container on the same Docker bridge
 * network.
 *
 * <h2>Why {@code container.getHost()} is Wrong for In-Container Use</h2>
 *
 * <p>{@code GenericContainer.getHost()} returns the address from the <strong>test JVM
 * perspective</strong> — the Docker host machine's address. On Docker Desktop (macOS/Windows) this
 * is {@code localhost} / {@code 127.0.0.1}. On Linux it may be the Docker bridge gateway IP.
 *
 * <p>When a command runs <em>inside a container</em> (via {@code execInContainer}), that IP is
 * meaningless — the container's {@code localhost} is itself, not the host. The correct address is
 * the target container's <strong>Docker bridge IP</strong> (e.g., {@code 172.18.0.5}), which is
 * reachable from any container on the same bridge network via kernel network namespace routing.
 *
 * <h2>Correct Approach</h2>
 *
 * <p>Use Docker's container inspect API to get the container's actual bridge network IP:
 *
 * <pre>
 * docker inspect &lt;container-id&gt;
 *   .NetworkSettings.Networks["bridge"].IPAddress → "172.18.0.5"
 * </pre>
 *
 * <p>This IP is:
 *
 * <ul>
 *   <li>Assigned by Docker when the container joins the bridge network
 *   <li>Reachable from all containers on the same bridge (via kernel namespaces)
 *   <li>The correct target for {@code iptables}, {@code tc}, and other in-container network tools
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is stateless and thread-safe. All methods are static.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class ContainerNetworkUtils {

  /** Private constructor — utility class, not instantiable. */
  private ContainerNetworkUtils() {
    throw new UnsupportedOperationException(
        "ContainerNetworkUtils is a utility class and cannot be instantiated");
  }

  /**
   * Resolves the Docker bridge network IP of a container.
   *
   * <p>This is the IP that other containers on the same Docker bridge network use to reach this
   * container. It is correct for use in commands that execute <em>inside</em> a container and need
   * to address another container (e.g., {@code iptables}, {@code tc}, network partitioning).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * String targetIp = ContainerNetworkUtils.getContainerBridgeIp(targetContainer);
   * // Returns e.g. "172.18.0.5"
   *
   * // Correct: iptables runs inside source container, targets bridge IP of target container
   * source.execInContainer("iptables", "-A", "OUTPUT", "-d", targetIp, "-j", "DROP");
   * }</pre>
   *
   * @param container the container whose bridge IP is needed (must be running)
   * @return the container's Docker bridge network IP address (e.g., {@code "172.18.0.5"})
   * @throws NullPointerException if container is null
   * @throws ChaosOperationFailedException if the IP cannot be resolved (container not on any
   *     network, Docker inspect fails, or IP is empty)
   */
  public static String getContainerBridgeIp(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    try {
      final var networkSettings =
          container
              .getDockerClient()
              .inspectContainerCmd(container.getContainerId())
              .exec()
              .getNetworkSettings();

      if (networkSettings == null) {
        throw new ChaosOperationFailedException(
            "Container has no network settings: "
                + ContainerIdFormatter.truncate(container.getContainerId()));
      }

      final Map<String, ContainerNetwork> networks = networkSettings.getNetworks();

      if (networks == null || networks.isEmpty()) {
        throw new ChaosOperationFailedException(
            "Container is not attached to any Docker network: "
                + ContainerIdFormatter.truncate(container.getContainerId()));
      }

      final String ip =
          networks.values().stream()
              .map(ContainerNetwork::getIpAddress)
              .filter(addr -> addr != null && !addr.isBlank())
              .findFirst()
              .orElseThrow(
                  () ->
                      new ChaosOperationFailedException(
                          "No usable IP address found for container: "
                              + ContainerIdFormatter.truncate(container.getContainerId())));

      return ip;

    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to resolve bridge IP for container "
              + ContainerIdFormatter.truncate(container.getContainerId())
              + ": "
              + e.getMessage(),
          e);
    }
  }
}
