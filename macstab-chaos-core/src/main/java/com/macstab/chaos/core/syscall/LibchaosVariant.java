/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

/**
 * libc + architecture combination for selecting the matching libchaos {@code .so} variant.
 *
 * <p>Resolved <strong>before container start</strong> — exec-based platform detection is
 * unavailable at that point. Uses image name to infer libc (alpine → musl, otherwise → glibc) and
 * the {@code os.arch} system property for architecture.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum LibchaosVariant {

  /** glibc on x86_64. */
  GLIBC_AMD64("glibc", "amd64"),

  /** glibc on aarch64. */
  GLIBC_ARM64("glibc", "arm64"),

  /** musl on x86_64. */
  MUSL_AMD64("musl", "amd64"),

  /** musl on aarch64. */
  MUSL_ARM64("musl", "arm64");

  private final String libc;
  private final String arch;

  LibchaosVariant(final String libc, final String arch) {
    this.libc = libc;
    this.arch = arch;
  }

  /**
   * Resource-suffix form combining libc and architecture.
   *
   * @return e.g. {@code "glibc-amd64"}
   */
  public String suffix() {
    return libc + "-" + arch;
  }

  /**
   * libc identifier.
   *
   * @return {@code "glibc"} or {@code "musl"}
   */
  public String libc() {
    return libc;
  }

  /**
   * CPU architecture identifier.
   *
   * @return {@code "amd64"} or {@code "arm64"}
   */
  public String arch() {
    return arch;
  }

  /**
   * Resolves the variant for the given container, pre-start.
   *
   * <p>Inspects the container's image name to infer libc — runtime exec is unavailable because
   * {@code prepare()} runs before {@code container.start()}.
   *
   * @param container target container (image name only — no exec)
   * @return matching variant
   * @throws NullPointerException if container is null
   */
  public static LibchaosVariant resolve(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    final boolean musl = container.getDockerImageName().toLowerCase().contains("alpine");
    final boolean arm64 = isArm64();
    if (musl) {
      return arm64 ? MUSL_ARM64 : MUSL_AMD64;
    }
    return arm64 ? GLIBC_ARM64 : GLIBC_AMD64;
  }

  private static boolean isArm64() {
    final String osArch = System.getProperty("os.arch", "amd64").toLowerCase();
    return osArch.contains("aarch64") || osArch.contains("arm64");
  }
}
