/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.ToolPackage;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.extension.SentinelCluster;

/**
 * Unit tests for {@link DefaultSentinelClusterFactory}.
 *
 * <p>{@link PackageInstallerPort} is injected as a mock, eliminating all Docker calls. The {@code
 * create()} method itself requires Docker and is covered by integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultSentinelClusterFactory")
class DefaultSentinelClusterFactoryTest {

  @Mock private PackageInstallerPort packageInstaller;

  private DefaultSentinelClusterFactory factory;

  @BeforeEach
  void setUp() {
    factory = new DefaultSentinelClusterFactory(packageInstaller);
  }

  // ─── allContainers() ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("allContainers()")
  class AllContainersTests {

    @Test
    @DisplayName("Returns master + all replicas + all sentinels in order")
    void shouldReturnAllContainersInOrder() {
      // ARRANGE
      final SentinelCluster cluster = buildCluster(1, 2);

      // ACT
      final List<GenericContainer<?>> result = factory.allContainers(cluster);

      // ASSERT
      assertThat(result).hasSize(4); // 1 master + 1 replica + 2 sentinels
      assertThat(result.get(0)).isSameAs(cluster.getMasterContainer());
      assertThat(result.get(1)).isSameAs(cluster.getReplicaContainers().get(0));
      assertThat(result.get(2)).isSameAs(cluster.getSentinelContainers().get(0));
      assertThat(result.get(3)).isSameAs(cluster.getSentinelContainers().get(1));
    }

    @Test
    @DisplayName("Works with zero replicas and one sentinel")
    void shouldHandleMinimalCluster() {
      // ARRANGE
      final SentinelCluster cluster = buildCluster(0, 1);

      // ACT
      final List<GenericContainer<?>> result = factory.allContainers(cluster);

      // ASSERT
      assertThat(result).hasSize(2); // master + 1 sentinel
    }
  }

  // ─── installExtras() ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("installExtras()")
  class InstallExtrasTests {

    @Test
    @DisplayName("Does nothing when networkChaos=false and no packages")
    void shouldDoNothingWhenNoExtras() {
      // ARRANGE
      final SentinelCluster cluster = buildCluster(1, 1);
      final RedisSentinel annotation = buildAnnotation("id", false, new String[0]);

      // ACT
      factory.installExtras(cluster, annotation);

      // ASSERT — ensureInstalled never called when chaos disabled and no packages
      verify(packageInstaller, never())
          .ensureInstalled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Tool[].class));
      verify(packageInstaller, never())
          .ensureInstalled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(ToolPackage[].class));
    }

    @Test
    @DisplayName("Calls ensureInstalled with IPROUTE/IPTABLES when enableNetworkChaos=true")
    void shouldEnsureInstalledNetworkToolsWhenChaosEnabled() {
      // ARRANGE — master only (1 container)
      final SentinelCluster cluster = buildCluster(0, 0);
      final RedisSentinel annotation = buildAnnotation("id", true, new String[0]);

      // ACT
      factory.installExtras(cluster, annotation);

      // ASSERT
      verify(packageInstaller, org.mockito.Mockito.times(1))
          .ensureInstalled(org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(Tool[].class));
    }

    @Test
    @DisplayName("Calls ensureInstalled with ToolPackages when packages are specified")
    void shouldEnsureInstalledPackagesWhenSpecified() {
      // ARRANGE — master only (1 container)
      final SentinelCluster cluster = buildCluster(0, 0);
      final RedisSentinel annotation = buildAnnotation("id", false, new String[] {"curl", "jq"});

      // ACT
      factory.installExtras(cluster, annotation);

      // ASSERT
      verify(packageInstaller, org.mockito.Mockito.times(1))
          .ensureInstalled(org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(ToolPackage[].class));
    }
  }

  // ─── installNetworkTools() ────────────────────────────────────────────────

  @Nested
  @DisplayName("installNetworkTools()")
  class InstallNetworkToolsTests {

    @Test
    @DisplayName("Calls ensureInstalled with IPROUTE and IPTABLES per container")
    void shouldEnsureInstalledPerContainer() {
      // ARRANGE — master + 1 replica + 1 sentinel = 3 containers
      final SentinelCluster cluster = buildCluster(1, 1);

      // ACT
      factory.installNetworkTools(cluster, "test-cluster");

      // ASSERT — ensureInstalled called once per container
      verify(packageInstaller, org.mockito.Mockito.times(3))
          .ensureInstalled(org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(Tool[].class));
    }

    @Test
    @DisplayName("Swallows exception and continues to next container")
    void shouldSwallowExceptionAndContinue() {
      // ARRANGE
      final GenericContainer<?> c1 = mock(GenericContainer.class);
      final GenericContainer<?> c2 = mock(GenericContainer.class);
      final SentinelCluster cluster = buildClusterFromContainers(c1, List.of(), List.of(c2));

      org.mockito.Mockito.doThrow(new RuntimeException("exec failed"))
          .when(packageInstaller)
          .ensureInstalled(org.mockito.ArgumentMatchers.eq(c1),
              org.mockito.ArgumentMatchers.any(Tool[].class));

      // ACT — must not throw
      factory.installNetworkTools(cluster, "test-cluster");

      // ASSERT — c2 was still attempted despite c1 throwing
      verify(packageInstaller)
          .ensureInstalled(org.mockito.ArgumentMatchers.eq(c2),
              org.mockito.ArgumentMatchers.any(Tool[].class));
    }
  }

  // ─── installPackages() ────────────────────────────────────────────────────

  @Nested
  @DisplayName("installPackages()")
  class InstallPackagesTests {

    @Test
    @DisplayName("Ensures packages in every container via ensureInstalled")
    void shouldEnsureInstalledInEveryContainer() {
      // ARRANGE
      final SentinelCluster cluster = buildCluster(1, 1);
      final String[] packages = {"curl", "jq"};

      // ACT
      factory.installPackages(cluster, "test-cluster", packages);

      // ASSERT — called for master + 1 replica + 1 sentinel = 3 containers
      verify(packageInstaller, org.mockito.Mockito.times(3))
          .ensureInstalled(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(ToolPackage[].class));
    }

    @Test
    @DisplayName("Counts and logs success/failure without rethrowing")
    void shouldSwallowExceptionAndCountFailure() {
      // ARRANGE
      final GenericContainer<?> good = mock(GenericContainer.class);
      final GenericContainer<?> bad = mock(GenericContainer.class);
      final SentinelCluster cluster = buildClusterFromContainers(good, List.of(), List.of(bad));

      doThrow(new RuntimeException("install failed"))
          .when(packageInstaller)
          .ensureInstalled(
              org.mockito.ArgumentMatchers.eq(bad),
              org.mockito.ArgumentMatchers.any(ToolPackage[].class));

      // ACT — must not throw
      factory.installPackages(cluster, "test-cluster", new String[] {"vim"});

      // ASSERT — good container was still attempted
      verify(packageInstaller)
          .ensureInstalled(
              org.mockito.ArgumentMatchers.eq(good),
              org.mockito.ArgumentMatchers.any(ToolPackage[].class));
    }
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  @SuppressWarnings("rawtypes")
  private static SentinelCluster buildCluster(final int replicaCount, final int sentinelCount) {
    final GenericContainer master = mock(GenericContainer.class);
    final List<GenericContainer<?>> replicas = new java.util.ArrayList<>();
    for (int i = 0; i < replicaCount; i++) {
      replicas.add(mock(GenericContainer.class));
    }
    final List<GenericContainer<?>> sentinels = new java.util.ArrayList<>();
    for (int i = 0; i < sentinelCount; i++) {
      sentinels.add(mock(GenericContainer.class));
    }
    return buildClusterFromContainers(master, replicas, sentinels);
  }

  @SuppressWarnings("rawtypes")
  private static SentinelCluster buildClusterFromContainers(
      final GenericContainer master,
      final List<GenericContainer<?>> replicas,
      final List<GenericContainer<?>> sentinels) {
    final org.testcontainers.containers.Network network =
        mock(org.testcontainers.containers.Network.class);
    return new SentinelCluster(network, master, replicas, sentinels, "mymaster");
  }

  private static RedisSentinel buildAnnotation(
      final String id, final boolean networkChaos, final String[] packages) {
    return new RedisSentinel() {
      @Override
      public Class<RedisSentinel> annotationType() {
        return RedisSentinel.class;
      }

      @Override
      public String id() {
        return id;
      }

      @Override
      public String version() {
        return "7.4";
      }

      @Override
      public String masterName() {
        return "mymaster";
      }

      @Override
      public int replicas() {
        return 2;
      }

      @Override
      public int sentinels() {
        return 3;
      }

      @Override
      public int quorum() {
        return 2;
      }

      @Override
      public boolean enableNetworkChaos() {
        return networkChaos;
      }

      @Override
      public String[] packages() {
        return packages;
      }
    };
  }
}
