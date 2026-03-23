/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Objects;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Shell {

  /** Shell binary path (POSIX-compliant). */
  public static final String SH = "/bin/sh";

  /** Shell command flag. */
  public static final String FLAG_C = "-c";

  /** Private constructor - utility class. */
  private Shell() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Executes shell command in container.
   *
   * @param container target container
   * @param command shell command to execute
   * @return execution result
   * @throws Exception if execution fails
   * @throws NullPointerException if container or command is null
   */
  public static Container.ExecResult exec(final GenericContainer<?> container, final String command)
      throws Exception {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(command, "command must not be null");
    return container.execInContainer(SH, FLAG_C, command);
  }
}
