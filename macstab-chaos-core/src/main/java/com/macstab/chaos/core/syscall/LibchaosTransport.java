/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Generic LD_PRELOAD transport for any libchaos-* shared library.
 *
 * <p>Resolves, copies and controls a pre-compiled {@code .so} inside a container. Each libchaos
 * lib (io, net, memory, time, process, dns) gets its own instance with isolated paths and labels.
 *
 * <p>Call {@link #prepare} before {@code container.start()}, then use {@link #addRule} /
 * {@link #removeRules} / {@link #clearRules} at runtime.
 */
@Slf4j
public final class LibchaosTransport {

  private final String libName;
  private final String libraryPath;
  private final String configPath;
  private final String labelKey;
  private final String resourcePrefix;

  /**
   * @param libName short lib identifier: io, net, memory, time, process, or dns
   */
  public LibchaosTransport(final String libName) {
    Objects.requireNonNull(libName, "libName must not be null");
    this.libName = libName;
    this.libraryPath = "/usr/local/lib/libchaos-" + libName + ".so";
    this.configPath = "/tmp/.chaos-" + libName + ".conf";
    this.labelKey = "macstab.chaos." + libName + ".active";
    this.resourcePrefix = "libchaos-" + libName + "/libchaos-" + libName + "-";
  }

  public String getLibraryPath() {
    return libraryPath;
  }

  public String getConfigPath() {
    return configPath;
  }

  public String getLabelKey() {
    return labelKey;
  }

  /**
   * Copies the matching {@code .so} into the container and sets {@code LD_PRELOAD}.
   * Idempotent — safe to call multiple times.
   *
   * @param container must not yet be started
   */
  public void prepare(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (container.getLabels().containsKey(labelKey)) {
      log.debug("libchaos-{} already prepared for this container", libName);
      return;
    }

    final String variant = resolveVariant(container);
    final String resourcePath = resourcePrefix + variant + ".so";
    final byte[] bytes = loadResource(resourcePath);

    container.withCopyToContainer(Transferable.of(bytes, 0755), libraryPath);
    container.withEnv("LD_PRELOAD", libraryPath);
    container.withLabel(labelKey, variant);

    log.info("Prepared libchaos-{}: variant={}, size={}B", libName, variant, bytes.length);
  }

  /** Appends a single rule for the given owner. */
  public void addRule(
      final GenericContainer<?> container, final String owner, final String rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    validateActive(container);

    final String fullRule = rule + " # " + owner;
    execSilent(container, String.format("echo '%s' >> %s", fullRule, configPath));
    log.debug("libchaos-{}: added rule: {}", libName, fullRule);
  }

  /** Appends multiple rules for the given owner in a single exec. */
  public void addRules(
      final GenericContainer<?> container, final String owner, final List<String> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    if (rules.isEmpty()) return;
    validateActive(container);

    final var sb = new StringBuilder();
    for (final String rule : rules) {
      sb.append(rule).append(" # ").append(owner).append('\n');
    }
    execSilent(container, String.format("printf '%%s' '%s' >> %s",
        sb.toString().replace("'", "'\\''"), configPath));
    log.debug("libchaos-{}: added {} rules for owner '{}'", libName, rules.size(), owner);
  }

  /** Removes all rules belonging to the given owner; other owners are untouched. */
  public void removeRules(final GenericContainer<?> container, final String owner) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    validateActive(container);

    execSilent(container, String.format("sed -i '/# %s$/d' %s 2>/dev/null || true",
        owner, configPath));
    log.debug("libchaos-{}: removed rules for owner '{}'", libName, owner);
  }

  /** Removes all rules from all owners. Full reset. */
  public void clearRules(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateActive(container);

    execSilent(container, String.format("rm -f %s", configPath));
    log.debug("libchaos-{}: cleared all rules", libName);
  }

  public boolean isActive(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.getLabels().containsKey(labelKey);
  }

  // ── private ──────────────────────────────────────────────────────────────

  private String resolveVariant(final GenericContainer<?> container) {
    final String imageName = container.getDockerImageName().toLowerCase();
    final String libc = imageName.contains("alpine") ? "musl" : "glibc";
    return libc + "-" + detectArchitecture();
  }

  private static String detectArchitecture() {
    final String osArch = System.getProperty("os.arch", "amd64").toLowerCase();
    return (osArch.contains("aarch64") || osArch.contains("arm64")) ? "arm64" : "amd64";
  }

  private byte[] loadResource(final String resourcePath) {
    try (final InputStream is =
        LibchaosTransport.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IllegalStateException(
            "libchaos-" + libName + " binary not found on classpath: " + resourcePath);
      }
      return is.readAllBytes();
    } catch (final IllegalStateException e) {
      throw e;
    } catch (final Exception e) {
      throw new IllegalStateException(
          "Failed to load libchaos-" + libName + " binary: " + resourcePath, e);
    }
  }

  private void validateActive(final GenericContainer<?> container) {
    if (!container.getLabels().containsKey(labelKey)) {
      throw new IllegalStateException(
          "prepare() must be called before adding rules for libchaos-" + libName + ".");
    }
  }

  private static void execSilent(final GenericContainer<?> container, final String command) {
    try {
      final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, command);
      if (result.getExitCode() != 0) {
        log.warn("exec failed (exit {}): {}", result.getExitCode(), command);
      }
    } catch (final Exception e) {
      log.warn("exec error: {}", e.getMessage());
    }
  }
}
