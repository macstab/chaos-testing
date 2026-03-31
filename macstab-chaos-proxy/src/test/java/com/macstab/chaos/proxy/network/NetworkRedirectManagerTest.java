/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.network;
 import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.network.NetworkRedirect;
import com.macstab.chaos.toxiproxy.network.NetworkRedirectManager;





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
import com.macstab.chaos.core.shell.Shell;

import com.macstab.chaos.proxy.support.TestExecResults;

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
  private ContainerContext ctx;

  @BeforeEach
  void setUp() {
    networkRedirect = new NetworkRedirectManager();
    container = mock(GenericContainer.class);
    shell = mock(Shell.class);
    platform = mock(Platform.class);

    when(container.isRunning()).thenReturn(true);
    when(platform.getNetworkCommandBuilder()).thenReturn(new IptablesCommandBuilder());
    when(platform.getDefaultShell()).thenReturn(shell);

    // Pre-build context after stubs are in place — avoids Mockito ordering issues
    ctx = ContainerContext.of(container, platform, shell);
  }

  @Nested
  @DisplayName("Setup Redirect")
  class SetupRedirectTests {

    @Test
    @DisplayName("should setup redirect successfully")
    void shouldSetupRedirect_successfully() throws Exception {
      // GIVEN
      final ExecResult result = TestExecResults.of(0, "", "");
      when(shell.exec(eq(container), anyString())).thenReturn(result);

      // WHEN
      networkRedirect.setupRedirect(ctx, 6379, 16379);

      // THEN
      verify(shell).exec(eq(container), anyString());
    }

    @Test
    @DisplayName("should throw IOException when command fails")
    void shouldThrowIOException_whenSetupFails() throws Exception {
      // GIVEN
      final ExecResult result = TestExecResults.of(1, "", "iptables error");
      when(shell.exec(eq(container), anyString())).thenReturn(result);

      // WHEN / THEN
      assertThatThrownBy(() -> networkRedirect.setupRedirect(ctx, 6379, 16379))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to setup");
    }

    @Test
    @DisplayName("should throw IOException when shell throws")
    void shouldThrowIOException_whenShellThrows() throws Exception {
      // GIVEN
      when(shell.exec(eq(container), anyString())).thenThrow(new RuntimeException("exec failed"));

      // WHEN / THEN
      assertThatThrownBy(() -> networkRedirect.setupRedirect(ctx, 6379, 16379))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to setup redirect");
    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> networkRedirect.setupRedirect(null, 6379, 16379))
          .withMessage("ctx must not be null");
    }

    @Test
    @DisplayName("should reject servicePort out of range")
    void shouldRejectServicePort_outOfRange() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> networkRedirect.setupRedirect(ctx, 0, 16379))
          .withMessageContaining("must be in range [1, 65535]");

      assertThatIllegalArgumentException()
          .isThrownBy(() -> networkRedirect.setupRedirect(ctx, 65536, 16379))
          .withMessageContaining("must be in range [1, 65535]");
    }

    @Test
    @DisplayName("should reject proxyPort out of range")
    void shouldRejectProxyPort_outOfRange() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> networkRedirect.setupRedirect(ctx, 6379, 99999))
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
      final ExecResult result = TestExecResults.of(0, "", "");
      when(shell.exec(eq(container), anyString())).thenReturn(result);

      // WHEN
      networkRedirect.removeRedirect(ctx, 6379, 16379);

      // THEN
      verify(shell).exec(eq(container), anyString());
    }

    @Test
    @DisplayName("should throw IOException when removal fails")
    void shouldThrowIOException_whenRemovalFails() throws Exception {
      // GIVEN
      final ExecResult result = TestExecResults.of(1, "", "No rule to delete");
      when(shell.exec(eq(container), anyString())).thenReturn(result);

      // WHEN / THEN
      assertThatThrownBy(() -> networkRedirect.removeRedirect(ctx, 6379, 16379))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to remove");
    }

    @Test
    @DisplayName("should throw IOException when shell throws")
    void shouldThrowIOException_whenShellThrows() throws Exception {
      // GIVEN
      when(shell.exec(eq(container), anyString())).thenThrow(new RuntimeException("exec failed"));

      // WHEN / THEN
      assertThatThrownBy(() -> networkRedirect.removeRedirect(ctx, 6379, 16379))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to remove redirect");
    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> networkRedirect.removeRedirect(null, 6379, 16379))
          .withMessage("ctx must not be null");
    }
  }

  @Nested
  @DisplayName("Clear All Redirects")
  class ClearAllRedirectsTests {

    @Test
    @DisplayName("should clear all redirects successfully")
    void shouldClearAllRedirects_successfully() throws Exception {
      // GIVEN
      final ExecResult result = TestExecResults.of(0, "", "");
      when(shell.exec(eq(container), anyString())).thenReturn(result);

      // WHEN
      networkRedirect.clearAllRedirects(ctx);

      // THEN
      verify(shell).exec(eq(container), anyString());
    }

    @Test
    @DisplayName("should log warning but not throw when clear fails (rules may not exist)")
    void shouldNotThrow_whenClearFails() throws Exception {
      // GIVEN — non-zero exit is treated as warning, not error
      final ExecResult result = TestExecResults.of(1, "", "No chain/target/match by that name");
      when(shell.exec(eq(container), anyString())).thenReturn(result);

      // WHEN / THEN
      assertThatNoException().isThrownBy(() -> networkRedirect.clearAllRedirects(ctx));
    }

    @Test
    @DisplayName("should throw IOException when shell throws")
    void shouldThrowIOException_whenShellThrows() throws Exception {
      // GIVEN
      when(shell.exec(eq(container), anyString())).thenThrow(new RuntimeException("exec failed"));

      // WHEN / THEN
      assertThatThrownBy(() -> networkRedirect.clearAllRedirects(ctx))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("Failed to clear redirects");
    }

    @Test
    @DisplayName("should throw NullPointerException when ctx is null")
    void shouldThrowNpe_whenCtxIsNull() {
      assertThatNullPointerException()
          .isThrownBy(() -> networkRedirect.clearAllRedirects(null))
          .withMessage("ctx must not be null");
    }
  }
}
