/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link ContainerPidResolver}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerPidResolver")
class ContainerPidResolverTest {

  private GenericContainer<?> container;
  private HashMap<String, String> labels;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    container = mock(GenericContainer.class);
    labels = new HashMap<>();
    when(container.getLabels()).thenReturn(labels);
  }

  @Nested
  @DisplayName("cached label")
  class CachedLabel {

    @Test
    @DisplayName("returns cached PID from label — no exec called")
    void returnsCachedPid() throws Exception {
      labels.put(ContainerPidResolver.LABEL_MAIN_PID, "42");

      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(42);
      verify(container, never()).execInContainer(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sets label after first detection")
    void setsLabelAfterDetection() throws Exception {
      final ExecResult r = result(0, "redis-server\n");
      when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(r);

      ContainerPidResolver.resolve(container);

      verify(container).withLabel(ContainerPidResolver.LABEL_MAIN_PID, "1");
    }
  }

  @Nested
  @DisplayName("no init — PID 1 is the application")
  class NoInit {

    @Test
    @DisplayName("returns 1 for redis-server as PID 1")
    void returnsPid1ForApplication() throws Exception {
      final ExecResult r = result(0, "redis-server\n");
      when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(r);
      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(1);
    }

    @Test
    @DisplayName("returns 1 when comm read fails (non-zero exit)")
    void returnsPid1OnCommReadFailure() throws Exception {
      final ExecResult r = result(1, "");
      when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(r);
      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(1);
    }

    @Test
    @DisplayName("returns 1 when exec throws exception")
    void returnsPid1OnException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new java.io.IOException("exec failed"));

      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("with init — find first child")
  class WithInit {

    @Test
    @DisplayName("detects docker-init and returns first child PID")
    void detectsDockerInit() throws Exception {
      final ExecResult comm = result(0, "docker-init\n");
      final ExecResult child = result(0, "7\n");
      when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(comm);
      when(container.execInContainer(
              org.mockito.ArgumentMatchers.eq("/bin/sh"),
              org.mockito.ArgumentMatchers.eq("-c"),
              org.mockito.ArgumentMatchers.contains("PPid")))
          .thenReturn(child);
      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(7);
    }

    @Test
    @DisplayName("detects tini and returns first child PID")
    void detectsTini() throws Exception {
      final ExecResult comm = result(0, "tini\n");
      final ExecResult child = result(0, "5\n");
      when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(comm);
      when(container.execInContainer(
              org.mockito.ArgumentMatchers.eq("/bin/sh"),
              org.mockito.ArgumentMatchers.eq("-c"),
              org.mockito.ArgumentMatchers.contains("PPid")))
          .thenReturn(child);
      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(5);
    }

    @Test
    @DisplayName("falls back to PID 1 when child scan returns blank")
    void fallsBackWhenChildBlank() throws Exception {
      final ExecResult comm = result(0, "docker-init\n");
      final ExecResult blank = result(0, "   \n");
      when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(comm);
      when(container.execInContainer(
              org.mockito.ArgumentMatchers.eq("/bin/sh"),
              org.mockito.ArgumentMatchers.eq("-c"),
              org.mockito.ArgumentMatchers.contains("PPid")))
          .thenReturn(blank);
      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(1);
    }

    @Test
    @DisplayName("falls back to PID 1 when child scan fails")
    void fallsBackWhenChildScanFails() throws Exception {
      final ExecResult comm = result(0, "tini\n");
      final ExecResult fail = result(1, "");
      when(container.execInContainer("/bin/sh", "-c", "cat /proc/1/comm")).thenReturn(comm);
      when(container.execInContainer(
              org.mockito.ArgumentMatchers.eq("/bin/sh"),
              org.mockito.ArgumentMatchers.eq("-c"),
              org.mockito.ArgumentMatchers.contains("PPid")))
          .thenReturn(fail);
      assertThat(ContainerPidResolver.resolve(container)).isEqualTo(1);
    }
  }

  // ==================== helpers ====================

  private static ExecResult result(final int exitCode, final String stdout) {
    final ExecResult r = mock(ExecResult.class);
    when(r.getExitCode()).thenReturn(exitCode);
    when(r.getStdout()).thenReturn(stdout);
    when(r.getStderr()).thenReturn("");
    return r;
  }
}
