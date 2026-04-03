/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link ContainerArchitecture}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerArchitecture")
class ContainerArchitectureTest {

  @Nested
  @DisplayName("constants")
  class Constants {

    @Test
    @DisplayName("ARM64 has binaryName=arm64 and unameAlias=aarch64")
    void arm64Constants() {
      assertThat(ContainerArchitecture.ARM64.getBinaryName()).isEqualTo("arm64");
      assertThat(ContainerArchitecture.ARM64.getUnameAlias()).isEqualTo("aarch64");
    }

    @Test
    @DisplayName("AMD64 has binaryName=amd64 and unameAlias=x86_64")
    void amd64Constants() {
      assertThat(ContainerArchitecture.AMD64.getBinaryName()).isEqualTo("amd64");
      assertThat(ContainerArchitecture.AMD64.getUnameAlias()).isEqualTo("x86_64");
    }
  }

  @Nested
  @DisplayName("detect()")
  class Detect {

    @Test
    @DisplayName("detects ARM64 from aarch64 uname output")
    void detectsArm64FromAarch64() throws Exception {
      final GenericContainer<?> container = mockUname("aarch64");
      assertThat(ContainerArchitecture.detect(container)).isEqualTo(ContainerArchitecture.ARM64);
    }

    @Test
    @DisplayName("detects ARM64 from arm64 uname output")
    void detectsArm64FromArm64() throws Exception {
      final GenericContainer<?> container = mockUname("arm64");
      assertThat(ContainerArchitecture.detect(container)).isEqualTo(ContainerArchitecture.ARM64);
    }

    @Test
    @DisplayName("detects AMD64 from x86_64 uname output")
    void detectsAmd64FromX86_64() throws Exception {
      final GenericContainer<?> container = mockUname("x86_64");
      assertThat(ContainerArchitecture.detect(container)).isEqualTo(ContainerArchitecture.AMD64);
    }

    @Test
    @DisplayName("detects AMD64 from amd64 uname output")
    void detectsAmd64FromAmd64() throws Exception {
      final GenericContainer<?> container = mockUname("amd64");
      assertThat(ContainerArchitecture.detect(container)).isEqualTo(ContainerArchitecture.AMD64);
    }

    @Test
    @DisplayName("throws IllegalStateException for unknown architecture")
    void throwsForUnknownArch() throws Exception {
      final GenericContainer<?> container = mockUname("riscv64");
      assertThatThrownBy(() -> ContainerArchitecture.detect(container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Unsupported architecture");
    }

    @Test
    @DisplayName("throws IllegalStateException when uname exits non-zero")
    void throwsWhenUnameFailsWithNonZeroExit() throws Exception {
      final GenericContainer<?> container = mock(GenericContainer.class);
      final ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(1);
      when(result.getStdout()).thenReturn("");
      when(container.execInContainer("uname", "-m")).thenReturn(result);
      assertThatThrownBy(() -> ContainerArchitecture.detect(container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("uname -m exited with code");
    }

    @Test
    @DisplayName("throws IllegalStateException when execInContainer throws IOException")
    void throwsWhenExecThrows() throws Exception {
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.execInContainer("uname", "-m")).thenThrow(new IOException("exec failed"));
      assertThatThrownBy(() -> ContainerArchitecture.detect(container))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Failed to detect container architecture");
    }

    @Test
    @DisplayName("throws NullPointerException for null container")
    void throwsForNullContainer() {
      assertThatThrownBy(() -> ContainerArchitecture.detect(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static GenericContainer<?> mockUname(final String output) throws Exception {
      final GenericContainer<?> container = mock(GenericContainer.class);
      final ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(result.getStdout()).thenReturn(output + "\n");
      when(container.execInContainer("uname", "-m")).thenReturn(result);
      return container;
    }
  }
}
