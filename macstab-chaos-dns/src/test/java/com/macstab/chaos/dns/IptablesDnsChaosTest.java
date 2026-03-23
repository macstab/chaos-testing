/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;

/**
 * Integration tests for {@link IptablesDnsChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class IptablesDnsChaosTest {

  private GenericContainer<?> container;
  private IptablesDnsChaos chaos;

  @BeforeEach
  void setUp() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    container.start();

    chaos = new IptablesDnsChaos();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  @Test
  @DisplayName("should block DNS resolution (NXDOMAIN)")
  void shouldBlockResolution() throws Exception {
    final String hostname = "example.com";

    chaos.blockResolution(container, hostname);

    assertThat(container.execInContainer("pgrep", "coredns").getExitCode()).isZero();
  }

  @Test
  @DisplayName("should return NXDOMAIN for hostname")
  void shouldReturnNXDOMAIN() throws Exception {
    final String hostname = "test.example.com";

    chaos.returnNXDOMAIN(container, hostname);

    final var file = container.execInContainer("cat", "/tmp/chaos-dns-nxdomain");
    assertThat(file.getStdout()).contains(hostname);
  }

  @Test
  @DisplayName("should return SERVFAIL for hostname")
  void shouldReturnSERVFAIL() throws Exception {
    final String hostname = "fail.example.com";

    chaos.returnSERVFAIL(container, hostname);

    final var file = container.execInContainer("cat", "/tmp/chaos-dns-servfail");
    assertThat(file.getStdout()).contains(hostname);
  }

  @Test
  @DisplayName("should return REFUSED for hostname")
  void shouldReturnREFUSED() throws Exception {
    final String hostname = "blocked.example.com";

    chaos.returnREFUSED(container, hostname);

    final var file = container.execInContainer("cat", "/tmp/chaos-dns-refused");
    assertThat(file.getStdout()).contains(hostname);
  }

  @Test
  @DisplayName("should rewrite host to target IP")
  void shouldRewriteHost() throws Exception {
    final String hostname = "db.prod.com";
    final String targetIp = "127.0.0.1";

    chaos.rewriteHost(container, hostname, targetIp);

    final var file = container.execInContainer("cat", "/tmp/chaos-dns-hosts");
    assertThat(file.getStdout()).contains(targetIp).contains(hostname);
  }

  @Test
  @DisplayName("should reset DNS chaos")
  void shouldReset() throws Exception {
    chaos.blockResolution(container, "test.com");

    chaos.reset(container);

    assertThat(container.execInContainer("pgrep", "coredns").getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("should be supported")
  void shouldBeSupported() throws Exception {
    assertThat(chaos.isSupported()).isTrue();
  }

  @Test
  @DisplayName("should reject invalid hostname")
  void shouldRejectInvalidHostname() throws Exception {
    assertThatThrownBy(() -> chaos.blockResolution(container, "invalid_host!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid hostname");
  }

  @Test
  @DisplayName("should reject invalid IP address")
  void shouldRejectInvalidIP() throws Exception {
    assertThatThrownBy(() -> chaos.rewriteHost(container, "test.com", "999.999.999.999"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid IP address");
  }

  @Test
  @DisplayName("should reject stopped container")
  void shouldRejectStoppedContainer() throws Exception {
    container.stop();

    assertThatThrownBy(() -> chaos.blockResolution(container, "test.com"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be running");
  }

  @Test
  @DisplayName("should handle wildcard hostnames")
  void shouldHandleWildcards() throws Exception {
    final String hostname = "*.example.com";

    chaos.blockResolution(container, hostname);

    final var file = container.execInContainer("cat", "/tmp/chaos-dns-nxdomain");
    assertThat(file.getStdout()).contains(hostname);
  }
}
