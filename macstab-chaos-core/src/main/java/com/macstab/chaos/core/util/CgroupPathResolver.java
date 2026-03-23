/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Resolve cgroups v2 path for container.
 *
 * <p><strong>Detection Strategy:</strong>
 *
 * <ol>
 *   <li>Try systemd path: {@code /sys/fs/cgroup/system.slice/docker-<id>.scope}
 *   <li>Try direct path: {@code /sys/fs/cgroup/docker/<id>}
 *   <li>Fail with helpful error
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CgroupPathResolver {

  private CgroupPathResolver() {
    // Utility class
  }

  /**
   * Resolve cgroups v2 path for container.
   *
   * @param container target container
   * @return cgroups v2 path (e.g., "/sys/fs/cgroup/system.slice/docker-abc123.scope")
   * @throws ChaosOperationFailedException if path not found
   */
  public static String resolve(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    final String containerId = container.getContainerId();
    if (containerId == null || containerId.isEmpty()) {
      throw new ChaosOperationFailedException(
          "Container ID is null or empty (container not started?)");
    }

    // Try systemd path first (most common)
    final String systemdPath = "/sys/fs/cgroup/system.slice/docker-" + containerId + ".scope";
    if (Files.exists(Path.of(systemdPath))) {
      log.debug("Resolved cgroup path (systemd): {}", systemdPath);
      return systemdPath;
    }

    // Try direct Docker path
    final String dockerPath = "/sys/fs/cgroup/docker/" + containerId;
    if (Files.exists(Path.of(dockerPath))) {
      log.debug("Resolved cgroup path (docker): {}", dockerPath);
      return dockerPath;
    }

    // Not found
    throw new ChaosOperationFailedException(
        "Could not resolve cgroup path for container: "
            + containerId
            + ". Tried: "
            + systemdPath
            + ", "
            + dockerPath);
  }
}
