/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.lifecycle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.chaos.core.command.http.HttpCommandBuilder;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.shell.Shell;
import com.macstab.chaos.toxiproxy.context.ContainerContext;
import com.macstab.chaos.toxiproxy.support.TestExecResults;

/**
 * Unit tests for {@link ToxiproxyInstaller} failure branches.
 *
 * <p>Uses a real running container for the dependency-installation step (which calls
 * {@code execInContainer} directly) while injecting a mock {@link Shell} to control the
 * shell-dispatched commands ({@code which}, download, chmod) that {@link ToxiproxyInstaller}
 * routes through {@link ContainerContext#shell()}.
 *
 * <p>Covered branches:
 * <ul>
 *   <li>{@code isAlreadyInstalled} — exception catch path (shell.exec throws → returns false)
 *   <li>{@code downloadBinary} — non-zero exit code → {@link ChaosOperationFailedException}
 *   <li>{@code makeExecutable} — non-zero exit code → {@link ChaosOperationFailedException}
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("ToxiproxyInstaller — unit (failure paths)")
class ToxiproxyInstallerUnitTest {

  @Container
  private static final GenericContainer<?> UBUNTU =
      new GenericContainer<>("ubuntu:22.04").withCommand("sleep", "infinity");

  private final ToxiproxyInstaller installer = new ToxiproxyInstaller();

  /**
   * Build a {@link ContainerContext} backed by the real Ubuntu container but with a mock Shell and
   * a real Platform. {@code installDependencies} runs against the real container (via
   * {@code execInContainer}). {@code isAlreadyInstalled}, {@code downloadBinary}, and
   * {@code makeExecutable} all route through the mock shell, giving us full control.
   */
  private ContainerContext ctxWithMockShell(final Shell mockShell) {
    final Platform realPlatform = PlatformDetector.detect(UBUNTU);
    return ContainerContext.of(UBUNTU, realPlatform, mockShell);
  }

  /**
   * Build a {@link ContainerContext} backed by a mock {@link Platform} whose
   * {@link HttpCommandBuilder} is also mocked. This avoids any real HTTP builder interaction
   * while keeping the real container for the dependency-install step.
   */
  private ContainerContext ctxWithMockPlatformAndShell(
      final Platform mockPlatform, final Shell mockShell) {
    return ContainerContext.of(UBUNTU, mockPlatform, mockShell);
  }

  // ==================== downloadBinary failure ====================

  @Nested
  @DisplayName("downloadBinary()")
  class DownloadBinaryTests {

    @Test
    @DisplayName("non-zero download exit code throws ChaosOperationFailedException")
    void nonZeroDownloadExit_throws() throws Exception {
      final Shell mockShell = mock(Shell.class);
      final ContainerContext ctx = ctxWithMockShell(mockShell);

      // Pre-create mocks BEFORE the when() — avoids nested mock creation during stubbing
      final var notFound = TestExecResults.failure("toxiproxy-server: not found");
      final var downloadFail = TestExecResults.failure("curl: (6) Could not resolve host");

      when(mockShell.exec(any(), anyString()))
          .thenReturn(notFound)
          .thenReturn(downloadFail);

      assertThatThrownBy(() -> installer.install(ctx))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to download Toxiproxy");
    }

    @Test
    @DisplayName("shell.exec throws during download — wraps in ChaosOperationFailedException")
    void shellThrowsDuringDownload_wraps() throws Exception {
      final Shell mockShell = mock(Shell.class);
      final ContainerContext ctx = ctxWithMockShell(mockShell);

      // 1st call — isAlreadyInstalled: exception in shell, catch returns false → proceed
      // 2nd call — downloadBinary: shell throws unexpectedly
      when(mockShell.exec(any(), anyString()))
          .thenThrow(new RuntimeException("container exec timeout"))
          .thenThrow(new RuntimeException("container exec timeout during download"));

      assertThatThrownBy(() -> installer.install(ctx))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to download Toxiproxy");
    }
  }

  // ==================== makeExecutable failure ====================

  @Nested
  @DisplayName("makeExecutable()")
  class MakeExecutableTests {

    @Test
    @DisplayName("non-zero chmod exit code throws ChaosOperationFailedException")
    void nonZeroChmodExit_throws() throws Exception {
      final Shell mockShell = mock(Shell.class);
      final ContainerContext ctx = ctxWithMockShell(mockShell);

      final var notFound = TestExecResults.failure("toxiproxy-server: not found");
      final var downloadOk = TestExecResults.success();
      final var chmodFail = TestExecResults.failure("chmod: cannot access '/usr/local/bin/toxiproxy-server'");

      when(mockShell.exec(any(), anyString()))
          .thenReturn(notFound)
          .thenReturn(downloadOk)
          .thenReturn(chmodFail);

      assertThatThrownBy(() -> installer.install(ctx))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to chmod Toxiproxy binary");
    }

    @Test
    @DisplayName("shell.exec throws during chmod — wraps in ChaosOperationFailedException")
    void shellThrowsDuringChmod_wraps() throws Exception {
      final Shell mockShell = mock(Shell.class);
      final ContainerContext ctx = ctxWithMockShell(mockShell);

      final var notFound = TestExecResults.failure("toxiproxy-server: not found");
      final var downloadOk = TestExecResults.success();

      when(mockShell.exec(any(), anyString()))
          .thenReturn(notFound)
          .thenReturn(downloadOk)
          .thenThrow(new RuntimeException("chmod exec failed"));

      assertThatThrownBy(() -> installer.install(ctx))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to make Toxiproxy executable");
    }
  }

  // ==================== isAlreadyInstalled exception path ====================

  @Nested
  @DisplayName("isAlreadyInstalled() — exception catch")
  class IsAlreadyInstalledTests {

    @Test
    @DisplayName("shell.exec throws — caught, install proceeds (download failure proves install path was reached)")
    void shellExecThrows_catchReturnsFalse_installProceeds() throws Exception {
      final Shell mockShell = mock(Shell.class);
      final ContainerContext ctx = ctxWithMockShell(mockShell);

      // 1st call — isAlreadyInstalled throws → catch returns false → install proceeds
      // 2nd call — downloadBinary: controlled failure to stop the install fast
      when(mockShell.exec(any(), anyString()))
          .thenThrow(new RuntimeException("shell not available"))
          .thenReturn(TestExecResults.failure("curl failed"));

      // The exception catch in isAlreadyInstalled is hit (returns false).
      // install() then proceeds into installDependencies → downloadBinary (which fails).
      assertThatThrownBy(() -> installer.install(ctx))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to download Toxiproxy");
    }
  }
}
