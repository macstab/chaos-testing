/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import java.util.Objects;

/**
 * Immutable binding of a tool binary name to its installation package.
 *
 * <p>Used by {@link PackageInstaller#ensureInstalled} to express the relationship between the
 * binary that must exist on the container's {@code PATH} ({@code tool}) and the package that
 * provides it ({@code packageName}).
 *
 * <p>These two names are frequently different:
 *
 * <ul>
 *   <li>{@code tool="taskset"}, {@code packageName="util-linux"}
 *   <li>{@code tool="renice"}, {@code packageName="util-linux"}
 *   <li>{@code tool="tc"}, {@code packageName="iproute2"}
 * </ul>
 *
 * <p>When they are the same, use the convenience factory {@link #ofSame}:
 *
 * <pre>{@code
 * ToolPackage.ofSame("stress-ng")   // tool="stress-ng", packageName="stress-ng"
 * ToolPackage.of("taskset", "util-linux")
 * }</pre>
 *
 * @param tool binary name checked via {@code which} (must not be blank)
 * @param packageName package name passed to the platform package manager (must not be blank)
 * @author Christian Schnapka - Macstab GmbH
 */
public record ToolPackage(String tool, String packageName) implements ToolDefinition {

  /**
   * Compact canonical constructor — validates both fields are non-null and non-blank.
   *
   * @throws NullPointerException if {@code tool} or {@code packageName} is null
   * @throws IllegalArgumentException if {@code tool} or {@code packageName} is blank
   */
  public ToolPackage {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(packageName, "packageName must not be null");
    if (tool.isBlank()) {
      throw new IllegalArgumentException("tool must not be blank");
    }
    if (packageName.isBlank()) {
      throw new IllegalArgumentException("packageName must not be blank");
    }
  }

  /**
   * Creates a {@code ToolPackage} where the binary name and package name differ.
   *
   * @param tool binary name (e.g. {@code "taskset"})
   * @param packageName package providing the binary (e.g. {@code "util-linux"})
   * @return new {@code ToolPackage} instance
   */
  public static ToolPackage of(final String tool, final String packageName) {
    return new ToolPackage(tool, packageName);
  }

  /**
   * Creates a {@code ToolPackage} where the binary name equals the package name.
   *
   * <p>Convenience factory for the common case where installing the package provides a binary with
   * the same name (e.g. {@code stress-ng}, {@code cpulimit}).
   *
   * @param toolAndPackage name used for both tool binary and package
   * @return new {@code ToolPackage} instance
   */
  public static ToolPackage ofSame(final String toolAndPackage) {
    return new ToolPackage(toolAndPackage, toolAndPackage);
  }
}
