/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.network;

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

import com.macstab.chaos.core.command.network.IptablesCommandBuilder;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;

/**
 * Unit tests for {@link NetworkRedirectManager}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("NetworkRedirectManager")
class NetworkRedirectManagerTest {

  private NetworkRedirect networkRedirect;
  private GenericContainer<?> container;
  private Shell shell;
  private Platform platform;
  private IptablesCommandBuilder networkBuilder;

  @BeforeEach
  void setUp() {
    networkRedirect = new NetworkRedirectManager();
    container = mock(GenericContainer.class);
    shell = mock(Shell.class);
    platform = mock(Platform.class);
    networkBuilder = new IptablesCommandBuilder();

    when(container.isRunning()).thenReturn(true);
    when(platform.getNetworkCommandBuilder()).thenReturn(networkBuilder);
  }

  @Nested
  @DisplayName("Setup Redirect")
  class SetupRedirectTests {

    @Test
    @DisplayName("should setup redirect successfully")
    void shouldSetupRedirect_successfully() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        networkRedirect.setupRedirect(container, shell, 6379, 16379);

        // THEN
        verify(shell).exec(eq(container), anyString());
      }
    }

    @Test
    @DisplayName("should throw IOException when setup fails")
    void shouldThrowIOException_whenSetupFails() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(1, "", "iptables error");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN / THEN
        assertThatThrownBy(() -> networkRedirect.setupRedirect(container, shell, 6379, 16379))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to setup");
      }
    }

    @Test
    @DisplayName("should validate port range")
    void shouldValidate_portRange() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> networkRedirect.setupRedirect(container, shell, 0, 16379))
          .withMessageContaining("must be in range [1, 65535]");

      assertThatIllegalArgumentException()
          .isThrownBy(() -> networkRedirect.setupRedirect(container, shell, 6379, 99999))
          .withMessageContaining("must be in range [1, 65535]");
    }
  }

  @Nested
  @DisplayName("Remove Redirect")
  class RemoveRedirectTests {

    @Test
    @DisplayName("should remove redirect successfully")
    void shouldRemoveRedirect_successfully() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        networkRedirect.removeRedirect(container, shell, 6379, 16379);

        // THEN
        verify(shell).exec(eq(container), anyString());
      }
    }
  }

  @Nested
  @DisplayName("Clear All Redirects")
  class ClearAllRedirectsTests {

    @Test
    @DisplayName("should clear all redirects successfully")
    void shouldClearAllRedirects_successfully() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(0, "", "");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN
        networkRedirect.clearAllRedirects(container, shell);

        // THEN
        verify(shell).exec(eq(container), anyString());
      }
    }

    @Test
    @DisplayName("should not throw when clear fails (may not exist)")
    void shouldNotThrow_whenClearFails() throws Exception {
      // GIVEN
      try (var detector = mockStatic(PlatformDetector.class)) {
        detector.when(() -> PlatformDetector.detect(container)).thenReturn(platform);
        final ExecResult result = mockExecResult(1, "", "No chain/target");
        when(shell.exec(eq(container), anyString())).thenReturn(result);

        // WHEN / THEN - Should not throw
        assertThatNoException()
            .isThrownBy(() -> networkRedirect.clearAllRedirects(container, shell));
      }
    }
  }

  // ==================== Test Helpers ====================

  private static ExecResult mockExecResult(
      final int exitCode, final String stdout, final String stderr) {
    final ExecResult result = mock(ExecResult.class);
    lenient().when(result.getExitCode()).thenReturn(exitCode);
    lenient().when(result.getStdout()).thenReturn(stdout);
    lenient().when(result.getStderr()).thenReturn(stderr);
    return result;
  }
}
