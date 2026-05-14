/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.core.util.ToolPackage;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.command.RedisCommandBuilder;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.extension.RedisContainerExtension.Store;

import lombok.extern.slf4j.Slf4j;

/**
 * Production implementation of {@link StandaloneContainerInstanceFactory}.
 *
 * <p>Owns all Docker I/O for standalone Redis startup: container creation, port binding,
 * command-line args, network chaos capability, package installation, and connection info
 * construction.
 *
 * <p><strong>Design:</strong> The {@link PackageInstallerPort} dependency is injected, allowing all
 * configuration and installation logic to be exercised in unit tests. Container startup ({@link
 * #createAndStartContainer}) requires Docker and is covered by integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class DefaultStandaloneContainerInstanceFactory
    implements StandaloneContainerInstanceFactory {

  /** Production singleton — uses the real {@link PackageInstallerPort#DEFAULT}. */
  public static final DefaultStandaloneContainerInstanceFactory INSTANCE =
      new DefaultStandaloneContainerInstanceFactory(PackageInstallerPort.DEFAULT);

  private final PackageInstallerPort packageInstaller;

  /**
   * Production constructor (via {@link #INSTANCE}).
   *
   * @param packageInstaller package installer port (must not be null)
   */
  DefaultStandaloneContainerInstanceFactory(final PackageInstallerPort packageInstaller) {
    this.packageInstaller = Objects.requireNonNull(packageInstaller, "packageInstaller");
  }

  @Override
  public StartupResult create(final RedisStandalone annotation) {
    try {
      final GenericContainer<?> container = createAndStartContainer(annotation);
      installNetworkTools(container, annotation);
      installAnnotationPackages(container, annotation);
      return buildSuccessResult(container, annotation);
    } catch (final RuntimeException e) {
      log.error("Failed to start standalone Redis instance '{}'", annotation.id(), e);
      return new StartupResult.Failure(annotation.id(), e.getMessage(), e);
    }
  }

  /**
   * Creates, configures and starts a Redis container.
   *
   * <p>Package-private to allow unit testing of the start step independently of Docker socket
   * access (integration tests cover actual container startup).
   *
   * @param annotation annotation driving container configuration — must not be null
   * @return started container (never null)
   */
  GenericContainer<?> createAndStartContainer(final RedisStandalone annotation) {
    final GenericContainer<?> container = buildContainer(annotation);
    container.start();
    return container;
  }

  /**
   * Builds a configured Redis container (not started).
   *
   * <p>Package-private to allow direct unit testing of container configuration (image, ports, args,
   * capabilities) without starting Docker.
   *
   * @param annotation annotation driving container configuration — must not be null
   * @return configured container ready to start (never null)
   */
  @SuppressWarnings("resource")
  GenericContainer<?> buildContainer(final RedisStandalone annotation) {
    final GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse("redis:" + annotation.version()))
            .withExposedPorts(RedisCommandBuilder.DEFAULT_REDIS_PORT);

    if (annotation.port() > 0) {
      container.setPortBindings(
          List.of(annotation.port() + ":" + RedisCommandBuilder.DEFAULT_REDIS_PORT));
    }

    if (annotation.args().length > 0) {
      final List<String> cmd = new ArrayList<>();
      cmd.add("redis-server");
      cmd.addAll(List.of(annotation.args()));
      container.withCommand(cmd.toArray(String[]::new));
    }

    if (annotation.enableNetworkChaos()) {
      container.withCreateContainerCmdModifier(
          cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    }

    if (annotation.enableConnectionChaos()) {
      // Pre-start libchaos-net preparation: copies the .so into the image overlay and sets
      // LD_PRELOAD on the container env. Both operations require the container to not yet be
      // started — the dynamic loader only honours LD_PRELOAD at process launch.
      new LibchaosTransport(LibchaosLib.NET).prepare(container);
    }

    return container;
  }

  /**
   * Installs {@code iproute2} and {@code iptables} if network chaos is enabled and not yet present.
   *
   * @param container running container — must not be null
   * @param annotation source annotation — must not be null
   */
  void installNetworkTools(final GenericContainer<?> container, final RedisStandalone annotation) {
    if (!annotation.enableNetworkChaos()) {
      return;
    }
    try {
      packageInstaller.ensureInstalled(container, Tool.IPROUTE, Tool.IPTABLES);
    } catch (final RuntimeException e) {
      log.warn("Network tools install failed for '{}': {}", annotation.id(), e.getMessage());
    }
  }

  /**
   * Installs annotation-specified packages via {@link PackageInstallerPort}.
   *
   * @param container running container — must not be null
   * @param annotation source annotation — must not be null
   */
  void installAnnotationPackages(
      final GenericContainer<?> container, final RedisStandalone annotation) {
    if (annotation.packages().length == 0) {
      return;
    }
    try {
      final ToolPackage[] toolPackages =
          Arrays.stream(annotation.packages()).map(ToolPackage::ofSame).toArray(ToolPackage[]::new);
      packageInstaller.ensureInstalled(container, toolPackages);
      log.info("Packages installed in instance '{}'", annotation.id());
    } catch (final RuntimeException e) {
      log.warn("Package install failed for '{}': {}", annotation.id(), e.getMessage());
    }
  }

  /**
   * Builds a {@link StartupResult.Success} from a started container.
   *
   * @param container started container — must not be null
   * @param annotation source annotation — must not be null
   * @return success result containing connection info and store
   */
  StartupResult buildSuccessResult(
      final GenericContainer<?> container, final RedisStandalone annotation) {
    final RedisConnectionInfo info =
        new RedisConnectionInfo(
            container.getHost(), container.getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT));
    return new StartupResult.Success(annotation.id(), info, new Store(container, info));
  }
}
