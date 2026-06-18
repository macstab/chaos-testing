/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.ToolPackage;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.extension.RedisContainerExtension.Store;

/**
 * Unit tests for {@link DefaultStandaloneContainerInstanceFactory}.
 *
 * <p>{@link PackageInstallerPort} is injected as a mock. Container configuration methods ({@link
 * DefaultStandaloneContainerInstanceFactory#buildContainer buildContainer}) are tested directly via
 * the package-private accessor. Container startup is covered by integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultStandaloneContainerInstanceFactory")
class DefaultStandaloneContainerInstanceFactoryTest {

  @Mock private PackageInstallerPort packageInstaller;

  private DefaultStandaloneContainerInstanceFactory factory;

  @BeforeEach
  void setUp() {
    factory = new DefaultStandaloneContainerInstanceFactory(packageInstaller);
  }

  // ─── buildContainer() ────────────────────────────────────────────────────

  @Nested
  @DisplayName("buildContainer()")
  class BuildContainerTests {

    @Test
    @DisplayName("Exposes port 6379 by default")
    void shouldExposePort6379() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation("id", "7.4", 0, new String[0], false, new String[0]));

      // ASSERT
      assertThat(container.getExposedPorts()).contains(6379);
    }

    @Test
    @DisplayName("Uses the version from the annotation in the image name")
    void shouldUseAnnotationVersion() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation("id", "7.2", 0, new String[0], false, new String[0]));

      // ASSERT
      assertThat(container.getDockerImageName()).isEqualTo("redis:7.2");
    }

    @Test
    @DisplayName("Sets port binding when annotation.port() > 0")
    void shouldSetPortBindingWhenPortSpecified() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation("id", "7.4", 6400, new String[0], false, new String[0]));

      // ASSERT — Testcontainers appends /tcp to port bindings
      assertThat(container.getPortBindings()).contains("6400:6379/tcp");
    }

    @Test
    @DisplayName("No port binding when annotation.port() == 0")
    void shouldNotSetPortBindingWhenPortIsZero() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation("id", "7.4", 0, new String[0], false, new String[0]));

      // ASSERT
      assertThat(container.getPortBindings()).isEmpty();
    }

    @Test
    @DisplayName("Prepends redis-server to custom args")
    void shouldPrependRedisServerToCustomArgs() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation(
                  "id",
                  "7.4",
                  0,
                  new String[] {"--save", "", "--appendonly", "yes"},
                  false,
                  new String[0]));

      // ASSERT
      assertThat(container.getCommandParts())
          .containsSequence("redis-server", "--save", "", "--appendonly", "yes");
    }

    @Test
    @DisplayName("No command override when args is empty")
    void shouldNotSetCommandWhenArgsIsEmpty() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation("id", "7.4", 0, new String[0], false, new String[0]));

      // ASSERT — default entrypoint unchanged, no explicit command override
      assertThat(container.getCommandParts()).isNullOrEmpty();
    }

    @Test
    @DisplayName("Registers container cmd modifier when enableNetworkChaos=true")
    void shouldRegisterModifierWhenChaosEnabled() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation("id", "7.4", 0, new String[0], true, new String[0]));

      // ASSERT — modifier registered (non-null, non-empty modifier set)
      assertThat(container.getCreateContainerCmdModifiers()).isNotEmpty();
    }

    @Test
    @DisplayName("No container cmd modifier when enableNetworkChaos=false")
    void shouldNotRegisterModifierWhenChaosDisabled() {
      // ACT
      final GenericContainer<?> container =
          factory.buildContainer(
              buildAnnotation("id", "7.4", 0, new String[0], false, new String[0]));

      // ASSERT
      assertThat(container.getCreateContainerCmdModifiers()).isEmpty();
    }
  }

  // ─── installNetworkTools() ───────────────────────────────────────────────

  @Nested
  @DisplayName("installNetworkTools()")
  class InstallNetworkToolsTests {

    @Test
    @DisplayName("Does nothing when enableNetworkChaos=false")
    void shouldSkipWhenChaosDisabled() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisStandalone annotation =
          buildAnnotation("id", "7.4", 0, new String[0], false, new String[0]);

      // ACT
      factory.installNetworkTools(container, annotation);

      // ASSERT — ensureInstalled never called when chaos disabled
      verify(packageInstaller, never())
          .ensureInstalled(ArgumentMatchers.any(), ArgumentMatchers.any(Tool[].class));
    }

    @Test
    @DisplayName("Calls ensureInstalled with IPROUTE and IPTABLES when chaos enabled")
    void shouldEnsureInstalledWhenChaosEnabled() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisStandalone annotation =
          buildAnnotation("id", "7.4", 0, new String[0], true, new String[0]);

      // ACT
      factory.installNetworkTools(container, annotation);

      // ASSERT
      verify(packageInstaller).ensureInstalled(container, Tool.IPROUTE, Tool.IPTABLES);
    }

    @Test
    @DisplayName("Swallows exception without rethrowing")
    void shouldSwallowExceptionOnInstallFailure() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisStandalone annotation =
          buildAnnotation("id", "7.4", 0, new String[0], true, new String[0]);
      doThrow(new RuntimeException("exec failed"))
          .when(packageInstaller)
          .ensureInstalled(ArgumentMatchers.eq(container), ArgumentMatchers.any(Tool[].class));

      // ACT — must not throw
      factory.installNetworkTools(container, annotation);
    }
  }

  // ─── installAnnotationPackages() ─────────────────────────────────────────

  @Nested
  @DisplayName("installAnnotationPackages()")
  class InstallAnnotationPackagesTests {

    @Test
    @DisplayName("Does nothing when packages array is empty")
    void shouldSkipWhenNoPackages() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisStandalone annotation =
          buildAnnotation("id", "7.4", 0, new String[0], false, new String[0]);

      // ACT
      factory.installAnnotationPackages(container, annotation);

      // ASSERT
      verify(packageInstaller, never())
          .install(
              ArgumentMatchers.any(),
              ArgumentMatchers.any(java.util.Collection.class),
              ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("Calls ensureInstalled with ToolPackages for specified packages")
    void shouldEnsureInstalledPackages() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisStandalone annotation =
          buildAnnotation("id", "7.4", 0, new String[0], false, new String[] {"curl", "jq"});

      // ACT
      factory.installAnnotationPackages(container, annotation);

      // ASSERT
      verify(packageInstaller)
          .ensureInstalled(
              ArgumentMatchers.eq(container), ArgumentMatchers.any(ToolPackage[].class));
    }

    @Test
    @DisplayName("Swallows exception without rethrowing")
    void shouldSwallowExceptionOnInstallFailure() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      final RedisStandalone annotation =
          buildAnnotation("id", "7.4", 0, new String[0], false, new String[] {"curl"});
      doThrow(new RuntimeException("package not found"))
          .when(packageInstaller)
          .ensureInstalled(
              ArgumentMatchers.eq(container), ArgumentMatchers.any(ToolPackage[].class));

      // ACT — must not throw
      factory.installAnnotationPackages(container, annotation);
    }
  }

  // ─── buildSuccessResult() ────────────────────────────────────────────────

  @Nested
  @DisplayName("buildSuccessResult()")
  class BuildSuccessResultTests {

    @Test
    @DisplayName("Returns Success with host and mapped port from container")
    @SuppressWarnings("rawtypes")
    void shouldBuildSuccessFromContainer() {
      // ARRANGE
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getHost()).thenReturn("localhost");
      when(container.getMappedPort(6379)).thenReturn(54321);
      final RedisStandalone annotation =
          buildAnnotation("inst1", "7.4", 0, new String[0], false, new String[0]);

      // ACT
      final StartupResult result = factory.buildSuccessResult(container, annotation);

      // ASSERT
      assertThat(result).isInstanceOf(StartupResult.Success.class);
      final StartupResult.Success success = (StartupResult.Success) result;
      assertThat(success.instanceId()).isEqualTo("inst1");
      assertThat(success.connectionInfo().getHost()).isEqualTo("localhost");
      assertThat(success.connectionInfo().getPort()).isEqualTo(54321);
    }

    @Test
    @DisplayName("Store in success result wraps the same container")
    @SuppressWarnings("rawtypes")
    void shouldWrapContainerInStore() {
      // ARRANGE
      final GenericContainer container = mock(GenericContainer.class);
      when(container.getHost()).thenReturn("localhost");
      when(container.getMappedPort(6379)).thenReturn(6379);
      final RedisStandalone annotation =
          buildAnnotation("inst1", "7.4", 0, new String[0], false, new String[0]);

      // ACT
      final StartupResult result = factory.buildSuccessResult(container, annotation);
      final Store store = ((StartupResult.Success) result).store();

      // ASSERT — stop the store, which should stop the wrapped container
      store.close();
      Mockito.verify(container).stop();
    }
  }

  // ─── create() failure path ───────────────────────────────────────────────

  @Nested
  @DisplayName("create() failure wrapping")
  class CreateFailureTests {

    @Test
    @DisplayName("Returns Failure when buildContainer produces a container whose start() throws")
    @SuppressWarnings("rawtypes")
    void shouldReturnFailureWhenContainerStartFails() {
      // ARRANGE — buildContainer returns a real (but unconfigured) container structure;
      // we use an annotation with an invalid image name so DockerImageName itself throws
      // before any Docker socket call, staying fully offline.
      final RedisStandalone annotation =
          buildAnnotation("inst1", "", 0, new String[0], false, new String[0]);

      // ACT — empty version → DockerImageName.parse("redis:") throws IAE
      final StartupResult result = factory.create(annotation);

      // ASSERT
      assertThat(result).isInstanceOf(StartupResult.Failure.class);
      assertThat(((StartupResult.Failure) result).instanceId()).isEqualTo("inst1");
    }
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private static RedisStandalone buildAnnotation(
      final String id,
      final String version,
      final int port,
      final String[] args,
      final boolean enableNetworkChaos,
      final String[] packages) {
    return new RedisStandalone() {
      @Override
      public Class<RedisStandalone> annotationType() {
        return RedisStandalone.class;
      }

      @Override
      public String id() {
        return id;
      }

      @Override
      public String version() {
        return version;
      }

      @Override
      public int port() {
        return port;
      }

      @Override
      public String[] args() {
        return args;
      }

      @Override
      public boolean enableNetworkChaos() {
        return enableNetworkChaos;
      }

      @Override
      public boolean enableConnectionChaos() {
        return false;
      }

      @Override
      public String[] packages() {
        return packages;
      }
    };
  }
}
