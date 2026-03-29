/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform.linux;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.*;

/** Comprehensive tests for {@link AbstractLinuxPlatform} base class. */
@DisplayName("AbstractLinuxPlatform - Comprehensive Coverage")
class AbstractLinuxPlatformComprehensiveTest {

  static class TestLinuxPlatform extends AbstractLinuxPlatform {
    @Override
    public String getDistribution() {
      return "test-linux";
    }
  }

  /** Platform whose overrides shadow all tools with null — forces UnsupportedOperationException. */
  static class NoMappingPlatform extends AbstractLinuxPlatform {
    @Override
    public String getDistribution() {
      return "no-mapping";
    }

    @Override
    protected java.util.Map<com.macstab.chaos.core.platform.Tool, ToolMapping> getToolOverrides() {
      // HashMap allows null values; getOrDefault returns null when key is present with null value
      java.util.Map<com.macstab.chaos.core.platform.Tool, ToolMapping> map =
          new java.util.HashMap<>();
      for (com.macstab.chaos.core.platform.Tool t : com.macstab.chaos.core.platform.Tool.values()) {
        map.put(t, null);
      }
      return map;
    }
  }

  @Nested
  @DisplayName("getType()")
  class GetTypeTest {
    @Test
    @DisplayName("Should return LINUX")
    void shouldReturnLinux() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getType()).isEqualTo(PlatformType.LINUX);
    }
  }

  @Nested
  @DisplayName("getRequiredTools()")
  class GetRequiredToolsTest {
    @Test
    @DisplayName("Should return curl and iptables")
    void shouldReturnCurlAndIptables() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getRequiredTools()).containsExactly("curl", "iptables");
    }
  }

  @Nested
  @DisplayName("validatePrerequisites()")
  class ValidatePrerequisitesTest {

    @Test
    @DisplayName("Should throw when container is null")
    void shouldThrowWhenContainerNull() {
      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.validatePrerequisites(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container must not be null");
    }

    @Test
    @DisplayName("Should pass when all tools present")
    void shouldPassWhenAllToolsPresent() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(container.execInContainer("which", "curl")).thenReturn(result);
      when(container.execInContainer("which", "iptables")).thenReturn(result);

      Platform platform = new TestLinuxPlatform();

      assertThatCode(() -> platform.validatePrerequisites(container)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should throw when curl missing")
    void shouldThrowWhenCurlMissing() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult curlMissing = mock(ExecResult.class);
      when(curlMissing.getExitCode()).thenReturn(1);

      ExecResult iptablesPresent = mock(ExecResult.class);
      when(iptablesPresent.getExitCode()).thenReturn(0);

      when(container.execInContainer("which", "curl")).thenReturn(curlMissing);
      when(container.execInContainer("which", "iptables")).thenReturn(iptablesPresent);

      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.validatePrerequisites(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Missing required tools: curl");
    }

    @Test
    @DisplayName("Should throw when iptables missing")
    void shouldThrowWhenIptablesMissing() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult curlPresent = mock(ExecResult.class);
      when(curlPresent.getExitCode()).thenReturn(0);

      ExecResult iptablesMissing = mock(ExecResult.class);
      when(iptablesMissing.getExitCode()).thenReturn(1);

      when(container.execInContainer("which", "curl")).thenReturn(curlPresent);
      when(container.execInContainer("which", "iptables")).thenReturn(iptablesMissing);

      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.validatePrerequisites(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Missing required tools: iptables");
    }

    @Test
    @DisplayName("Should throw when both tools missing")
    void shouldThrowWhenBothToolsMissing() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult missing = mock(ExecResult.class);
      when(missing.getExitCode()).thenReturn(1);
      when(container.execInContainer("which", "curl")).thenReturn(missing);
      when(container.execInContainer("which", "iptables")).thenReturn(missing);

      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.validatePrerequisites(container))
          .isInstanceOf(ChaosOperationFailedException.class)
          .hasMessageContaining("Missing required tools")
          .hasMessageContaining("curl")
          .hasMessageContaining("iptables");
    }
  }

  @Nested
  @DisplayName("hasCommand()")
  class HasCommandTest {

    @Test
    @DisplayName("Should throw when container is null")
    void shouldThrowWhenContainerNull() {
      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.hasCommand(null, "curl"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container must not be null");
    }

    @Test
    @DisplayName("Should throw when command is null")
    void shouldThrowWhenCommandNull() {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.hasCommand(container, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("command must not be null");
    }

    @Test
    @DisplayName("Should return false when container not running")
    void shouldReturnFalseWhenContainerNotRunning() {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(false);

      Platform platform = new TestLinuxPlatform();
      boolean result = platform.hasCommand(container, "curl");

      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return true when command exists")
    void shouldReturnTrueWhenCommandExists() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(0);
      when(container.execInContainer("which", "curl")).thenReturn(result);

      Platform platform = new TestLinuxPlatform();
      boolean has = platform.hasCommand(container, "curl");

      assertThat(has).isTrue();
    }

    @Test
    @DisplayName("Should return false when command not found")
    void shouldReturnFalseWhenCommandNotFound() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);

      ExecResult result = mock(ExecResult.class);
      when(result.getExitCode()).thenReturn(1);
      when(container.execInContainer("which", "missing-cmd")).thenReturn(result);

      Platform platform = new TestLinuxPlatform();
      boolean has = platform.hasCommand(container, "missing-cmd");

      assertThat(has).isFalse();
    }

    @Test
    @DisplayName("Should return false when exception occurs")
    void shouldReturnFalseWhenExceptionOccurs() throws Exception {
      @SuppressWarnings("resource")
      GenericContainer<?> container = mock(GenericContainer.class);
      when(container.isRunning()).thenReturn(true);
      when(container.execInContainer("which", "curl"))
          .thenThrow(new RuntimeException("Test exception"));

      Platform platform = new TestLinuxPlatform();
      boolean has = platform.hasCommand(container, "curl");

      assertThat(has).isFalse();
    }
  }

  @Nested
  @DisplayName("supportsCapability()")
  class SupportsCapabilityTest {

    @Test
    @DisplayName("Should throw when capability is null")
    void shouldThrowWhenCapabilityNull() {
      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.supportsCapability(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("capability must not be null");
    }

    @Test
    @DisplayName("Should support NET_ADMIN")
    void shouldSupportNetAdmin() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.supportsCapability("NET_ADMIN")).isTrue();
    }

    @Test
    @DisplayName("Should support /proc")
    void shouldSupportProc() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.supportsCapability("/proc")).isTrue();
    }

    @Test
    @DisplayName("Should not support unknown capability")
    void shouldNotSupportUnknown() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.supportsCapability("UNKNOWN_CAP")).isFalse();
    }
  }

  @Nested
  @DisplayName("getPackageName() / getBinaryName()")
  class ToolMappingTest {

    @Test
    @DisplayName("getPackageName() should throw when tool is null")
    void getPackageName_shouldThrowWhenToolNull() {
      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.getPackageName(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tool must not be null");
    }

    @Test
    @DisplayName("getBinaryName() should throw when tool is null")
    void getBinaryName_shouldThrowWhenToolNull() {
      Platform platform = new TestLinuxPlatform();

      assertThatThrownBy(() -> platform.getBinaryName(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tool must not be null");
    }

    @Test
    @DisplayName("Should return curl package name")
    void shouldReturnCurlPackageName() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getPackageName(Tool.CURL)).isEqualTo("curl");
    }

    @Test
    @DisplayName("Should return curl binary name")
    void shouldReturnCurlBinaryName() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getBinaryName(Tool.CURL)).isEqualTo("curl");
    }

    @Test
    @DisplayName("Should return iptables package name")
    void shouldReturnIptablesPackageName() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getPackageName(Tool.IPTABLES)).isEqualTo("iptables");
    }

    @Test
    @DisplayName("Should return ca-certificates package name")
    void shouldReturnCaCertificatesPackageName() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getPackageName(Tool.CA_CERTIFICATES)).isEqualTo("ca-certificates");
    }

    @Test
    @DisplayName("Should return procps package name")
    void shouldReturnProcpsPackageName() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getPackageName(Tool.PROCPS)).isEqualTo("procps");
    }

    @Test
    @DisplayName("Should return ps binary name for PROCPS")
    void shouldReturnPsBinaryNameForProcps() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getBinaryName(Tool.PROCPS)).isEqualTo("ps");
    }

    @Test
    @DisplayName("Should return iproute2 package name")
    void shouldReturnIproute2PackageName() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getPackageName(Tool.IPROUTE)).isEqualTo("iproute2");
    }

    @Test
    @DisplayName("Should return ip binary name for IPROUTE")
    void shouldReturnIpBinaryNameForIproute() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getBinaryName(Tool.IPROUTE)).isEqualTo("ip");
    }

    @Test
    @DisplayName("Should return python3 for PYTHON")
    void shouldReturnPython3() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getPackageName(Tool.PYTHON)).isEqualTo("python3");
      assertThat(platform.getBinaryName(Tool.PYTHON)).isEqualTo("python3");
    }

    @Test
    @DisplayName("Should return stress-ng for STRESS_NG")
    void shouldReturnStressNg() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getPackageName(Tool.STRESS_NG)).isEqualTo("stress-ng");
      assertThat(platform.getBinaryName(Tool.STRESS_NG)).isEqualTo("stress-ng");
    }

    @Test
    @DisplayName(
        "getBinaryName() falls back to packageName when binaryName is null (CA_CERTIFICATES)")
    void getBinaryName_shouldFallbackToPackageName_whenBinaryNameNull() {
      // CA_CERTIFICATES has ToolMapping("ca-certificates", null) → binaryName() is null
      // getBinaryName() must return packageName as fallback
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getBinaryName(Tool.CA_CERTIFICATES)).isEqualTo("ca-certificates");
    }

    @Test
    @DisplayName(
        "getPackageName() throws UnsupportedOperationException for unmapped tool (via override)")
    void getPackageName_shouldThrow_whenToolNotMapped() {
      // Subclass that overrides ALL defaults with an empty map and removes CURL
      Platform platform = new NoMappingPlatform();
      assertThatThrownBy(() -> platform.getPackageName(Tool.CURL))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("CURL");
    }

    @Test
    @DisplayName(
        "getBinaryName() throws UnsupportedOperationException for unmapped tool (via override)")
    void getBinaryName_shouldThrow_whenToolNotMapped() {
      Platform platform = new NoMappingPlatform();
      assertThatThrownBy(() -> platform.getBinaryName(Tool.CURL))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("CURL");
    }
  }

  @Nested
  @DisplayName("Command Builders")
  class CommandBuildersTest {

    @Test
    @DisplayName("Should return network command builder")
    void shouldReturnNetworkCommandBuilder() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getNetworkCommandBuilder()).isNotNull();
    }

    @Test
    @DisplayName("Should return process command builder")
    void shouldReturnProcessCommandBuilder() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getProcessCommandBuilder()).isNotNull();
    }

    @Test
    @DisplayName("Should return HTTP command builder")
    void shouldReturnHttpCommandBuilder() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getHttpCommandBuilder()).isNotNull();
    }
  }

  @Nested
  @DisplayName("getDefaultShell()")
  class GetDefaultShellTest {

    @Test
    @DisplayName("Should return shell instance")
    void shouldReturnShell() {
      Platform platform = new TestLinuxPlatform();
      assertThat(platform.getDefaultShell()).isNotNull();
    }
  }
}
