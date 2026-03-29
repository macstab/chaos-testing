/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;

/**
 * Unit tests for {@link ContainerNetworkUtils}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerNetworkUtils")
class ContainerNetworkUtilsTest {

  @Nested
  @DisplayName("getContainerBridgeIp")
  class GetContainerBridgeIp {

    @Nested
    @DisplayName("Null validation")
    class NullValidation {

      @Test
      @DisplayName("should throw NullPointerException when container is null")
      void shouldThrowNpe_whenContainerIsNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> ContainerNetworkUtils.getContainerBridgeIp(null))
            .withMessage("container must not be null");
      }
    }

    @Nested
    @DisplayName("Integration: real container")
    class IntegrationRealContainer {

      @Test
      @DisplayName("should resolve bridge IP of a running container")
      void shouldResolveBridgeIp_ofRunningContainer() {
        // GIVEN
        try (final GenericContainer<?> container =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))) {
          container.start();

          // WHEN
          final String ip = ContainerNetworkUtils.getContainerBridgeIp(container);

          // THEN
          assertThat(ip)
              .isNotBlank()
              .matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
        }
      }

      @Test
      @DisplayName("should resolve different IPs for two distinct containers")
      void shouldResolveDifferentIps_forTwoDistinctContainers() {
        // GIVEN
        try (final GenericContainer<?> c1 =
                new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"));
            final GenericContainer<?> c2 =
                new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))) {

          c1.start();
          c2.start();

          // WHEN
          final String ip1 = ContainerNetworkUtils.getContainerBridgeIp(c1);
          final String ip2 = ContainerNetworkUtils.getContainerBridgeIp(c2);

          // THEN — each container has a unique bridge IP
          assertThat(ip1).isNotBlank();
          assertThat(ip2).isNotBlank();
          assertThat(ip1).isNotEqualTo(ip2);
        }
      }
    }

    @Nested
    @DisplayName("Error paths")
    class ErrorPaths {

      @Test
      @DisplayName("should throw when container has no network settings")
      void shouldThrow_whenNoNetworkSettings() {
        // GIVEN
        final var container = mock(GenericContainer.class);
        final var dockerClient = mock(DockerClient.class);
        final var inspectCmd = mock(InspectContainerCmd.class);
        final var inspectResponse = mock(InspectContainerResponse.class);

        when(container.getContainerId()).thenReturn("abc123def456");
        when(container.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("abc123def456")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(inspectResponse.getNetworkSettings()).thenReturn(null);

        // WHEN / THEN
        assertThatThrownBy(() -> ContainerNetworkUtils.getContainerBridgeIp(container))
            .isInstanceOf(ChaosOperationFailedException.class)
            .hasMessageContaining("no network settings");
      }

      @Test
      @DisplayName("should throw when container has no networks attached")
      void shouldThrow_whenNoNetworksAttached() {
        // GIVEN
        final var container = mock(GenericContainer.class);
        final var dockerClient = mock(DockerClient.class);
        final var inspectCmd = mock(InspectContainerCmd.class);
        final var inspectResponse = mock(InspectContainerResponse.class);
        final var networkSettings = mock(NetworkSettings.class);

        when(container.getContainerId()).thenReturn("abc123def456");
        when(container.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("abc123def456")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(inspectResponse.getNetworkSettings()).thenReturn(networkSettings);
        when(networkSettings.getNetworks()).thenReturn(Collections.emptyMap());

        // WHEN / THEN
        assertThatThrownBy(() -> ContainerNetworkUtils.getContainerBridgeIp(container))
            .isInstanceOf(ChaosOperationFailedException.class)
            .hasMessageContaining("not attached to any Docker network");
      }

      @Test
      @DisplayName("should throw when all networks have blank IP addresses")
      void shouldThrow_whenAllNetworksHaveBlankIps() {
        // GIVEN
        final var container = mock(GenericContainer.class);
        final var dockerClient = mock(DockerClient.class);
        final var inspectCmd = mock(InspectContainerCmd.class);
        final var inspectResponse = mock(InspectContainerResponse.class);
        final var networkSettings = mock(NetworkSettings.class);
        final var blankNetwork = mock(ContainerNetwork.class);

        when(container.getContainerId()).thenReturn("abc123def456");
        when(container.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("abc123def456")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(inspectResponse.getNetworkSettings()).thenReturn(networkSettings);
        when(networkSettings.getNetworks()).thenReturn(Map.of("bridge", blankNetwork));
        when(blankNetwork.getIpAddress()).thenReturn("   ");

        // WHEN / THEN
        assertThatThrownBy(() -> ContainerNetworkUtils.getContainerBridgeIp(container))
            .isInstanceOf(ChaosOperationFailedException.class)
            .hasMessageContaining("No usable IP address");
      }

      @Test
      @DisplayName("should wrap unexpected exceptions in ChaosOperationFailedException")
      void shouldWrapUnexpectedExceptions() {
        // GIVEN
        final var container = mock(GenericContainer.class);
        final var dockerClient = mock(DockerClient.class);
        final var inspectCmd = mock(InspectContainerCmd.class);

        when(container.getContainerId()).thenReturn("abc123def456");
        when(container.getDockerClient()).thenReturn(dockerClient);
        when(dockerClient.inspectContainerCmd("abc123def456")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new RuntimeException("Docker daemon unreachable"));

        // WHEN / THEN
        assertThatThrownBy(() -> ContainerNetworkUtils.getContainerBridgeIp(container))
            .isInstanceOf(ChaosOperationFailedException.class)
            .hasMessageContaining("Failed to resolve bridge IP")
            .hasMessageContaining("Docker daemon unreachable");
      }
    }
  }

  @Nested
  @DisplayName("Utility class contract")
  class UtilityClassContract {

    @Test
    @DisplayName("should not be instantiable")
    void shouldNotBeInstantiable() {
      assertThatThrownBy(
              () -> {
                final var constructor =
                    ContainerNetworkUtils.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                constructor.newInstance();
              })
          .cause()
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }
}
