/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;
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
    } catch (final Exception e) {
      log.error("Failed to start instance '{}'", annotation.id(), e);
      return new StartupResult.Failure(annotation.id(), e.getMessage(), e);
    }
  }

  /** Creates, configures and starts a Redis container. */
  GenericContainer<?> createAndStartContainer(final RedisStandalone annotation) {
    final GenericContainer<?> container = buildContainer(annotation);
    container.start();
    return container;
  }

  /** Builds a configured Redis container (not started). */
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
      cmd.addAll(Arrays.asList(annotation.args()));
      container.withCommand(cmd.toArray(new String[0]));
    }

    if (annotation.enableNetworkChaos()) {
      container.withCreateContainerCmdModifier(
          cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    }

    return container;
  }

  /** Installs network tools if network chaos is enabled. */
  void installNetworkTools(final GenericContainer<?> container, final RedisStandalone annotation) {
    if (!annotation.enableNetworkChaos()) {
      return;
    }
    try {
      if (!packageInstaller.isInstalled(container, "tc")) {
        packageInstaller.install(container, "iproute2", "iptables");
      }
    } catch (final Exception e) {
      log.warn("Network tools install failed for '{}': {}", annotation.id(), e.getMessage());
    }
  }

  /** Installs annotation-specified packages. */
  void installAnnotationPackages(
      final GenericContainer<?> container, final RedisStandalone annotation) {
    if (annotation.packages().length == 0) {
      return;
    }
    try {
      packageInstaller.install(container, Arrays.asList(annotation.packages()), true);
      log.info("Packages installed in instance '{}'", annotation.id());
    } catch (final Exception e) {
      log.warn("Package install failed for '{}': {}", annotation.id(), e.getMessage());
    }
  }

  /** Builds a success result from a started container. */
  StartupResult buildSuccessResult(
      final GenericContainer<?> container, final RedisStandalone annotation) {
    final RedisConnectionInfo info =
        new RedisConnectionInfo(
            container.getHost(), container.getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT));
    return new StartupResult.Success(annotation.id(), info, new Store(container, info));
  }
}
