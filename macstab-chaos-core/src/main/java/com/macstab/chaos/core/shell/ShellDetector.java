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
   * <p>Priority order: bash → ash (BusyBox /bin/sh on Alpine) → generic /bin/sh
   *
   * @param container container
   * @return shell instance
   * @throws IllegalStateException if no shell found
   */
  @SuppressWarnings("deprecation")
  public static Shell detect(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    // Try bash first (most capable)
    final Shell bash = new BashShell();
    if (bash.isAvailable(container)) {
      log.debug("Detected shell: {}", bash.getType());
      return bash;
    }

    // Check if /bin/sh is BusyBox ash (Alpine)
    if (isBusyBoxSh(container)) {
      final Shell ash = new AshShell();
      log.debug("Detected shell: {} (BusyBox ash)", ash.getType());
      return ash;
    }

    // Generic /bin/sh fallback (dash on Debian minimal, etc.)
    final Shell sh = new BusyboxShell();
    if (sh.isAvailable(container)) {
      log.debug("Detected shell: {} (generic /bin/sh)", sh.getType());
      return sh;
    }

    throw new IllegalStateException("No shell found in container (tried: bash, ash, sh)");
  }

  /**
   * Checks if {@code /bin/sh} is provided by BusyBox (Alpine Linux).
   *
   * <p>BusyBox {@code --help} outputs a line containing {@code "BusyBox"}. This distinguishes
   * Alpine's ash from Debian's dash or other /bin/sh implementations.
   *
   * @param container container to check
   * @return {@code true} if /bin/sh is BusyBox
   */
  private static boolean isBusyBoxSh(final GenericContainer<?> container) {
    try {
      final var result = container.execInContainer("/bin/sh", "--help");
      // BusyBox outputs help to stderr
      final String output = result.getStdout() + result.getStderr();
      return output.contains("BusyBox");
    } catch (final Exception e) {
      log.debug("BusyBox detection failed: {}", e.getMessage());
      return false;
    }
  }
}
