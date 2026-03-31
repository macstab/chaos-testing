/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.context;

import java.util.Objects;

import lombok.NonNull;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.command.http.HttpCommandBuilder;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;

/**
 * Immutable value object bundling a target container with its resolved execution context
 * (platform, shell, HTTP command builder), created once per operation entry point and passed
 * down the call chain.
 *
 * <h2>Problem Solved: N × Platform Detection</h2>
 *
 * <p>Platform detection — determining whether the container runs Debian, Alpine, RHEL, Ubuntu —
 * requires executing {@code cat /etc/os-release} inside the container via {@code execInContainer}.
 * Each {@code execInContainer} call is a Docker API round trip, typically costing 5–100 ms on
 * native Linux and 50–300 ms on Docker Desktop (macOS/Windows). Without {@code ContainerContext},
 * each manager class ({@link com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyLifecycleManager},
 * {@link com.macstab.chaos.toxiproxy.network.NetworkRedirectManager},
 * {@link com.macstab.chaos.toxiproxy.lifecycle.ToxiproxyInstaller}) would independently cache
 * {@code (container, platform)} state — duplicating state management, introducing cache
 * invalidation risk, and creating thread-safety complexity.
 *
 * <p>{@code ContainerContext} eliminates this entirely: the orchestrator calls
 * {@link #of(GenericContainer)} once at the start of each operation, and passes the resolved
 * context as a plain argument down the entire call chain. All managers receive pre-detected
 * platform data at zero additional cost.
 *
 * <h2>Immutability Guarantee</h2>
 *
 * <p>All three fields are {@code private final}. The container reference itself is not copied —
 * the underlying container state can still change externally (container stopped, port changed)
 * without {@code ContainerContext} reflecting it. {@code ContainerContext} provides a consistent
 * snapshot of the <em>execution strategy</em> (which shell to use, which HTTP command builder)
 * at the time of creation. It should not be cached across operations; create a new instance per
 * operation to ensure the platform is re-evaluated if the container is restarted.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is unconditionally thread-safe. All fields are final and set in the constructor.
 * The {@code GenericContainer<?>} reference held by this context may be accessed concurrently by
 * multiple threads during {@code execInContainer} calls; Docker's Java client (docker-java)
 * handles concurrent exec calls safely via its connection pool.
 *
 * <h2>Factory Method Design</h2>
 *
 * <p>Two factory methods are provided:
 * <ul>
 *   <li>{@link #of(GenericContainer)} — production path; performs real platform detection
 *       via a Docker API call.
 *   <li>{@link #of(GenericContainer, Platform, Shell)} — test path; bypasses platform detection
 *       by accepting pre-resolved components. Use with mock {@code Platform} and {@code Shell}
 *       in unit tests to avoid Docker dependency and Docker API latency.
 * </ul>
 * The private constructor is not accessible; callers must use one of the factory methods.
 *
 * <h2>Usage Pattern</h2>
 *
 * <p>Correct usage: create once per public operation at the entry point, pass down:
 * <pre>{@code
 * // At orchestrator entry point:
 * ContainerContext ctx = ContainerContext.of(container);
 * lifecycle.ensureRunning(ctx);
 * apiClient.createProxy(ctx, config);
 * networkRedirect.setupRedirect(ctx, 6379, 16379);
 * }</pre>
 *
 * <p>Incorrect usage: creating per-method call defeats the purpose:
 * <pre>{@code
 * // WRONG — re-detects platform on every call
 * lifecycle.ensureRunning(ContainerContext.of(container));
 * apiClient.createProxy(ContainerContext.of(container), config);  // redundant detection
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ContainerContext {

  private final GenericContainer<?> container;
  private final Platform platform;
  private final Shell shell;

  private ContainerContext(
      final GenericContainer<?> container,
      final Platform platform,
      final Shell shell) {
    this.container = container;
    this.platform = platform;
    this.shell = shell;
  }

  /**
   * Creates a context by performing live platform detection inside the container.
   *
   * <p><strong>Cost:</strong> One Docker API call ({@code execInContainer("cat", "/etc/os-release")})
   * to detect the Linux distribution. Expected latency: 5–100 ms on native Linux Docker,
   * 50–300 ms on Docker Desktop. Call this once per operation; pass the resulting context to all
   * collaborators.
   *
   * <p><strong>Platform detection strategy:</strong> {@link PlatformDetector#detect} first reads
   * {@code /etc/os-release} (present on all modern Linux distributions). If that fails, it falls
   * back to package manager detection ({@code which apt-get}, {@code which apk}, {@code which dnf}).
   * Supported distributions: Debian, Ubuntu, Alpine, RHEL/CentOS/Rocky/AlmaLinux.
   *
   * @param container the target container; must be running ({@code container.isRunning() == true})
   * @return fully resolved context with detected platform and default shell
   * @throws NullPointerException if container is null
   * @throws com.macstab.chaos.core.platform.UnsupportedPlatformException if the container's
   *         Linux distribution cannot be detected
   * @throws IllegalStateException if the container is not running
   */
  public static ContainerContext of(@NonNull final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    final Platform platform = PlatformDetector.detect(container);
    final Shell shell = platform.getDefaultShell();
    return new ContainerContext(container, platform, shell);
  }

  /**
   * Creates a context from pre-resolved components, bypassing platform detection.
   *
   * <p><strong>Primary use: unit testing.</strong> Inject a mock {@link Platform} and
   * {@link Shell} to test components that consume {@code ContainerContext} without requiring
   * a running Docker container. This avoids the {@code mockStatic(PlatformDetector.class)}
   * approach, which is fragile and adds test framework overhead.
   *
   * <p>Example:
   * <pre>{@code
   * Platform mockPlatform = mock(Platform.class);
   * Shell mockShell = mock(Shell.class);
   * when(mockPlatform.getDefaultShell()).thenReturn(mockShell);
   * ContainerContext ctx = ContainerContext.of(mockContainer, mockPlatform, mockShell);
   * }</pre>
   *
   * @param container the target container (need not be running for unit test purposes, but
   *                  any {@code execInContainer} calls will fail if it is not)
   * @param platform pre-resolved platform; must not be null
   * @param shell pre-resolved shell; must not be null
   * @return context wrapping the provided components without additional detection
   * @throws NullPointerException if any argument is null
   */
  public static ContainerContext of(
      @NonNull final GenericContainer<?> container,
      @NonNull final Platform platform,
      @NonNull final Shell shell) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(platform, "platform must not be null");
    Objects.requireNonNull(shell, "shell must not be null");
    return new ContainerContext(container, platform, shell);
  }

  /**
   * Returns the target container.
   *
   * @return the Testcontainers container; never null
   */
  public GenericContainer<?> container() {
    return container;
  }

  /**
   * Returns the detected platform, providing access to platform-specific command builders
   * and package name mappings.
   *
   * @return the detected platform; never null
   */
  public Platform platform() {
    return platform;
  }

  /**
   * Returns the default shell for this container's platform (bash, sh, or busybox sh).
   *
   * <p>Used to execute multi-word commands that cannot be passed as a single string to
   * {@code execInContainer}. The shell is responsible for parsing the command string and
   * expanding arguments.
   *
   * @return the platform-appropriate shell; never null
   */
  public Shell shell() {
    return shell;
  }

  /**
   * Returns the platform-appropriate HTTP command builder.
   *
   * <p>Convenience shortcut for {@code platform().getHttpCommandBuilder()}. The builder
   * generates {@code curl} or {@code wget} commands appropriate for the container's package
   * manager ecosystem. Alpine images may not have {@code curl} pre-installed and may fall back
   * to {@code wget}; the builder abstracts this difference.
   *
   * @return the HTTP command builder for this container's platform; never null
   */
  public HttpCommandBuilder http() {
    return platform.getHttpCommandBuilder();
  }
}
