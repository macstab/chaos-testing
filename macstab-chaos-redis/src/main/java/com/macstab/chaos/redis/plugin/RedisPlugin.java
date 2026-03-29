/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.extension.ChaosPlugin;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.StandaloneRedis;

import lombok.extern.slf4j.Slf4j;

/**
 * Plugin for standalone Redis containers.
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li>Container creation (image, ports, command args)
 *   <li>Connection info extraction (host, port, container)
 *   <li>Network chaos capability (NET_ADMIN)
 * </ul>
 *
 * <p><strong>NOT Responsible For:</strong>
 *
 * <ul>
 *   <li>Lifecycle management (ChaosTestingExtension handles)
 *   <li>Resource constraints (ChaosTestingExtension handles)
 *   <li>Package installation (ChaosTestingExtension handles)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class RedisPlugin implements ChaosPlugin<RedisStandalone> {

  @Override
  public Class<RedisStandalone> annotationType() {
    return RedisStandalone.class;
  }

  @Override
  public GenericContainer<?> createContainer(final RedisStandalone annotation) {
    log.debug(
        "Creating Redis container: version={}, port={}", annotation.version(), annotation.port());

    final GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse("redis:" + annotation.version()));

    container.withExposedPorts(6379);

    if (annotation.port() > 0) {
      container.setPortBindings(List.of(annotation.port() + ":6379"));
    }

    if (annotation.args().length > 0) {
      final List<String> command = new ArrayList<>();
      command.add("redis-server");
      command.addAll(List.of(annotation.args()));
      container.withCommand(command.toArray(new String[0]));
    }

    if (annotation.enableNetworkChaos()) {
      container.withCreateContainerCmdModifier(
          cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
      log.debug("Enabled network chaos (NET_ADMIN) for Redis container");
    }

    return container;
  }

  @Override
  public Object createConnectionInfo(
      final GenericContainer<?> container, final RedisStandalone annotation) {

    return new StandaloneRedis(container.getHost(), container.getMappedPort(6379));
  }

  @Override
  public Set<Class<?>> supportedParameterTypes() {
    // RedisContainerExtension owns parameter injection for StandaloneRedis.
    // ChaosTestingExtension handles container creation via createContainer/createConnectionInfo;
    // parameter resolution is delegated to the dedicated extension to avoid competing resolvers.
    return Set.of();
  }
}
