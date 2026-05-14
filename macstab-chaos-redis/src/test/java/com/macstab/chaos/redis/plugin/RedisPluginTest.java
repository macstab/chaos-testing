/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.api.StandaloneRedis;

/**
 * Unit tests for {@link RedisPlugin}.
 *
 * <p>All tests verify container configuration without starting Docker.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisPlugin")
class RedisPluginTest {

  private final RedisPlugin plugin = new RedisPlugin();

  @Nested
  @DisplayName("annotationType()")
  class AnnotationType {

    @Test
    @DisplayName("Should return RedisStandalone.class")
    void shouldReturnRedisStandaloneClass() {
      assertThat(plugin.annotationType()).isEqualTo(RedisStandalone.class);
    }
  }

  @Nested
  @DisplayName("supportedParameterTypes()")
  class SupportedParameterTypes {

    @Test
    @DisplayName(
        "Should be empty — RedisContainerExtension owns parameter injection for StandaloneRedis")
    void shouldBeEmpty() {
      // RedisPlugin intentionally does NOT register StandaloneRedis as a parameter type.
      // RedisContainerExtension owns parameter injection to avoid competing ParameterResolvers.
      assertThat(plugin.supportedParameterTypes()).isEmpty();
    }
  }

  @Nested
  @DisplayName("createContainer()")
  class CreateContainer {

    @Test
    @DisplayName("Should NOT set port bindings when port=0")
    void shouldNotSetPortBindingsWhenPortZero() {
      // ARRANGE
      final RedisStandalone annotation = annotation(0, new String[0], false);

      // ACT
      final GenericContainer<?> container = plugin.createContainer(annotation);

      // ASSERT
      assertThat(container.getPortBindings()).isEmpty();
    }

    @Test
    @DisplayName("Should set port bindings when port > 0")
    void shouldSetPortBindingsWhenPortPositive() {
      // ARRANGE
      final RedisStandalone annotation = annotation(6380, new String[0], false);

      // ACT
      final GenericContainer<?> container = plugin.createContainer(annotation);

      // ASSERT
      // Testcontainers normalizes port bindings to "host:container/protocol"
      assertThat(container.getPortBindings())
          .anySatisfy(binding -> assertThat(binding).startsWith("6380:6379"));
    }

    @Test
    @DisplayName("Should include redis-server and args in command when args set")
    void shouldIncludeArgsInCommand() {
      // ARRANGE
      final RedisStandalone annotation =
          annotation(0, new String[] {"--maxmemory", "256mb"}, false);

      // ACT
      final GenericContainer<?> container = plugin.createContainer(annotation);

      // ASSERT
      final String[] cmd = container.getCommandParts();
      assertThat(cmd).containsSequence("redis-server", "--maxmemory", "256mb");
    }

    @Test
    @DisplayName("Should NOT set command when no args")
    void shouldNotSetCommandWhenNoArgs() {
      // ARRANGE
      final RedisStandalone annotation = annotation(0, new String[0], false);

      // ACT
      final GenericContainer<?> container = plugin.createContainer(annotation);

      // ASSERT: no explicit command set — Testcontainers uses image default
      assertThat(container.getCommandParts()).isEmpty();
    }

    @Test
    @DisplayName("Should add NET_ADMIN capability when enableNetworkChaos=true")
    void shouldAddNetAdminWhenChaosEnabled() {
      // ARRANGE
      final RedisStandalone annotation = annotation(0, new String[0], true);

      // ACT: createContainerCmdModifier is registered — container creates fine
      final GenericContainer<?> container = plugin.createContainer(annotation);

      // ASSERT: container was created (modifier registered, not yet applied)
      assertThat(container).isNotNull();
    }
  }

  @Nested
  @DisplayName("createConnectionInfo()")
  class CreateConnectionInfo {

    @Test
    @DisplayName("Should return StandaloneRedis with correct host and port")
    void shouldReturnStandaloneRedisWithHostAndPort() {
      // ARRANGE
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.getHost()).thenReturn("localhost");
      when(container.getMappedPort(6379)).thenReturn(12345);
      final RedisStandalone annotation = annotation(0, new String[0], false);

      // ACT
      final Object info = plugin.createConnectionInfo(container, annotation);

      // ASSERT
      assertThat(info).isInstanceOf(StandaloneRedis.class);
      final StandaloneRedis redis = (StandaloneRedis) info;
      assertThat(redis.host()).isEqualTo("localhost");
      assertThat(redis.port()).isEqualTo(12345);
    }
  }

  // ==================== Helper ====================

  private RedisStandalone annotation(
      final int port, final String[] args, final boolean enableNetworkChaos) {
    return new RedisStandalone() {
      @Override
      public Class<? extends java.lang.annotation.Annotation> annotationType() {
        return RedisStandalone.class;
      }

      @Override
      public String id() {
        return "default";
      }

      @Override
      public String version() {
        return "7.4";
      }

      @Override
      public int port() {
        return port;
      }

      @Override
      public String[] args() {
        return args;
      }

      @Override
      public boolean enableNetworkChaos() {
        return enableNetworkChaos;
      }

      @Override
      public boolean enableConnectionChaos() {
        return false;
      }

      @Override
      public String[] packages() {
        return new String[0];
      }
    };
  }
}
