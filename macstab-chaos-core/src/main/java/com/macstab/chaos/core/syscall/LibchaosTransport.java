/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.util.Shell;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Generic {@code LD_PRELOAD} transport for any {@link LibchaosLib} shared library.
 *
 * <p>Resolves, copies and controls a pre-compiled {@code .so} inside a container. Each {@link
 * LibchaosLib} has its own instance with isolated paths and labels — multiple chaos modules can
 * share a transport instance for the same library.
 *
 * <p>The {@code .so} resources live in their owning module's JAR (e.g. {@code libchaos-io} ships
 * inside {@code macstab-chaos-disk}). Resolution uses the runtime classpath — the owning module
 * must be on the classpath for {@link #prepare} to find the binary.
 *
 * <h2>Setup flow</h2>
 *
 * <ol>
 *   <li>Construct: {@code new LibchaosTransport(LibchaosLib.IO)}
 *   <li>Pre-start: {@link #prepare(GenericContainer)} — copies {@code .so}, sets {@code LD_PRELOAD}
 *   <li>Post-start: {@link #addRule}, {@link #addRules}, {@link #removeRules}, {@link #clearRules}
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LibchaosLib
 * @see LibchaosVariant
 * @see LibchaosCommandBuilder
 */
@Slf4j
public final class LibchaosTransport {

  @Getter private final LibchaosLib lib;

  private final LibchaosCommandBuilder commands;

  /**
   * Creates a transport using the default POSIX {@code sh} command builder.
   *
   * @param lib library to transport
   * @throws NullPointerException if {@code lib} is null
   */
  public LibchaosTransport(final LibchaosLib lib) {
    this(lib, new ShLibchaosCommandBuilder());
  }

  /**
   * Creates a transport with a custom command builder (for testability or alternate shells).
   *
   * @param lib library to transport
   * @param commands command builder
   * @throws NullPointerException if any argument is null
   */
  public LibchaosTransport(final LibchaosLib lib, final LibchaosCommandBuilder commands) {
    this.lib = Objects.requireNonNull(lib, "lib must not be null");
    this.commands = Objects.requireNonNull(commands, "commands must not be null");
  }

  // ==================== Public API: lifecycle ====================

  /**
   * Copies the matching {@code .so} into the container and appends it to {@code LD_PRELOAD}.
   *
   * <p>Idempotent — safe to call multiple times (label-guarded). Must be called
   * <strong>before</strong> {@code container.start()}.
   *
   * <p>Multiple libchaos transports may prepare the same container: each call appends its library
   * path to any existing {@code LD_PRELOAD} value (colon-separated, the loader's canonical form).
   * Pre-existing entries placed by user code are preserved. Duplicate paths are skipped.
   *
   * @param container container to prepare (must not yet be started)
   * @throws NullPointerException if {@code container} is null
   * @throws ChaosOperationFailedException if the {@code .so} cannot be loaded from the classpath
   */
  public void prepare(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (container.getLabels().containsKey(lib.getLabelKey())) {
      log.debug("libchaos-{} already prepared for this container", lib.getShortName());
      return;
    }

    final LibchaosVariant variant = LibchaosVariant.resolve(container);
    final String resourcePath = lib.getResourcePrefix() + variant.suffix() + ".so";
    final byte[] bytes = loadResource(resourcePath);

    container.withCopyToContainer(Transferable.of(bytes, 0755), lib.getLibraryPath());
    final String existingPreload = container.getEnvMap().getOrDefault("LD_PRELOAD", "");
    container.withEnv("LD_PRELOAD", composeLdPreload(existingPreload, lib.getLibraryPath()));
    container.withLabel(lib.getLabelKey(), variant.suffix());

    log.info(
        "Prepared libchaos-{}: variant={}, size={}B",
        lib.getShortName(),
        variant.suffix(),
        bytes.length);
  }

  /**
   * Builds an {@code LD_PRELOAD} value that appends {@code newPath} to {@code existing}, deduping
   * exact-match entries. Package-private for testability.
   *
   * @param existing current {@code LD_PRELOAD} value (may be {@code null} or empty)
   * @param newPath path to append
   * @return combined value, colon-separated; never contains {@code newPath} twice
   */
  static String composeLdPreload(final String existing, final String newPath) {
    if (existing == null || existing.isEmpty()) {
      return newPath;
    }
    for (final String entry : existing.split(":")) {
      if (entry.equals(newPath)) {
        return existing;
      }
    }
    return existing + ":" + newPath;
  }

  /**
   * Returns {@code true} if {@link #prepare} has been called on this container.
   *
   * @param container target container
   * @return {@code true} when the container carries this transport's label
   * @throws NullPointerException if {@code container} is null
   */
  public boolean isActive(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.getLabels().containsKey(lib.getLabelKey());
  }

  // ==================== Public API: rule management ====================

  /**
   * Appends a single rule for the given owner.
   *
   * @param container running container (must be prepared)
   * @param owner owner tag matching {@code [a-z0-9_]+}
   * @param rule libchaos rule body, e.g. {@code "/data:write:EIO:0.3"}
   * @throws NullPointerException if any argument is null
   * @throws IllegalStateException if {@link #prepare} was not called
   * @throws IllegalArgumentException if {@code owner} contains unsafe characters
   * @throws ChaosOperationFailedException if the shell command fails
   */
  public void addRule(final GenericContainer<?> container, final String owner, final String rule) {
    Objects.requireNonNull(container, "container must not be null");
    validateActive(container);
    final String cmd = commands.buildAppendRule(rule, owner, lib.getConfigPath());
    exec(container, cmd, "addRule");
    log.debug("libchaos-{}: added rule for owner '{}'", lib.getShortName(), owner);
  }

  /**
   * Appends multiple rules for the given owner in a single exec call.
   *
   * @param container running container (must be prepared)
   * @param owner owner tag matching {@code [a-z0-9_]+}
   * @param rules rules to append; empty list is a no-op
   * @throws NullPointerException if any argument is null
   * @throws IllegalStateException if {@link #prepare} was not called
   * @throws IllegalArgumentException if {@code owner} contains unsafe characters
   * @throws ChaosOperationFailedException if the shell command fails
   */
  public void addRules(
      final GenericContainer<?> container, final String owner, final List<String> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    if (rules.isEmpty()) {
      return;
    }
    validateActive(container);
    final String cmd = commands.buildAppendRules(rules, owner, lib.getConfigPath());
    exec(container, cmd, "addRules");
    log.debug(
        "libchaos-{}: added {} rules for owner '{}'", lib.getShortName(), rules.size(), owner);
  }

  /**
   * Removes all rules belonging to the given owner; other owners' rules are untouched.
   *
   * @param container running container (must be prepared)
   * @param owner owner tag whose rules should be removed
   * @throws NullPointerException if any argument is null
   * @throws IllegalStateException if {@link #prepare} was not called
   * @throws IllegalArgumentException if {@code owner} contains unsafe characters
   * @throws ChaosOperationFailedException if the shell command fails
   */
  public void removeRules(final GenericContainer<?> container, final String owner) {
    Objects.requireNonNull(container, "container must not be null");
    validateActive(container);
    final String cmd = commands.buildRemoveRulesByOwner(owner, lib.getConfigPath());
    exec(container, cmd, "removeRules");
    log.debug("libchaos-{}: removed rules for owner '{}'", lib.getShortName(), owner);
  }

  /**
   * Removes all rules from all owners — full reset (deletes the config file).
   *
   * @param container running container (must be prepared)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if {@link #prepare} was not called
   * @throws ChaosOperationFailedException if the shell command fails
   */
  public void clearRules(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    validateActive(container);
    final String cmd = commands.buildClearAll(lib.getConfigPath());
    exec(container, cmd, "clearRules");
    log.debug("libchaos-{}: cleared all rules", lib.getShortName());
  }

  // ==================== Private helpers ====================

  private byte[] loadResource(final String resourcePath) {
    try (final InputStream is =
        LibchaosTransport.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new ChaosOperationFailedException(
            "libchaos-"
                + lib.getShortName()
                + " binary not found on classpath: "
                + resourcePath
                + " — ensure the owning module JAR is on the classpath");
      }
      return is.readAllBytes();
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to load libchaos-" + lib.getShortName() + " binary: " + resourcePath, e);
    }
  }

  private void validateActive(final GenericContainer<?> container) {
    if (!container.getLabels().containsKey(lib.getLabelKey())) {
      throw new IllegalStateException(
          "prepare() must be called before rule operations for libchaos-" + lib.getShortName());
    }
  }

  private void exec(final GenericContainer<?> container, final String command, final String op) {
    try {
      final var result = Shell.exec(container, command);
      if (result.getExitCode() != 0) {
        throw new ChaosOperationFailedException(
            "libchaos-"
                + lib.getShortName()
                + " "
                + op
                + " failed (exit "
                + result.getExitCode()
                + "): "
                + result.getStderr());
      }
    } catch (final ChaosOperationFailedException e) {
      throw e;
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "libchaos-" + lib.getShortName() + " " + op + " execution error", e);
    }
  }
}
