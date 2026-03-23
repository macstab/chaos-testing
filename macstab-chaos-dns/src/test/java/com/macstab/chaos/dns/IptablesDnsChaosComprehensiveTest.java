/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;

@DisplayName("IptablesDnsChaos - Comprehensive Tests")
class IptablesDnsChaosComprehensiveTest {
  private GenericContainer<?> container;
  private IptablesDnsChaos chaos;

  @AfterEach
  void tearDown() throws Exception {
    if (container != null && container.isRunning()) {
      if (chaos != null) chaos.reset(container);
      container.stop();
    }
  }

  @Nested
  @DisplayName("Debian Tests")
  class DebianTests {
    @ParameterizedTest
    @ValueSource(strings = {"api.example.com", "db.prod.com", "cache.internal.net"})
    void shouldBlockResolution(String hostname) throws Exception {
      container = createDebianContainer();
      chaos = new IptablesDnsChaos();
      chaos.blockResolution(container, hostname);
      assertThat(container.execInContainer("pgrep", "coredns").getExitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("Alpine Tests")
  class AlpineTests {
    @Test
    void shouldReturnNXDOMAIN() throws Exception {
      container = createAlpineContainer();
      chaos = new IptablesDnsChaos();
      chaos.returnNXDOMAIN(container, "blocked.com");
      assertThat(container.execInContainer("cat", "/tmp/chaos-dns-nxdomain").getStdout())
          .contains("blocked.com");
    }
  }

  @Nested
  @DisplayName("Positive Tests")
  class PositiveTests {
    @ParameterizedTest
    @ValueSource(strings = {"test1.com", "test2.org", "test3.net", "test4.io"})
    void shouldHandleMultipleBlocks(String hostname) throws Exception {
      container = createDebianContainer();
      chaos = new IptablesDnsChaos();
      chaos.blockResolution(container, hostname);
      assertThat(container.isRunning()).isTrue();
    }

    @Test
    void shouldRewriteMultipleHosts() throws Exception {
      container = createDebianContainer();
      chaos = new IptablesDnsChaos();
      chaos.rewriteHost(container, "api.com", "127.0.0.1");
      chaos.rewriteHost(container, "db.com", "192.168.1.1");
      chaos.rewriteHost(container, "cache.com", "10.0.0.1");
      assertThat(container.isRunning()).isTrue();
    }
  }

  @Nested
  @DisplayName("Negative Tests")
  class NegativeTests {
    @ParameterizedTest
    @ValueSource(strings = {"invalid!", "test@host", "host with spaces", "host;rm -rf /"})
    void shouldRejectInvalidHostnames(String hostname) throws Exception {
      container = createDebianContainer();
      chaos = new IptablesDnsChaos();
      assertThatThrownBy(() -> chaos.blockResolution(container, hostname))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999.999.999.999", "256.1.1.1", "invalid", "1.2.3"})
    void shouldRejectInvalidIPs(String ip) throws Exception {
      container = createDebianContainer();
      chaos = new IptablesDnsChaos();
      assertThatThrownBy(() -> chaos.rewriteHost(container, "test.com", ip))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Cleanup Tests")
  class CleanupTests {
    @Test
    void shouldRemoveAllRules() throws Exception {
      container = createDebianContainer();
      chaos = new IptablesDnsChaos();
      chaos.blockResolution(container, "test.com");
      chaos.reset(container);
      assertThat(container.execInContainer("pgrep", "coredns").getExitCode()).isNotZero();
    }
  }

  private GenericContainer<?> createDebianContainer() {
    var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }

  private GenericContainer<?> createAlpineContainer() {
    var c =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withCreateContainerCmdModifier(
                cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
    c.start();
    return c;
  }
}
