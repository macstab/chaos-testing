/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.cpu.command.StressNgCommandBuilder;

/**
 * Unit tests for {@link CpuObservability}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CpuObservability")
class CpuObservabilityTest {

  private StressNgCommandBuilder cmd;
  private GenericContainer<?> container;
  private CpuObservability observe;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    cmd = mock(StressNgCommandBuilder.class);
    container = mock(GenericContainer.class);
    observe = new CpuObservability(cmd);

    when(cmd.buildIsRunningByCommExactCommand(anyString())).thenReturn("check-exact");
    when(cmd.buildIsRunningByCommPrefixCommand(anyString())).thenReturn("check-prefix");
    when(cmd.buildGetAffinityMaskCommand(org.mockito.ArgumentMatchers.anyInt())).thenReturn("taskset");
    when(cmd.buildGetNiceValueCommand(org.mockito.ArgumentMatchers.anyInt())).thenReturn("awk nice");
    when(cmd.buildGetCoreCountCommand()).thenReturn("nproc");
    when(cmd.buildGetCoreCountFallbackCommand()).thenReturn("grep -c processor");
    when(cmd.buildReadCpuStatCommand()).thenReturn("cat /proc/stat");
  }

  // ==================== isThrottled ====================

  @Nested
  @DisplayName("isThrottled")
  class IsThrottled {

    @Test
    @DisplayName("returns true when cpulimit process found (exit 0)")
    void trueWhenFound() throws Exception {
      final ExecResult r = result(0, "");
      when(container.execInContainer("/bin/sh", "-c", "check-exact")).thenReturn(r);
      assertThat(observe.isThrottled(container)).isTrue();
    }

    @Test
    @DisplayName("returns false when cpulimit not found (exit 1)")
    void falseWhenNotFound() throws Exception {
      final ExecResult r = result(1, "");
      when(container.execInContainer("/bin/sh", "-c", "check-exact")).thenReturn(r);
      assertThat(observe.isThrottled(container)).isFalse();
    }

    @Test
    @DisplayName("returns false when exec throws")
    void falseOnException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("exec error"));
      assertThat(observe.isThrottled(container)).isFalse();
    }
  }

  // ==================== isStressed ====================

  @Nested
  @DisplayName("isStressed")
  class IsStressed {

    @Test
    @DisplayName("returns true when stress-ng found (exit 0)")
    void trueWhenFound() throws Exception {
      final ExecResult r = result(0, "");
      when(container.execInContainer("/bin/sh", "-c", "check-prefix")).thenReturn(r);
      assertThat(observe.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("returns false when stress-ng not found (exit 1)")
    void falseWhenNotFound() throws Exception {
      final ExecResult r = result(1, "");
      when(container.execInContainer("/bin/sh", "-c", "check-prefix")).thenReturn(r);
      assertThat(observe.isStressed(container)).isFalse();
    }

    @Test
    @DisplayName("returns false when exec throws")
    void falseOnException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("exec error"));
      assertThat(observe.isStressed(container)).isFalse();
    }
  }

  // ==================== getAvailableCores ====================

  @Nested
  @DisplayName("getAvailableCores")
  class GetAvailableCores {

    @Test
    @DisplayName("returns value from nproc")
    void returnsNprocValue() throws Exception {
      final ExecResult r = result(0, "8\n");
      when(container.execInContainer("/bin/sh", "-c", "nproc")).thenReturn(r);
      assertThat(observe.getAvailableCores(container)).isEqualTo(8);
    }

    @Test
    @DisplayName("falls back to /proc/cpuinfo when nproc fails")
    void fallsBackToCpuinfo() throws Exception {
      final ExecResult fail = result(1, "");
      final ExecResult ok = result(0, "4\n");
      when(container.execInContainer("/bin/sh", "-c", "nproc")).thenReturn(fail);
      when(container.execInContainer("/bin/sh", "-c", "grep -c processor")).thenReturn(ok);
      assertThat(observe.getAvailableCores(container)).isEqualTo(4);
    }

    @Test
    @DisplayName("throws when both nproc and fallback fail")
    void throwsWhenBothFail() throws Exception {
      final ExecResult fail = result(1, "");
      when(container.execInContainer("/bin/sh", "-c", "nproc")).thenReturn(fail);
      when(container.execInContainer("/bin/sh", "-c", "grep -c processor")).thenReturn(fail);
      assertThatThrownBy(() -> observe.getAvailableCores(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Could not determine CPU core count");
    }
  }

  // ==================== getAvailableCoresSilent ====================

  @Nested
  @DisplayName("getAvailableCoresSilent")
  class GetAvailableCoresSilent {

    @Test
    @DisplayName("returns core count on success")
    void returnsOnSuccess() throws Exception {
      final ExecResult r = result(0, "4\n");
      when(container.execInContainer("/bin/sh", "-c", "nproc")).thenReturn(r);
      assertThat(observe.getAvailableCoresSilent(container)).isEqualTo(4);
    }

    @Test
    @DisplayName("returns 1 on failure without throwing")
    void returnsOneOnFailure() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("exec failed"));
      assertThat(observe.getAvailableCoresSilent(container)).isEqualTo(1);
    }
  }

  // ==================== readAffinityMask ====================

  @Nested
  @DisplayName("readAffinityMask")
  class ReadAffinityMask {

    @Test
    @DisplayName("parses hex mask from taskset output")
    void parsesHexMask() throws Exception {
      final ExecResult r = result(0, "pid 1's current affinity mask: fff\n");
      when(container.execInContainer("/bin/sh", "-c", "taskset")).thenReturn(r);
      assertThat(observe.readAffinityMask(container, 1)).isEqualTo(0xfffL);
    }

    @Test
    @DisplayName("throws ChaosOperationFailedException on non-zero exit")
    void throwsOnNonZeroExit() throws Exception {
      final ExecResult r = mock(ExecResult.class);
      when(r.getExitCode()).thenReturn(1);
      when(r.getStdout()).thenReturn("");
      when(r.getStderr()).thenReturn("Permission denied");
      when(container.execInContainer("/bin/sh", "-c", "taskset")).thenReturn(r);
      assertThatThrownBy(() -> observe.readAffinityMask(container, 1))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("taskset");
    }

    @Test
    @DisplayName("throws ChaosOperationFailedException on exec exception")
    void throwsOnException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("exec failed"));
      assertThatThrownBy(() -> observe.readAffinityMask(container, 1))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to read affinity mask");
    }
  }

  // ==================== isAffinityPinned ====================

  @Nested
  @DisplayName("isAffinityPinned")
  class IsAffinityPinned {

    @Test
    @DisplayName("returns false when mask equals full mask (not pinned)")
    void falseWhenFullMask() throws Exception {
      // 1 core system, full mask = 0x1, current mask = 0x1 -> not pinned
      final ExecResult tsk = result(0, "pid 1's current affinity mask: 1\n");
      final ExecResult np = result(0, "1\n");
      when(container.execInContainer("/bin/sh", "-c", "taskset")).thenReturn(tsk);
      when(container.execInContainer("/bin/sh", "-c", "nproc")).thenReturn(np);
      assertThat(observe.isAffinityPinned(container, 1)).isFalse();
    }

    @Test
    @DisplayName("returns true when mask differs from full mask (pinned)")
    void trueWhenPinned() throws Exception {
      // 4 core system, full mask = 0xf, current = 0x1 -> pinned
      final ExecResult tsk = result(0, "pid 1's current affinity mask: 1\n");
      final ExecResult np = result(0, "4\n");
      when(container.execInContainer("/bin/sh", "-c", "taskset")).thenReturn(tsk);
      when(container.execInContainer("/bin/sh", "-c", "nproc")).thenReturn(np);
      assertThat(observe.isAffinityPinned(container, 1)).isTrue();
    }

    @Test
    @DisplayName("returns false when exception thrown")
    void falseOnException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new RuntimeException("exec failed"));
      assertThat(observe.isAffinityPinned(container, 1)).isFalse();
    }
  }

  // ==================== getNiceValue ====================

  @Nested
  @DisplayName("getNiceValue")
  class GetNiceValue {

    @Test
    @DisplayName("returns parsed nice value")
    void returnsParsedValue() throws Exception {
      final ExecResult r = result(0, "10\n");
      when(container.execInContainer("/bin/sh", "-c", "awk nice")).thenReturn(r);
      assertThat(observe.getNiceValue(container, 1)).isEqualTo(10);
    }

    @Test
    @DisplayName("throws on blank output")
    void throwsOnBlankOutput() throws Exception {
      final ExecResult r = result(0, "  \n");
      when(container.execInContainer("/bin/sh", "-c", "awk nice")).thenReturn(r);
      assertThatThrownBy(() -> observe.getNiceValue(container, 1))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Failed to read nice value");
    }

    @Test
    @DisplayName("throws on non-zero exit")
    void throwsOnNonZeroExit() throws Exception {
      final ExecResult r = result(1, "");
      when(container.execInContainer("/bin/sh", "-c", "awk nice")).thenReturn(r);
      assertThatThrownBy(() -> observe.getNiceValue(container, 1))
          .isInstanceOf(ChaosOperationFailedException.class);
    }
  }

  // ==================== Helper ====================

  /**
   * Creates a pre-built ExecResult mock. Must NOT be called inside a when() chain
   * because Mockito treats mock creation as unfinished stubbing.
   */
  private static ExecResult result(final int exitCode, final String stdout) {
    final ExecResult r = mock(ExecResult.class);
    when(r.getExitCode()).thenReturn(exitCode);
    when(r.getStdout()).thenReturn(stdout != null ? stdout : "");
    when(r.getStderr()).thenReturn("");
    return r;
  }
}
