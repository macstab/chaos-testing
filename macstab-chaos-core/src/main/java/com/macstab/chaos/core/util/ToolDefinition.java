/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

/**
 * Open contract for a self-describing tool installation unit.
 *
 * <p>Defines the minimal information needed to install a tool into a container and verify its
 * presence: the binary name (used for {@code which}-based verification and label tracking) and the
 * package name passed to the platform package manager.
 *
 * <p><strong>Extension model:</strong> Any class or enum in any module may implement this interface
 * to define a typed, magic-string-free tool catalogue without modifying core:
 *
 * <pre>{@code
 * public enum MyProjectTools implements ToolDefinition {
 *
 *     REDIS_CLI("redis-cli", "redis-tools"),
 *     CUSTOM_AGENT("my-agent", "my-agent-pkg");
 *
 *     private final String tool;
 *     private final String packageName;
 *
 *     MyProjectTools(final String tool, final String packageName) {
 *         this.tool = tool;
 *         this.packageName = packageName;
 *     }
 *
 *     @Override public String tool()        { return tool; }
 *     @Override public String packageName() { return packageName; }
 * }
 *
 * // Usage:
 * PackageInstaller.ensureInstalled(container,
 *     MyProjectTools.REDIS_CLI,
 *     MyProjectTools.CUSTOM_AGENT);
 * }</pre>
 *
 * <p><strong>Built-in implementations:</strong>
 *
 * <ul>
 *   <li>{@link ToolPackage} — ad-hoc factory record for inline definitions
 * </ul>
 *
 * <p><strong>Distinction from {@link com.macstab.chaos.core.platform.Tool}:</strong> {@code Tool}
 * is a built-in enum whose package names are resolved per Linux distribution by the {@link
 * com.macstab.chaos.core.platform.Platform} layer. {@code ToolDefinition} is self-describing —
 * implementations carry their own package name and require no platform resolution. Use {@code Tool}
 * for built-in cross-distro tools; use {@code ToolDefinition} for project-specific or
 * module-specific tools.
 *
 * <p><strong>Label key:</strong> {@code macstab.chaos.pkg.<tool()>} — tracked on the {@link
 * org.testcontainers.containers.GenericContainer} Java object to ensure each tool is installed at
 * most once per container lifetime.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ToolPackage
 * @see PackageInstaller#ensureInstalled(org.testcontainers.containers.GenericContainer,
 *     ToolDefinition...)
 */
public interface ToolDefinition {

  /**
   * Binary name of the tool — used as the {@code which} verification target and as the label key
   * suffix ({@code macstab.chaos.pkg.<tool>}).
   *
   * <p>Must be non-null and non-blank. Must match the binary name exactly as it appears on the
   * container's {@code PATH} after installation.
   *
   * <p><strong>Examples:</strong> {@code "redis-cli"}, {@code "taskset"}, {@code "my-agent"}
   *
   * @return binary name; never null, never blank
   */
  String tool();

  /**
   * Package name passed to the platform package manager ({@code apk add}, {@code apt-get install},
   * etc.).
   *
   * <p>Must be non-null and non-blank. Note that the binary name and package name frequently differ
   * (e.g. binary {@code "taskset"} is provided by package {@code "util-linux"}).
   *
   * <p><strong>Examples:</strong> {@code "redis-tools"}, {@code "util-linux"}, {@code "stress-ng"}
   *
   * @return package name; never null, never blank
   */
  String packageName();
}
