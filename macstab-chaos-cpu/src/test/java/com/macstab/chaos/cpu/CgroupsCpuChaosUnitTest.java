/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.cpu.command.StressNgCommandBuilder;

/**
 * Unit tests for {@link CgroupsCpuChaos} — no Docker required.
 *
 * <p>Uses the package-private testability constructor to inject mocked collaborators.
 * Covers all exception paths, lifecycle branches, and the init-aware PID resolution.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsCpuChaos (unit)")
class CgroupsCpuChaosUnitTest {

  private StressNgCommandBuilder cmd;
  private CpuObservability observe;
  private GenericContainer<?> container;
  private CgroupsCpuChaos chaos;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    cmd = mock(StressNgCommandBuilder.class);
    observe = mock(CpuObservability.class);
    container = mock(GenericContainer.class);

    when(container.isRunning()).thenReturn(true);
    when(container.getLabels()).thenReturn(new HashMap<>());
    when(container.getContainerId()).thenReturn("test-container-id");

    // Default: all command builders return simple strings
    when(cmd.buildThrottleCommand(anyInt(), anyInt())).thenReturn("cpulimit");
    when(cmd.buildThrottleWithDurationCommand(anyInt(), anyInt(), anyLong())).thenReturn("cpulimit-d");
    when(cmd.buildStressCpuCommand(anyInt())).thenReturn("stress-ng --cpu 1");
    when(cmd.buildStressCpuWithTimeoutCommand(anyInt(), anyLong())).thenReturn("stress-ng --cpu 1 -t 10s");
    when(cmd.buildStressCacheCommand(anyInt())).thenReturn("stress-ng --cache 1");
    when(cmd.buildStressCacheWithTimeoutCommand(anyInt(), anyLong())).thenReturn("stress-ng --cache 1 -t 10s");
    when(cmd.buildStressCacheLineCommand(anyInt())).thenReturn("stress-ng --cacheline 1");
    when(cmd.buildStressContextSwitchCommand(anyInt())).thenReturn("stress-ng --context 1");
    when(cmd.buildStressThreadSwitchCommand(anyInt())).thenReturn("stress-ng --switch 1");
    when(cmd.buildStressBranchPredictorCommand(anyInt())).thenReturn("stress-ng --branch 1");
    when(cmd.buildStressTimerInterruptsCommand(anyInt())).thenReturn("stress-ng --hrtimers 1");
    when(cmd.buildStressMatrixCommand(anyInt())).thenReturn("stress-ng --matrix 1");
    when(cmd.buildStressMatrixWithTimeoutCommand(anyInt(), anyLong())).thenReturn("stress-ng --matrix 1 -t 10s");
    when(cmd.buildPinToMaskCommand(anyInt(), anyLong())).thenReturn("taskset");
    when(cmd.buildSetNiceValueCommand(anyInt(), anyInt())).thenReturn("renice");
    when(cmd.buildKillAllByCommSigKillCommand(anyString())).thenReturn("kill cpulimit");
    when(cmd.buildKillAllByCommPrefixSigKillCommand(anyString())).thenReturn("kill stress-ng");
    when(cmd.buildIsRunningByCommExactCommand(anyString())).thenReturn("check-exact");
    when(cmd.buildIsRunningByCommPrefixCommand(anyString())).thenReturn("check-prefix");
    when(cmd.buildFindLowestPidByCommCommand(anyString())).thenReturn("find-pid");
    when(cmd.buildIsRunningByCommPrefixCommand(anyString())).thenReturn("check-prefix");
    when(cmd.buildGetAffinityMaskCommand(anyInt())).thenReturn("taskset -p 1");
    when(cmd.buildGetNiceValueCommand(anyInt())).thenReturn("awk nice");
    when(cmd.buildGetCoreCountCommand()).thenReturn("nproc");
    when(cmd.buildReadCpuStatCommand()).thenReturn("cat /proc/stat");

    // Default: all execs succeed (must be first — specific stubs override)
    mockExecSuccess();

    // PlatformDetector: Debian via /etc/os-release
    final ExecResult osRelease = mock(ExecResult.class);
    when(osRelease.getExitCode()).thenReturn(0);
    when(osRelease.getStdout()).thenReturn("ID=debian\nVERSION_ID=\"12\"\n");
    when(container.execInContainer("cat", "/etc/os-release")).thenReturn(osRelease);

    // ShellDetector: bash not available, sh is (non-BusyBox)
    final ExecResult whichBashFail = mock(ExecResult.class);
    when(whichBashFail.getExitCode()).thenReturn(1);
    when(container.execInContainer("which", "/bin/bash")).thenReturn(whichBashFail);
    final ExecResult whichShOk = mock(ExecResult.class);
    when(whichShOk.getExitCode()).thenReturn(0);
    when(container.execInContainer("which", "/bin/sh")).thenReturn(whichShOk);
    final ExecResult shHelp = mock(ExecResult.class);
    when(shHelp.getExitCode()).thenReturn(0);
    when(shHelp.getStdout()).thenReturn("");
    when(shHelp.getStderr()).thenReturn("");
    when(container.execInContainer("/bin/sh", "--help")).thenReturn(shHelp);

    chaos = new CgroupsCpuChaos(cmd, observe);
  }

  // ==================== isSupported ====================

  @Nested
  @DisplayName("isSupported")
  class IsSupported {

    @Test
    @DisplayName("always returns true")
    void returnsTrue() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }

  // ==================== resolveMainPid ====================

  @Nested
  @DisplayName("resolveMainPid")
  class ResolveMainPid {

    @Test
    @DisplayName("uses cached label when present")
    void usesCachedLabel() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        // GIVEN — label already set to PID 42
        final var labels = new HashMap<String, String>();
        labels.put("macstab.chaos.pid.main", "42");
        when(container.getLabels()).thenReturn(labels);

        // WHEN — throttle triggers resolveMainPid
        chaos.throttle(container, 50);

        // THEN — throttle command built with PID 42, not 1
        verify(cmd).buildThrottleCommand(42, 50);
      }
    }

    @Test
    @DisplayName("detects docker-init as PID 1 and resolves first child")
    void detectsDockerInitAndResolvesChild() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        // GIVEN — /proc/1/comm = "docker-init", PPid scan returns 7
        final ExecResult commResult = mockExecResult(0, "docker-init\n");
        final ExecResult childResult = mockExecResult(0, "7\n");
        when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(commResult);
        when(container.execInContainer(org.mockito.ArgumentMatchers.eq("/bin/sh"),
            org.mockito.ArgumentMatchers.eq("-c"),
            org.mockito.ArgumentMatchers.contains("PPid"))).thenReturn(childResult);

        chaos.throttle(container, 50);

        verify(cmd).buildThrottleCommand(7, 50);
      }
    }

    @Test
    @DisplayName("uses PID 1 when /proc/1/comm is a normal process (not init)")
    void usesPid1ForNormalProcess() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        final ExecResult commResult = mockExecResult(0, "redis-server\n");
        when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(commResult);

        chaos.throttle(container, 50);

        verify(cmd).buildThrottleCommand(1, 50);
      }
    }

    @Test
    @DisplayName("falls back to PID 1 when comm exec fails")
    void fallsBackToPid1OnCommFail() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        final ExecResult fail = mockExecResult(1, "");
        when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(fail);

        chaos.throttle(container, 50);

        verify(cmd).buildThrottleCommand(1, 50);
      }
    }

    @Test
    @DisplayName("falls back to PID 1 when children scan returns blank")
    void fallsBackWhenChildrenBlank() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        final ExecResult commResult = mockExecResult(0, "docker-init\n");
        final ExecResult blank = mockExecResult(0, "   \n");
        when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(commResult);
        when(container.execInContainer(org.mockito.ArgumentMatchers.eq("/bin/sh"),
            org.mockito.ArgumentMatchers.eq("-c"),
            org.mockito.ArgumentMatchers.contains("PPid"))).thenReturn(blank);

        chaos.throttle(container, 50);

        verify(cmd).buildThrottleCommand(1, 50);
      }
    }
  }

  // ==================== Null/validation guards ====================

  @Nested
  @DisplayName("null and validation guards")
  class Guards {

    @Test
    @DisplayName("throttle — null container throws NPE")
    void throttleNullContainer() {
      assertThatThrownBy(() -> chaos.throttle(null, 50))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("throttle — stopped container throws IllegalStateException")
    void throttleStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.throttle(container, 50))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("stress — null container throws NPE")
    void stressNullContainer() {
      assertThatThrownBy(() -> chaos.stress(null, 1))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("stress — 0 workers throws ChaosConfigurationException")
    void stressZeroWorkers() {
      when(cmd.buildStressCpuCommand(0)).thenThrow(new ChaosConfigurationException("workers must be >= 1"));
      assertThatThrownBy(() -> chaos.stress(container, 0))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("throttle(duration) — null duration throws NPE")
    void throttleDurationNull() {
      assertThatThrownBy(() -> chaos.throttle(container, 50, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("throttle(duration) — zero duration throws ChaosConfigurationException")
    void throttleZeroDuration() {
      assertThatThrownBy(() -> chaos.throttle(container, 50, Duration.ZERO))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("duration must be > 0");
    }

    @Test
    @DisplayName("pinToCoreMask — zero mask throws ChaosConfigurationException")
    void pinZeroMask() {
      assertThatThrownBy(() -> chaos.pinToCoreMask(container, 0L))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("pinToCoreMask — negative mask throws ChaosConfigurationException")
    void pinNegativeMask() {
      assertThatThrownBy(() -> chaos.pinToCoreMask(container, -1L))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("degradePriority — nice -1 throws ChaosConfigurationException")
    void degradeNiceMinus1() {
      assertThatThrownBy(() -> chaos.degradePriority(container, -1))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("degradePriority — nice 20 throws ChaosConfigurationException")
    void degradeNice20() {
      assertThatThrownBy(() -> chaos.degradePriority(container, 20))
          .isInstanceOf(ChaosConfigurationException.class);
    }
  }

  // ==================== Observability delegation ====================

  @Nested
  @DisplayName("observability delegation")
  class ObservabilityDelegation {

    @Test
    @DisplayName("isStressed delegates to observe")
    void isStressedDelegates() {
      when(observe.isStressed(container)).thenReturn(true);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("isStressed returns false when container stopped")
    void isStressedFalseWhenStopped() {
      when(container.isRunning()).thenReturn(false);
      assertThat(chaos.isStressed(container)).isFalse();
    }

    @Test
    @DisplayName("isThrottled delegates to observe")
    void isThrottledDelegates() {
      when(observe.isThrottled(container)).thenReturn(true);
      assertThat(chaos.isThrottled(container)).isTrue();
    }

    @Test
    @DisplayName("isAffinityPinned delegates to observe")
    void isAffinityPinnedDelegates() throws Exception {
      when(observe.isAffinityPinned(container, 1)).thenReturn(true);
      assertThat(chaos.isAffinityPinned(container)).isTrue();
    }

    @Test
    @DisplayName("isAffinityPinned returns false when container stopped")
    void isAffinityPinnedFalseWhenStopped() {
      when(container.isRunning()).thenReturn(false);
      assertThat(chaos.isAffinityPinned(container)).isFalse();
    }

    @Test
    @DisplayName("getCurrentUsage delegates to observe")
    void getCurrentUsageDelegates() throws Exception {
      when(observe.getCurrentUsage(container)).thenReturn(75);
      assertThat(chaos.getCurrentUsage(container)).isEqualTo(75);
    }

    @Test
    @DisplayName("getCurrentUsage wraps exception as ChaosOperationFailedException")
    void getCurrentUsageWrapsException() throws Exception {
      when(observe.getCurrentUsage(container)).thenThrow(new RuntimeException("read failed"));
      assertThatThrownBy(() -> chaos.getCurrentUsage(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to read CPU usage");
    }

    @Test
    @DisplayName("getAvailableCores delegates to observe")
    void getAvailableCoresDelegates() throws Exception {
      when(observe.getAvailableCores(container)).thenReturn(8);
      assertThat(chaos.getAvailableCores(container)).isEqualTo(8);
    }

    @Test
    @DisplayName("getAvailableCores wraps exception")
    void getAvailableCoresWrapsException() throws Exception {
      when(observe.getAvailableCores(container)).thenThrow(new RuntimeException("no cores"));
      assertThatThrownBy(() -> chaos.getAvailableCores(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to get available cores");
    }

    @Test
    @DisplayName("getPinnedCoreMask delegates to observe")
    void getPinnedCoreMaskDelegates() {
      when(observe.readAffinityMask(container, 1)).thenReturn(0xfL);
      assertThat(chaos.getPinnedCoreMask(container)).isEqualTo(0xfL);
    }

    @Test
    @DisplayName("getNiceValue delegates to observe")
    void getNiceValueDelegates() throws Exception {
      when(observe.getNiceValue(container, 1)).thenReturn(10);
      assertThat(chaos.getNiceValue(container)).isEqualTo(10);
    }

    @Test
    @DisplayName("getNiceValue wraps exception")
    void getNiceValueWrapsException() throws Exception {
      when(observe.getNiceValue(container, 1)).thenThrow(new RuntimeException("stat failed"));
      assertThatThrownBy(() -> chaos.getNiceValue(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to read nice value");
    }
  }

  // ==================== reset ====================

  @Nested
  @DisplayName("reset")
  class Reset {

    @Test
    @DisplayName("reset on stopped container is a no-op")
    void resetStoppedNoOp() {
      when(container.isRunning()).thenReturn(false);
      assertThatCode(() -> chaos.reset(container)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("resetPriority on stopped container is a no-op")
    void resetPriorityStoppedNoOp() {
      when(container.isRunning()).thenReturn(false);
      assertThatCode(() -> chaos.resetPriority(container)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reset null container throws NPE")
    void resetNullContainer() {
      assertThatThrownBy(() -> chaos.reset(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== exec failure ====================

  @Nested
  @DisplayName("exec failure handling")
  class ExecFailure {

    @Test
    @DisplayName("exec non-zero exit code wraps as ChaosOperationFailedException")
    void execNonZeroWrapped() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        final ExecResult failResult = mock(ExecResult.class);
        when(failResult.getExitCode()).thenReturn(1);
        when(failResult.getStdout()).thenReturn("");
        when(failResult.getStderr()).thenReturn("command not found");
        when(container.execInContainer(anyString(), anyString(), anyString())).thenReturn(failResult);

        assertThatThrownBy(() -> chaos.stress(container, 1))
            .isInstanceOf(ChaosOperationFailedException.class);
      }
    }

    @Test
    @DisplayName("exec IOException wraps as ChaosOperationFailedException")
    void execIoExceptionWrapped() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        when(container.execInContainer(anyString(), anyString(), anyString()))
            .thenThrow(new IOException("Docker exec failed"));

        assertThatThrownBy(() -> chaos.stress(container, 1))
            .isInstanceOf(ChaosOperationFailedException.class);
      }
    }
  }

  // ==================== stressWithThrottle ====================

  @Nested
  @DisplayName("stressWithThrottle")
  class StressWithThrottle {

    @Test
    @DisplayName("rejects 0 workers before container side-effects")
    void rejectsZeroWorkers() {
      when(cmd.buildStressCpuCommand(0)).thenThrow(new ChaosConfigurationException("workers"));
      assertThatThrownBy(() -> chaos.stressWithThrottle(container, 0, 50))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("rejects 0 percentage before container side-effects")
    void rejectsZeroPercentage() {
      when(cmd.buildThrottleCommand(anyInt(), org.mockito.ArgumentMatchers.eq(0)))
          .thenThrow(new ChaosConfigurationException("percentage"));
      assertThatThrownBy(() -> chaos.stressWithThrottle(container, 1, 0))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("throws when stress-ng PID not found after start")
    void throwsWhenStressNgPidNotFound() throws Exception {
      try (MockedStatic<PackageInstaller> pi = org.mockito.Mockito.mockStatic(PackageInstaller.class)) {
        final ExecResult pidResult = mockExecResult(1, "");
        when(container.execInContainer("/bin/sh", "-c", "find-pid")).thenReturn(pidResult);

        assertThatThrownBy(() -> chaos.stressWithThrottle(container, 1, 50))
            .isInstanceOf(ChaosOperationFailedException.class)
            .hasMessageContaining("stress-ng did not start");
      }
    }
  }

  // ==================== Helpers ====================

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockExecSuccess() throws Exception {
    final ExecResult successResult = mockExecResult(0, "");
    // Cover 3-arg (sh -c <cmd>) and 2-arg (cat /etc/os-release) calls
    when(container.execInContainer(anyString(), anyString(), anyString())).thenReturn(successResult);
    when(container.execInContainer(anyString(), anyString())).thenReturn(successResult);
    // Cover varargs form used by PackageManager detection
    when(container.execInContainer(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.<String>any())).thenReturn(successResult);
  }

  private static ExecResult mockExecResult(final int exitCode, final String stdout) {
    final ExecResult result = mock(ExecResult.class);
    when(result.getExitCode()).thenReturn(exitCode);
    when(result.getStdout()).thenReturn(stdout);
    when(result.getStderr()).thenReturn("");
    return result;
  }

  private static int anyInt() {
    return org.mockito.ArgumentMatchers.anyInt();
  }

  private static long anyLong() {
    return org.mockito.ArgumentMatchers.anyLong();
  }
}
