/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.*;

/**
 * Coverage tests for all exception classes.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Exception Classes - Coverage")
class ExceptionCoverageTest {

  @Nested
  @DisplayName("ChaosException")
  class ChaosExceptionTest {
    @Test
    void messageConstructor() {
      ChaosException e = new ChaosException("msg");
      assertThat(e.getMessage()).contains("msg");
    }

    @Test
    void messageCauseConstructor() {
      Throwable cause = new RuntimeException("cause");
      ChaosException e = new ChaosException("msg", cause);
      assertThat(e.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("ChaosConfigurationException")
  class ChaosConfigurationExceptionTest {
    @Test
    void messageConstructor() {
      ChaosConfigurationException e = new ChaosConfigurationException("bad config");
      assertThat(e.getMessage()).contains("bad config");
      assertThat(e).isInstanceOf(ChaosException.class);
    }
  }

  @Nested
  @DisplayName("ChaosProviderNotFoundException")
  class ChaosProviderNotFoundExceptionTest {
    @Test
    void messageConstructor() {
      ChaosProviderNotFoundException e = new ChaosProviderNotFoundException("provider not found");
      assertThat(e.getMessage()).contains("provider not found");
      assertThat(e).isInstanceOf(ChaosException.class);
    }
  }

  @Nested
  @DisplayName("ChaosUnsupportedOperationException")
  class ChaosUnsupportedOperationExceptionTest {
    @Test
    void messageConstructor() {
      ChaosUnsupportedOperationException e =
          new ChaosUnsupportedOperationException("not supported");
      assertThat(e.getMessage()).contains("not supported");
      assertThat(e).isInstanceOf(ChaosException.class);
    }
  }

  @Nested
  @DisplayName("ChaosOperationFailedException")
  class ChaosOperationFailedExceptionTest {
    @Test
    void messageConstructor() {
      ChaosOperationFailedException e = new ChaosOperationFailedException("failed");
      assertThat(e.getMessage()).contains("failed");
    }

    @Test
    void messageCauseConstructor() {
      Throwable cause = new RuntimeException("root");
      ChaosOperationFailedException e = new ChaosOperationFailedException("failed", cause);
      assertThat(e.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("ClusterException")
  class ClusterExceptionTest {
    @Test
    void messageConstructor() {
      ClusterException e = new ClusterException("cluster error");
      assertThat(e.getMessage()).contains("cluster error");
    }

    @Test
    void messageCauseConstructor() {
      Throwable cause = new RuntimeException("cause");
      ClusterException e = new ClusterException("cluster error", cause);
      assertThat(e.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("PluginRegistrationException")
  class PluginRegistrationExceptionTest {
    @Test
    void messageConstructor() {
      PluginRegistrationException e = new PluginRegistrationException("bad plugin");
      assertThat(e.getMessage()).contains("bad plugin");
    }

    @Test
    void messageCauseConstructor() {
      Throwable cause = new RuntimeException("root");
      PluginRegistrationException e = new PluginRegistrationException("bad plugin", cause);
      assertThat(e.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("ContainerOperationException")
  class ContainerOperationExceptionTest {
    @Test
    void fullConstructor() {
      ContainerOperationException e =
          new ContainerOperationException(
              "restart", "abc123def456", "Container failed to restart", null);
      assertThat(e.getOperation()).isEqualTo("restart");
      assertThat(e.getContainerId()).isEqualTo("abc123def456");
      assertThat(e.getMessage()).contains("restart");
    }

    @Test
    void withCause() {
      Throwable cause = new RuntimeException("docker error");
      ContainerOperationException e =
          new ContainerOperationException("kill", "abc123def456", "Kill failed", cause);
      assertThat(e.getCause()).isSameAs(cause);
      assertThat(e.getOperation()).isEqualTo("kill");
    }

    @Test
    void noCauseConstructor() {
      ContainerOperationException e =
          new ContainerOperationException("pause", "abc123def456", "Pause timed out");
      assertThat(e.getOperation()).isEqualTo("pause");
      assertThat(e.getContainerId()).isEqualTo("abc123def456");
      assertThat(e.getMessage()).contains("pause");
      assertThat(e.getCause()).isNull();
    }
  }

  @Nested
  @DisplayName("PackageInstallationException")
  class PackageInstallationExceptionTest {
    @Test
    void fullConstructor() {
      PackageInstallationException e =
          new PackageInstallationException(
              "install failed", "abc123", List.of("curl"), 1, "stdout output", "stderr output");
      assertThat(e.getContainerId()).isEqualTo("abc123");
      assertThat(e.getPackages()).containsExactly("curl");
      assertThat(e.getExitCode()).isEqualTo(1);
      assertThat(e.getStdout()).isEqualTo("stdout output");
      assertThat(e.getStderr()).isEqualTo("stderr output");
      assertThat(e.getMessage()).isNotBlank();
    }

    @Test
    void causeConstructor() {
      Throwable cause = new RuntimeException("network error");
      PackageInstallationException e =
          new PackageInstallationException("install failed", "abc123", List.of("wget"), cause);
      assertThat(e.getCause()).isSameAs(cause);
      assertThat(e.getContainerId()).isEqualTo("abc123");
      assertThat(e.getPackages()).containsExactly("wget");
    }

    @Test
    void rejectsZeroExitCode() {
      assertThatThrownBy(
              () -> new PackageInstallationException("msg", "abc123", List.of("curl"), 0, "", ""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("exitCode");
    }

    @Test
    void rejectsEmptyPackageList() {
      assertThatThrownBy(
              () -> new PackageInstallationException("msg", "abc123", List.of(), 1, "", ""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("packages");
    }

    @Test
    void rejectsEmptyPackageListWithCause() {
      assertThatThrownBy(
              () ->
                  new PackageInstallationException(
                      "msg", "abc123", List.of(), new RuntimeException("x")))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullContainerId() {
      assertThatThrownBy(
              () -> new PackageInstallationException("msg", null, List.of("curl"), 1, "", ""))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
