/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.extension.SentinelCluster;
import com.macstab.chaos.redis.factory.SentinelContainerFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * Production implementation of {@link SentinelClusterFactory}.
 *
 * <p>Delegates cluster creation to {@link SentinelContainerFactory}, then installs optional network
 * tools and packages into all cluster containers.
 *
 * <p><strong>Design:</strong> The {@link PackageInstallerPort} dependency is injected, allowing all
 * installation logic to be exercised in unit tests without real Docker containers. Container
 * startup itself ({@link #create}) requires Docker and is covered by integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class DefaultSentinelClusterFactory implements SentinelClusterFactory {

  /** Production singleton — uses the real {@link PackageInstallerPort#DEFAULT}. */
  public static final DefaultSentinelClusterFactory INSTANCE =
      new DefaultSentinelClusterFactory(PackageInstallerPort.DEFAULT);

  private final PackageInstallerPort packageInstaller;

  /**
   * Production constructor (via {@link #INSTANCE}).
   *
   * @param packageInstaller package installer port (must not be null)
   */
  DefaultSentinelClusterFactory(final PackageInstallerPort packageInstaller) {
    this.packageInstaller = Objects.requireNonNull(packageInstaller, "packageInstaller");
  }

  @Override
  public SentinelCluster create(final RedisSentinel annotation) throws Exception {
    final com.macstab.chaos.redis.factory.SentinelCluster factoryCluster =
        SentinelContainerFactory.createSentinelCluster(
            annotation.replicas(), annotation.sentinels(), annotation.enableNetworkChaos());

    final SentinelCluster cluster =
        new SentinelCluster(
            factoryCluster.network(),
            factoryCluster.master(),
            factoryCluster.replicas(),
            factoryCluster.sentinels(),
            annotation.masterName());

    cluster.start();
    installExtras(cluster, annotation);
    return cluster;
  }

  /**
   * Installs optional network tools and packages after cluster startup.
   *
   * @param cluster started cluster
   * @param annotation cluster annotation
   */
  void installExtras(final SentinelCluster cluster, final RedisSentinel annotation) {
    if (annotation.enableNetworkChaos()) {
      installNetworkTools(cluster, annotation.id());
    }
    if (annotation.packages().length > 0) {
      installPackages(cluster, annotation.id(), annotation.packages());
    }
  }

  /**
   * Installs network tools (tc, iptables) in all cluster containers.
   *
   * @param cluster the cluster
   * @param clusterId cluster ID for logging
   */
  void installNetworkTools(final SentinelCluster cluster, final String clusterId) {
    for (final GenericContainer<?> container : allContainers(cluster)) {
      try {
        if (!packageInstaller.isInstalled(container, "tc")) {
          packageInstaller.install(container, "iproute2", "iptables");
        }
      } catch (final Exception e) {
        log.warn("Failed to install network tools in cluster '{}': {}", clusterId, e.getMessage());
      }
    }
  }

  /**
   * Installs packages in all cluster containers.
   *
   * @param cluster the cluster
   * @param clusterId cluster ID for logging
   * @param packages packages to install
   */
  void installPackages(
      final SentinelCluster cluster, final String clusterId, final String[] packages) {
    int success = 0;
    int fail = 0;
    for (final GenericContainer<?> container : allContainers(cluster)) {
      try {
        packageInstaller.install(container, Arrays.asList(packages), true);
        success++;
      } catch (final Exception e) {
        fail++;
        log.warn("Package install failed in cluster '{}': {}", clusterId, e.getMessage());
      }
    }
    log.info(
        "Packages installed {}/{} containers in cluster '{}'", success, success + fail, clusterId);
  }

  /** Returns all containers in the cluster (master + replicas + sentinels). */
  List<GenericContainer<?>> allContainers(final SentinelCluster cluster) {
    final List<GenericContainer<?>> all = new ArrayList<>();
    all.add(cluster.getMasterContainer());
    all.addAll(cluster.getReplicaContainers());
    all.addAll(cluster.getSentinelContainers());
    return all;
  }
}
