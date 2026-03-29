/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.command.http.HttpCommandBuilder;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;

/**
 * Resolved execution context for a target container.
 *
 * <p>Bundles the container, its detected {@link Platform}, and the default {@link Shell} into a
 * single immutable value. Created once at the entry point of each operation (typically in {@link
 * ToxiproxyOrchestrator}) and passed down the call chain.
 *
 * <h2>Motivation</h2>
 *
 * <p>Platform detection ({@link PlatformDetector#detect}) requires an {@code execInContainer} call
 * to read {@code /etc/os-release}. Without this DTO, each manager class independently cached {@code
 * cachedPlatform} / {@code cachedContainer} fields — duplicating state, introducing thread-safety
 * risk, and violating DRY.
 *
 * <p>With {@code ContainerContext}, platform detection happens exactly once per operation and the
 * result flows down as a plain argument. No mutable state, no caching needed anywhere.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is immutable. All fields are final. Safe for concurrent use.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * ContainerContext ctx = ContainerContext.of(container);
 * ctx.shell().exec(ctx.container(), someCommand);
 * ctx.http().buildGetRequest("http://localhost:8474/proxies");
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ContainerContext {

  private final GenericContainer<?> container;
  private final Platform platform;
  private final Shell shell;

  private ContainerContext(
      final GenericContainer<?> container, final Platform platform, final Shell shell) {
    this.container = container;
    this.platform = platform;
    this.shell = shell;
  }

  /**
   * Create a context by detecting platform and resolving default shell for the container.
   *
   * <p>Performs platform detection via {@link PlatformDetector#detect}. This executes one {@code
   * cat /etc/os-release} inside the container — call once per operation, not per method.
   *
   * @param container the target container (must be running)
   * @return fully resolved context
   * @throws NullPointerException if container is null
   */
  public static ContainerContext of(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    final Platform platform = PlatformDetector.detect(container);
    final Shell shell = platform.getDefaultShell();
    return new ContainerContext(container, platform, shell);
  }

  /**
   * Create a context from pre-resolved components.
   *
   * <p>Bypasses platform detection. Intended for use in tests where platform and shell are already
   * known (e.g., from mocks), avoiding the need for {@code mockStatic(PlatformDetector)}.
   *
   * @param container the target container
   * @param platform pre-detected platform
   * @param shell pre-resolved shell
   * @return fully resolved context
   * @throws NullPointerException if any argument is null
   */
  public static ContainerContext of(
      final GenericContainer<?> container, final Platform platform, final Shell shell) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(platform, "platform must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    return new ContainerContext(container, platform, shell);
  }

  /**
   * Get the target container.
   *
   * @return target container
   */
  public GenericContainer<?> container() {
    return container;
  }

  /**
   * Get the detected platform.
   *
   * @return platform
   */
  public Platform platform() {
    return platform;
  }

  /**
   * Get the default shell for command execution.
   *
   * @return shell
   */
  public Shell shell() {
    return shell;
  }

  /**
   * Get the platform-appropriate HTTP command builder.
   *
   * <p>Shortcut for {@code platform().getHttpCommandBuilder()}.
   *
   * @return HTTP command builder
   */
  public HttpCommandBuilder http() {
    return platform.getHttpCommandBuilder();
  }
}
