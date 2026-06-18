/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

/**
 * libchaos-* shared libraries injected via {@code LD_PRELOAD}.
 *
 * <p>Each library lives in its own owning module's resources:
 *
 * <ul>
 *   <li>{@link #IO} — {@code macstab-chaos-disk}
 *   <li>{@link #NET} — {@code macstab-chaos-network}
 *   <li>{@link #MEMORY} — {@code macstab-chaos-memory}
 *   <li>{@link #TIME} — {@code macstab-chaos-time}
 *   <li>{@link #PROCESS} — {@code macstab-chaos-process}
 *   <li>{@link #DNS} — {@code macstab-chaos-dns}
 * </ul>
 *
 * <p>Resources are discovered via the runtime classpath — the corresponding module JAR must be on
 * the classpath for the matching {@code .so} to load.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum LibchaosLib {

  /** {@code libchaos-io} — disk syscall fault injection. */
  IO("io"),

  /** {@code libchaos-net} — network syscall fault injection. */
  NET("net"),

  /** {@code libchaos-memory} — memory allocator fault injection. */
  MEMORY("memory"),

  /** {@code libchaos-time} — clock and timer manipulation. */
  TIME("time"),

  /** {@code libchaos-process} — process and signal interception. */
  PROCESS("process"),

  /** {@code libchaos-dns} — DNS resolver fault injection. */
  DNS("dns");

  private static final String LIB_DIR = "/usr/local/lib/";
  private static final String CONF_DIR = "/tmp/.chaos-";
  private static final String LABEL_PREFIX = "macstab.chaos.";
  private static final String LABEL_SUFFIX = ".active";

  private final String shortName;

  LibchaosLib(final String shortName) {
    this.shortName = shortName;
  }

  /**
   * Short identifier used in paths, labels and resource names.
   *
   * @return e.g. {@code "io"}, {@code "net"}
   */
  public String getShortName() {
    return shortName;
  }

  /**
   * Absolute path inside the container where the {@code .so} is deployed.
   *
   * @return e.g. {@code /usr/local/lib/libchaos-io.so}
   */
  public String getLibraryPath() {
    return LIB_DIR + "libchaos-" + shortName + ".so";
  }

  /**
   * Absolute path inside the container for the runtime config file.
   *
   * @return e.g. {@code /tmp/.chaos-io.conf}
   */
  public String getConfigPath() {
    return CONF_DIR + shortName + ".conf";
  }

  /**
   * Container label key used to mark the transport as prepared.
   *
   * @return e.g. {@code macstab.chaos.io.active}
   */
  public String getLabelKey() {
    return LABEL_PREFIX + shortName + LABEL_SUFFIX;
  }

  /**
   * Classpath resource prefix for variant binaries.
   *
   * @return e.g. {@code libchaos-io/libchaos-io-}
   */
  public String getResourcePrefix() {
    return "libchaos-" + shortName + "/libchaos-" + shortName + "-";
  }
}
