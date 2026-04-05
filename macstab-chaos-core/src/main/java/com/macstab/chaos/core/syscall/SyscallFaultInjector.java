/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import com.macstab.chaos.core.model.ContainerArchitecture;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.util.Shell;

import lombok.extern.slf4j.Slf4j;

/**
 * Deploys and controls the {@code libchaos-io} syscall fault injection library inside containers.
 *
 * <h2>What This Does</h2>
 *
 * <p>Copies a pre-compiled {@code LD_PRELOAD} shared library into the container and manages
 * a runtime configuration file that controls which syscalls are intercepted and how they fail.
 * Multiple chaos modules (disk, network, DNS, memory, process, filesystem) share the same
 * library instance and config file — each module owns its rules via an {@code owner} prefix.
 *
 * <h2>Setup Flow</h2>
 *
 * <p>Call {@link #prepare(GenericContainer)} <strong>before</strong> {@code container.start()}.
 * This method:
 * <ol>
 *   <li>Detects the container's platform (glibc/musl) and architecture (amd64/arm64)
 *   <li>Copies the matching {@code .so} from the classpath into the container
 *   <li>Sets {@code LD_PRELOAD} environment variable
 * </ol>
 *
 * <h2>Runtime Control</h2>
 *
 * <p>After the container is started, use {@link #addRule}, {@link #removeRules},
 * and {@link #clearRules} to control fault injection at runtime. Changes take effect
 * on the next intercepted syscall (the library re-reads the config file on mtime change).
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * GenericContainer<?> container = new GenericContainer<>("redis:7.4");
 * SyscallFaultInjector.prepare(container);
 * container.start();
 *
 * // 30% of writes to /data fail with EIO
 * SyscallFaultInjector.addRule(container, "disk", "/data:write:ERRNO:EIO:0.3");
 *
 * // Reset disk rules only
 * SyscallFaultInjector.removeRules(container, "disk");
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see SyscallRule
 */
@Slf4j
public final class SyscallFaultInjector {

  /** Path inside the container where the .so is deployed. */
  public static final String LIBRARY_PATH = "/usr/local/lib/libchaos-io.so";

  /** Path inside the container for the runtime config file. */
  static final String CONFIG_PATH = "/tmp/.chaos-io.conf";

  /** Label key tracking whether the .so has been deployed to this container. */
  static final String LABEL_ACTIVE = "macstab.chaos.io.active";

  /** Classpath prefix for pre-compiled .so binaries. */
  private static final String RESOURCE_PREFIX = "libchaos-io/libchaos-io-";

  /** Utility class — not instantiable. */
  private SyscallFaultInjector() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Prepares a container for syscall fault injection.
   *
   * <p>Must be called <strong>before</strong> {@code container.start()}. Detects the
   * target platform and architecture, copies the matching {@code .so} binary from the
   * classpath into the container, and sets the {@code LD_PRELOAD} environment variable.
   *
   * <p>Idempotent — safe to call multiple times on the same container (label-guarded).
   *
   * @param container container to prepare (must not yet be started)
   * @throws IllegalStateException if the matching .so binary cannot be found on the classpath
   */
  public static void prepare(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");

    if (container.getLabels().containsKey(LABEL_ACTIVE)) {
      log.debug("SyscallFaultInjector already prepared for this container");
      return;
    }

    final String variant = resolveVariant(container);
    final String resourcePath = RESOURCE_PREFIX + variant + ".so";
    final byte[] libraryBytes = loadResource(resourcePath);

    container.withCopyToContainer(Transferable.of(libraryBytes, 0755), LIBRARY_PATH);
    container.withEnv("LD_PRELOAD", LIBRARY_PATH);
    container.withLabel(LABEL_ACTIVE, variant);

    log.info("Prepared syscall fault injection: variant={}, size={}B", variant, libraryBytes.length);
  }

  /**
   * Adds a fault injection rule for the given owner module.
   *
   * <p>The rule is appended to the config file. The owner prefix allows selective
   * removal via {@link #removeRules}. Format:
   * {@code owner:path:operation:action:parameter}
   *
   * <p>The library re-reads the config on the next intercepted syscall after the
   * file's mtime changes.
   *
   * @param container running container with .so deployed
   * @param owner     module identifier (e.g. "disk", "net", "dns")
   * @param rule      config line without owner prefix (e.g. "/data:write:ERRNO:EIO:0.3")
   */
  public static void addRule(
      final GenericContainer<?> container, final String owner, final String rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    validateActive(container);

    final String fullRule = rule + " # " + owner;
    execSilent(container, String.format("echo '%s' >> %s", fullRule, CONFIG_PATH));
    log.debug("Added syscall rule: {}", fullRule);
  }

  /**
   * Adds multiple rules for the given owner module in a single exec call.
   *
   * @param container running container with .so deployed
   * @param owner     module identifier
   * @param rules     config lines without owner prefix
   */
  public static void addRules(
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
        sb.toString().replace("'", "'\\''"), CONFIG_PATH));
    log.debug("Added {} syscall rules for owner '{}'", rules.size(), owner);
  }

  /**
   * Removes all rules belonging to the given owner module.
   *
   * <p>Other modules' rules remain untouched. Uses {@code sed} to filter lines
   * by owner prefix.
   *
   * @param container running container with .so deployed
   * @param owner     module identifier whose rules should be removed
   */
  public static void removeRules(final GenericContainer<?> container, final String owner) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    validateActive(container);

    execSilent(container, String.format("sed -i '/# %s$/d' %s 2>/dev/null || true",
        owner, CONFIG_PATH));
    log.debug("Removed syscall rules for owner '{}'", owner);
  }

  /**
   * Clears all rules from all modules. Full reset.
   *
   * @param container running container with .so deployed
   */
  public static void clearRules(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateActive(container);

    execSilent(container, String.format("rm -f %s", CONFIG_PATH));
    log.debug("Cleared all syscall fault injection rules");
  }

  /**
   * Checks whether syscall fault injection is active on this container.
   *
   * @param container target container
   * @return true if {@link #prepare} was called on this container
   */
  public static boolean isActive(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.getLabels().containsKey(LABEL_ACTIVE);
  }

  // ==================== Private ====================

  /**
   * Resolves the correct .so variant name based on platform and architecture.
   *
   * @param container target container (inspected for image metadata)
   * @return variant suffix, e.g. "glibc-amd64" or "musl-arm64"
   */
  private static String resolveVariant(final GenericContainer<?> container) {
    // Platform detection requires a running container for exec — but we're called
    // pre-start. Use image name heuristics: Alpine images contain "alpine" in the tag.
    final String imageName = container.getDockerImageName().toLowerCase();
    final String libc = imageName.contains("alpine") ? "musl" : "glibc";

    // Architecture: check Docker host architecture (same as container on native,
    // or emulated arch via platform flag). Default to amd64.
    final String arch = detectArchitecture();

    return libc + "-" + arch;
  }

  /**
   * Detects the host architecture for .so variant selection.
   *
   * <p>Uses {@code os.arch} system property — reliable for native Docker and
   * for platform-specified containers (Docker translates the arch).
   *
   * @return "amd64" or "arm64"
   */
  private static String detectArchitecture() {
    final String osArch = System.getProperty("os.arch", "amd64").toLowerCase();
    if (osArch.contains("aarch64") || osArch.contains("arm64")) {
      return "arm64";
    }
    return "amd64";
  }

  /**
   * Loads a classpath resource as a byte array.
   *
   * @param resourcePath path relative to classpath root
   * @return byte content
   * @throws IllegalStateException if resource not found
   */
  private static byte[] loadResource(final String resourcePath) {
    try (final InputStream is = SyscallFaultInjector.class.getClassLoader()
        .getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IllegalStateException(
            "libchaos-io binary not found on classpath: " + resourcePath
                + " — ensure macstab-chaos-core jar includes the .so resources");
      }
      return is.readAllBytes();
    } catch (final IllegalStateException e) {
      throw e;
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to load libchaos-io binary: " + resourcePath, e);
    }
  }

  /**
   * Validates that the .so is deployed on this container.
   *
   * @param container target container
   * @throws IllegalStateException if prepare() was not called
   */
  private static void validateActive(final GenericContainer<?> container) {
    if (!container.getLabels().containsKey(LABEL_ACTIVE)) {
      throw new IllegalStateException(
          "SyscallFaultInjector.prepare() must be called before adding rules. "
              + "Call prepare() before container.start().");
    }
  }

  /**
   * Executes a shell command silently — logs warnings but does not throw.
   *
   * @param container running container
   * @param command   shell command
   */
  private static void execSilent(final GenericContainer<?> container, final String command) {
    try {
      final var result = container.execInContainer(Shell.SH, Shell.FLAG_C, command);
      if (result.getExitCode() != 0) {
        log.warn("SyscallFaultInjector exec failed (exit {}): {}", result.getExitCode(), command);
      }
    } catch (final Exception e) {
      log.warn("SyscallFaultInjector exec error: {}", e.getMessage());
    }
  }
}
