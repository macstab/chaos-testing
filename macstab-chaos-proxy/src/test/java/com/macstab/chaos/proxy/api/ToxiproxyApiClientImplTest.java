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
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.proxy.support.TestExecResults;
import com.macstab.chaos.proxy.internal.ContainerContext;
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
  /** Pre-built context — available in all tests without triggering Mockito side-effects. */
  private ContainerContext ctx;

  @BeforeEach
  void setUp() {
    apiClient = new ToxiproxyApiClientImpl("http://localhost:8474");
    container = mock(GenericContainer.class);
    shell = mock(Shell.class);
    platform = mock(Platform.class);
    httpBuilder = new CurlCommandBuilder();

    when(container.isRunning()).thenReturn(true);
    when(platform.getHttpCommandBuilder()).thenReturn(httpBuilder);
    when(platform.getDefaultShell()).thenReturn(shell);

    // Build context after all stubs are in place — avoids Mockito UnfinishedStubbingException
    ctx = ContainerContext.of(container, platform, shell);
  }

  // ==================== Tests ====================

  @Nested
  @DisplayName("isApiReady")
  class IsApiReadyTests {

    @Test
    @DisplayName("should return true when API responds with exit code 0")
    void shouldReturnTrue_whenApiResponds() throws Exception {
      final ExecResult execResult1 = TestExecResults.of(0, "{}", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult1);

      assertThat(apiClient.isApiReady(ctx)).isTrue();
      verify(shell).exec(eq(container), contains("/proxies"));

    }

    @Test
    @DisplayName("should return false when API returns non-zero exit code")
    void shouldReturnFalse_whenApiReturnsError() throws Exception {
      final ExecResult execResult2 = TestExecResults.of(1, "", "Connection refused");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult2);

      assertThat(apiClient.isApiReady(ctx)).isFalse();

    }

    @Test
    @DisplayName("should return false when shell throws exception")
    void shouldReturnFalse_whenShellThrows() throws Exception {
      when(shell.exec(any(), any())).thenThrow(new IOException("Network error"));

      assertThat(apiClient.isApiReady(ctx)).isFalse();

    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.isApiReady(null))
          .withMessage("ctx must not be null");
    }
  }

  @Nested
  @DisplayName("proxyExists")
  class ProxyExistsTests {

    @Test
    @DisplayName("should return true when proxy exists")
    void shouldReturnTrue_whenProxyExists() throws Exception {
      final ExecResult execResult3 = TestExecResults.of(0, "{\"name\":\"redis\"}", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult3);

      assertThat(apiClient.proxyExists(ctx, "redis")).isTrue();
      verify(shell).exec(eq(container), contains("/proxies/redis"));

    }

    @Test
    @DisplayName("should return false when proxy does not exist")
    void shouldReturnFalse_whenProxyDoesNotExist() throws Exception {
      final ExecResult execResult4 = TestExecResults.of(1, "", "404 Not Found");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult4);

      assertThat(apiClient.proxyExists(ctx, "nonexistent")).isFalse();

    }

    @Test
    @DisplayName("should throw IOException when shell throws unexpected exception")
    void shouldThrowIOException_whenShellFails() throws Exception {
      when(shell.exec(any(), any())).thenThrow(new RuntimeException("Command failed"));

      assertThatThrownBy(() -> apiClient.proxyExists(ctx, "redis"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to check if proxy exists");

    }

    @Test
    @DisplayName("should throw NullPointerException when proxyName is null")
    void shouldThrowNpe_whenProxyNameIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.proxyExists(ctx, null))
          .withMessage("proxyName must not be null");

    }
  }

  @Nested
  @DisplayName("createProxy")
  class CreateProxyTests {

    @Test
    @DisplayName("should create proxy successfully")
    void shouldCreateProxy_successfully() throws Exception {
      final ProxyConfiguration config =
          new ProxyConfiguration("redis", 6379, 16379, "localhost");
      final ExecResult execResult5 = TestExecResults.of(0, "{\"name\":\"redis\"}", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult5);

      apiClient.createProxy(ctx, config);

      verify(shell).exec(eq(container), contains("/proxies"));

    }

    @Test
    @DisplayName("should throw IOException when creation fails")
    void shouldThrowIOException_whenCreationFails() throws Exception {
      final ProxyConfiguration config =
          new ProxyConfiguration("redis", 6379, 16379, "localhost");
      final ExecResult execResult6 = TestExecResults.of(1, "", "Proxy already exists");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult6);

      assertThatThrownBy(() -> apiClient.createProxy(ctx, config))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to create proxy");

    }

    @Test
    @DisplayName("should throw NullPointerException when config is null")
    void shouldThrowNpe_whenConfigIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.createProxy(ctx, null))
          .withMessage("config must not be null");

    }
  }

  @Nested
  @DisplayName("deleteProxy")
  class DeleteProxyTests {

    @Test
    @DisplayName("should delete proxy successfully")
    void shouldDeleteProxy_successfully() throws Exception {
      final ExecResult execResult7 = TestExecResults.of(0, "", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult7);

      apiClient.deleteProxy(ctx, "redis");

      verify(shell).exec(eq(container), contains("/proxies/redis"));

    }

    @Test
    @DisplayName("should throw IOException when deletion fails")
    void shouldThrowIOException_whenDeletionFails() throws Exception {
      final ExecResult execResult8 = TestExecResults.of(1, "", "Proxy not found");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult8);

      assertThatThrownBy(() -> apiClient.deleteProxy(ctx, "redis"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to delete proxy");

    }

    @Test
    @DisplayName("should throw NullPointerException when proxyName is null")
    void shouldThrowNpe_whenProxyNameIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.deleteProxy(ctx, null))
          .withMessage("proxyName must not be null");

    }
  }

  @Nested
  @DisplayName("toxicExists")
  class ToxicExistsTests {

    @Test
    @DisplayName("should return true when toxic exists in list")
    void shouldReturnTrue_whenToxicExistsInList() throws Exception {
      final String toxicsJson = "[{\"name\":\"latency\",\"type\":\"latency\"}]";
      final ExecResult execResult9 = TestExecResults.of(0, toxicsJson, "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult9);

      assertThat(apiClient.toxicExists(ctx, "redis", "latency")).isTrue();

    }

    @Test
    @DisplayName("should return false when toxic not in list")
    void shouldReturnFalse_whenToxicNotInList() throws Exception {
      final ExecResult execResult10 = TestExecResults.of(0, "[]", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult10);

      assertThat(apiClient.toxicExists(ctx, "redis", "latency")).isFalse();

    }

    @Test
    @DisplayName("should throw NullPointerException when toxicName is null")
    void shouldThrowNpe_whenToxicNameIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.toxicExists(ctx, "redis", null))
          .withMessage("toxicName must not be null");

    }
  }

  @Nested
  @DisplayName("addToxic")
  class AddToxicTests {

    @Test
    @DisplayName("should add toxic successfully")
    void shouldAddToxic_successfully() throws Exception {
      final ExecResult execResult11 = TestExecResults.of(0, "{\"name\":\"latency\"}", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult11);

      apiClient.addToxic(
          ctx, "redis", "latency", "latency", "{\"latency\":1000}", 1.0);

      verify(shell).exec(eq(container), contains("/proxies/redis/toxics"));

    }

    @Test
    @DisplayName("should throw IllegalArgumentException when toxicity < 0")
    void shouldThrow_whenToxicityBelowZero() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> apiClient.addToxic(
              ctx, "redis", "latency", "latency", "{}", -0.1))
          .withMessageContaining("toxicity must be in [0.0, 1.0]");

    }

    @Test
    @DisplayName("should throw IllegalArgumentException when toxicity > 1")
    void shouldThrow_whenToxicityAboveOne() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> apiClient.addToxic(
              ctx, "redis", "latency", "latency", "{}", 1.1))
          .withMessageContaining("toxicity must be in [0.0, 1.0]");

    }

    @Test
    @DisplayName("should throw NullPointerException when toxicName is null")
    void shouldThrowNpe_whenToxicNameIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.addToxic(
              ctx, "redis", null, "latency", "{}", 1.0))
          .withMessage("toxicName must not be null");

    }

    @Test
    @DisplayName("should throw NullPointerException when toxicType is null")
    void shouldThrowNpe_whenToxicTypeIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.addToxic(
              ctx, "redis", "latency", null, "{}", 1.0))
          .withMessage("toxicType must not be null");

    }

    @Test
    @DisplayName("should throw NullPointerException when attributes is null")
    void shouldThrowNpe_whenAttributesIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> apiClient.addToxic(
              ctx, "redis", "latency", "latency", null, 1.0))
          .withMessage("attributes must not be null");

    }
  }

  @Nested
  @DisplayName("deleteToxic")
  class DeleteToxicTests {

    @Test
    @DisplayName("should delete toxic successfully")
    void shouldDeleteToxic_successfully() throws Exception {
      final ExecResult execResult12 = TestExecResults.of(0, "", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult12);

      apiClient.deleteToxic(ctx, "redis", "latency");

      verify(shell).exec(eq(container), contains("/proxies/redis/toxics/latency"));

    }

    @Test
    @DisplayName("should throw IOException when deletion fails")
    void shouldThrowIOException_whenDeletionFails() throws Exception {
      final ExecResult execResult13 = TestExecResults.of(1, "", "Toxic not found");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult13);

      assertThatThrownBy(() -> apiClient.deleteToxic(ctx, "redis", "latency"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to delete toxic");

    }
  }

  @Nested
  @DisplayName("listToxics")
  class ListToxicsTests {

    @Test
    @DisplayName("should parse toxic names from JSON response")
    void shouldParseToxicNames_fromJsonResponse() throws Exception {
      final String json =
          "[{\"name\":\"latency\",\"type\":\"latency\"},"
              + "{\"name\":\"bandwidth\",\"type\":\"bandwidth\"}]";
      final ExecResult execResult14 = TestExecResults.of(0, json, "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult14);

      final var names = apiClient.listToxics(ctx, "redis");

      assertThat(names).containsExactlyInAnyOrder("latency", "bandwidth");

    }

    @Test
    @DisplayName("should return empty list when no toxics")
    void shouldReturnEmptyList_whenNoToxics() throws Exception {
      final ExecResult execResult15 = TestExecResults.of(0, "[]", "");
      when(shell.exec(eq(container), anyString()))
          .thenReturn(execResult15);

      assertThat(apiClient.listToxics(ctx, "redis")).isEmpty();

    }
  }
}
