/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.command.http.CurlCommandBuilder;
import com.macstab.chaos.core.command.http.HttpCommandBuilder;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.internal.model.ProxyConfiguration;

/**
 * Unit tests for {@link ToxiproxyApiClientImpl}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ToxiproxyApiClientImpl")
class ToxiproxyApiClientImplTest {

  private ToxiproxyApiClient apiClient;
  private GenericContainer<?> container;
  private Shell shell;
  private Platform platform;
  private HttpCommandBuilder httpBuilder;

  @BeforeEach
  void setUp() {
    apiClient = new ToxiproxyApiClientImpl("http://localhost:8474");
    container = mock(GenericContainer.class);
    shell = mock(Shell.class);
    platform = mock(Platform.class);
    httpBuilder = new CurlCommandBuilder();

    // Mock container.isRunning() for PlatformDetector
    when(container.isRunning()).thenReturn(true);

    // Mock platform to return HttpCommandBuilder
    when(platform.getHttpCommandBuilder()).thenReturn(httpBuilder);
  }

  @Nested
  @DisplayName("API Health Check")
  class ApiHealthCheckTests {

    @Test
    @DisplayName("should return true when API responds successfully")
    void shouldReturnTrue_whenApiResponds() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "{}", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        final boolean ready = apiClient.isApiReady(container, shell);

        // THEN
        assertThat(ready).isTrue();
        verify(shell).exec(eq(container), contains("/proxies"));
      }
    }

    @Test
    @DisplayName("should return false when API returns error")
    void shouldReturnFalse_whenApiReturnsError() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(1, "", "Connection refused");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        final boolean ready = apiClient.isApiReady(container, shell);

        // THEN
        assertThat(ready).isFalse();
      }
    }

    @Test
    @DisplayName("should return false when shell execution throws exception")
    void shouldReturnFalse_whenShellThrowsException() throws Exception {
      // GIVEN
      when(shell.exec(any(), any())).thenThrow(new IOException("Network error"));

      // WHEN
      final boolean ready = apiClient.isApiReady(container, shell);

      // THEN
      assertThat(ready).isFalse();
    }
  }

  @Nested
  @DisplayName("Proxy Existence Check")
  class ProxyExistenceCheckTests {

    @Test
    @DisplayName("should return true when proxy exists")
    void shouldReturnTrue_whenProxyExists() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "{\"name\":\"redis\"}", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        final boolean exists = apiClient.proxyExists(container, shell, "redis");

        // THEN
        assertThat(exists).isTrue();
        verify(shell).exec(eq(container), contains("/proxies/redis"));
      }
    }

    @Test
    @DisplayName("should return false when proxy does not exist")
    void shouldReturnFalse_whenProxyDoesNotExist() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(1, "", "404 Not Found");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        final boolean exists = apiClient.proxyExists(container, shell, "nonexistent");

        // THEN
        assertThat(exists).isFalse();
      }
    }

    @Test
    @DisplayName("should throw IOException when shell execution fails")
    void shouldThrowIOException_whenShellFails() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        when(shell.exec(any(), any())).thenThrow(new RuntimeException("Command failed"));

        // WHEN / THEN
        assertThatThrownBy(() -> apiClient.proxyExists(container, shell, "redis"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to check if proxy exists");
      }
    }
  }

  @Nested
  @DisplayName("Proxy Creation")
  class ProxyCreationTests {

    @Test
    @DisplayName("should create proxy successfully")
    void shouldCreateProxy_successfully() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ProxyConfiguration config = new ProxyConfiguration("redis", 6379, 16379, "localhost");
        final ExecResult result = mockExecResult(0, "{\"name\":\"redis\"}", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        apiClient.createProxy(container, shell, config);

        // THEN
        verify(shell).exec(eq(container), contains("/proxies"));
        verify(shell).exec(eq(container), contains("redis"));
      }
    }

    @Test
    @DisplayName("should throw IOException when creation fails")
    void shouldThrowIOException_whenCreationFails() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ProxyConfiguration config = new ProxyConfiguration("redis", 6379, 16379, "localhost");
        final ExecResult result = mockExecResult(1, "", "Proxy already exists");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN / THEN
        assertThatThrownBy(() -> apiClient.createProxy(container, shell, config))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to create proxy");
      }
    }

    @Test
    @DisplayName("should validate configuration is not null")
    void shouldValidateConfiguration() {
      // WHEN / THEN
      assertThatThrownBy(() -> apiClient.createProxy(container, shell, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }
  }

  @Nested
  @DisplayName("Proxy Deletion")
  class ProxyDeletionTests {

    @Test
    @DisplayName("should delete proxy successfully")
    void shouldDeleteProxy_successfully() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        apiClient.deleteProxy(container, shell, "redis");

        // THEN
        verify(shell).exec(eq(container), contains("/proxies/redis"));
      }
    }

    @Test
    @DisplayName("should throw IOException when deletion fails")
    void shouldThrowIOException_whenDeletionFails() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(1, "", "Proxy not found");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN / THEN
        assertThatThrownBy(() -> apiClient.deleteProxy(container, shell, "redis"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to delete proxy");
      }
    }
  }

  @Nested
  @DisplayName("Toxic Operations")
  class ToxicTests {

    @Test
    @DisplayName("should check if toxic exists")
    void shouldCheckToxicExists() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        final boolean exists = apiClient.toxicExists(container, shell, "redis", "latency");

        // THEN
        assertThat(exists).isTrue();
      }
    }

    @Test
    @DisplayName("should add toxic successfully")
    void shouldAddToxic_successfully() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "{\"name\":\"latency\"}", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        apiClient.addToxic(
            container, shell, "redis", "latency", "latency", "{\"latency\":1000}", 1.0);

        // THEN
        verify(shell).exec(eq(container), contains("/proxies/redis/toxics"));
      }
    }

    @Test
    @DisplayName("should validate toxicity range")
    void shouldValidateToxicityRange() {
      // WHEN / THEN - toxicity < 0
      assertThatThrownBy(
              () ->
                  apiClient.addToxic(
                      container, shell, "redis", "latency", "latency", "{}", -0.1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity must be in [0.0, 1.0]");

      // WHEN / THEN - toxicity > 1
      assertThatThrownBy(
              () ->
                  apiClient.addToxic(
                      container, shell, "redis", "latency", "latency", "{}", 1.1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity must be in [0.0, 1.0]");
    }
  }

  @Nested
  @DisplayName("Validation")
  class ValidationTests {

    @Test
    @DisplayName("should validate container is not null (isApiReady)")
    void shouldValidateContainer_isApiReady() {
      // WHEN / THEN
      assertThatThrownBy(() -> apiClient.isApiReady(null, shell))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("should validate shell is not null (isApiReady)")
    void shouldValidateShell_isApiReady() {
      // WHEN / THEN
      assertThatThrownBy(() -> apiClient.isApiReady(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("shell");
    }

    @Test
    @DisplayName("should validate proxy name is not null (proxyExists)")
    void shouldValidateProxyName_proxyExists() {
      // WHEN / THEN
      assertThatThrownBy(() -> apiClient.proxyExists(container, shell, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("should validate proxy name is not null (deleteProxy)")
    void shouldValidateProxyName_deleteProxy() {
      // WHEN / THEN
      assertThatThrownBy(() -> apiClient.deleteProxy(container, shell, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("proxyName");
    }

    @Test
    @DisplayName("should validate toxic name is not null (toxicExists)")
    void shouldValidateToxicName_toxicExists() {
      // WHEN / THEN
      assertThatThrownBy(() -> apiClient.toxicExists(container, shell, "redis", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("toxicName");
    }

    @Test
    @DisplayName("should validate toxic name is not null (addToxic)")
    void shouldValidateToxicName_addToxic() {
      // WHEN / THEN
      assertThatThrownBy(
              () -> apiClient.addToxic(container, shell, "redis", null, "latency", "{}", 1.0))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("toxicName");
    }

    @Test
    @DisplayName("should validate toxic type is not null (addToxic)")
    void shouldValidateToxicType_addToxic() {
      // WHEN / THEN
      assertThatThrownBy(
              () -> apiClient.addToxic(container, shell, "redis", "latency", null, "{}", 1.0))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("toxicType");
    }

    @Test
    @DisplayName("should validate attributes is not null (addToxic)")
    void shouldValidateAttributes_addToxic() {
      // WHEN / THEN
      assertThatThrownBy(
              () ->
                  apiClient.addToxic(container, shell, "redis", "latency", "latency", null, 1.0))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("attributes");
    }
  }

  // ==================== Test Helpers ====================

  private static ExecResult mockExecResult(
      final int exitCode, final String stdout, final String stderr) {
    final ExecResult result = mock(ExecResult.class);
    when(result.getExitCode()).thenReturn(exitCode);
    when(result.getStdout()).thenReturn(stdout);
    when(result.getStderr()).thenReturn(stderr);
    return result;
  }
}
