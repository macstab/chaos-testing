/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import lombok.extern.slf4j.Slf4j;

/**
 * Automatic shell detection for containers.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ShellDetector {

  private ShellDetector() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Detect best available shell in container.
   *
   * <p>Priority order: bash → sh (busybox/dash)
   *
   * @param container container
   * @return shell instance
   * @throws IllegalStateException if no shell found
   */
  public static Shell detect(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    // Try bash first (most capable)
    final Shell bash = new BashShell();
    if (bash.isAvailable(container)) {
      log.debug("Detected shell: {}", bash.getType());
      return bash;
    }

    // Fallback to sh (busybox on Alpine, dash on Debian minimal)
    final Shell sh = new BusyboxShell();
    if (sh.isAvailable(container)) {
      log.debug("Detected shell: {}", sh.getType());
      return sh;
    }

    throw new IllegalStateException("No shell found in container (tried: bash, sh)");
  }
}
