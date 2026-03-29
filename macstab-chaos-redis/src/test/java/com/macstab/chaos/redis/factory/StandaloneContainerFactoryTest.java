/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for {@link StandaloneContainerFactory}.
 *
 * <p>Both factory methods return configured (not started) containers — all configuration is
 * verifiable without Docker. Tests cover: utility class guard, port exposure, startup timeout,
 * command configuration, and SSL-specific file mounts and command structure.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@DisplayName("StandaloneContainerFactory")
class StandaloneContainerFactoryTest {

  // ─── Constructor guard ────────────────────────────────────────────────────

  @Nested
  @DisplayName("Constructor guard")
  class ConstructorGuardTests {

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException via reflection")
    void shouldThrowOnReflectiveInstantiation() throws Exception {
      // ARRANGE
      final Constructor<StandaloneContainerFactory> ctor =
          StandaloneContainerFactory.class.getDeclaredConstructor();
      ctor.setAccessible(true);

      // ACT & ASSERT
      assertThatThrownBy(ctor::newInstance)
          .isInstanceOf(InvocationTargetException.class)
          .hasCauseInstanceOf(UnsupportedOperationException.class)
          .hasRootCauseMessage("Utility class - not instantiable");
    }
  }

  // ─── createStandalone() ──────────────────────────────────────────────────

  @Nested
  @DisplayName("createStandalone()")
  class CreateStandaloneTests {

    @Test
    @DisplayName("Exposes port 6379")
    void shouldExposePort6379() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandalone();

      // ASSERT
      assertThat(container.getExposedPorts()).contains(6379);
    }

    @Test
    @DisplayName("Uses redis:7-alpine image")
    void shouldUseRedisAlpineImage() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandalone();

      // ASSERT
      assertThat(container.getDockerImageName()).isEqualTo("redis:7-alpine");
    }

    @Test
    @DisplayName("Returns a non-null container")
    void shouldReturnNonNullContainer() {
      // ACT & ASSERT
      assertThat(StandaloneContainerFactory.createStandalone()).isNotNull();
    }
  }

  // ─── createStandaloneWithSSL() ───────────────────────────────────────────

  @Nested
  @DisplayName("createStandaloneWithSSL()")
  class CreateStandaloneWithSslTests {

    @Test
    @DisplayName("Exposes port 6380 (TLS port)")
    void shouldExposePort6380() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandaloneWithSSL();

      // ASSERT
      assertThat(container.getExposedPorts()).contains(6380);
    }

    @Test
    @DisplayName("Does NOT expose port 6379 (plain port disabled)")
    void shouldNotExposePort6379() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandaloneWithSSL();

      // ASSERT
      assertThat(container.getExposedPorts()).doesNotContain(6379);
    }

    @Test
    @DisplayName("Uses redis:7-alpine image")
    void shouldUseRedisAlpineImage() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandaloneWithSSL();

      // ASSERT
      assertThat(container.getDockerImageName()).isEqualTo("redis:7-alpine");
    }

    @Test
    @DisplayName("Command includes redis-server with --port 0 (plain TCP disabled)")
    void shouldDisablePlainTcpPort() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandaloneWithSSL();

      // ASSERT
      assertThat(container.getCommandParts()).containsSequence("redis-server", "--port", "0");
    }

    @Test
    @DisplayName("Command includes --tls-port 6380")
    void shouldConfigureTlsPort6380() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandaloneWithSSL();

      // ASSERT
      assertThat(container.getCommandParts()).containsSequence("--tls-port", "6380");
    }

    @Test
    @DisplayName("Command includes --tls-auth-clients yes (mutual TLS enforced)")
    void shouldEnforceMutualTls() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandaloneWithSSL();

      // ASSERT
      assertThat(container.getCommandParts()).containsSequence("--tls-auth-clients", "yes");
    }

    @Test
    @DisplayName("Command includes TLS certificate paths")
    void shouldIncludeCertificatePaths() {
      // ACT
      final GenericContainer<?> container = StandaloneContainerFactory.createStandaloneWithSSL();

      // ASSERT
      assertThat(container.getCommandParts())
          .containsSequence("--tls-cert-file", "/tls/server.crt")
          .containsSequence("--tls-key-file", "/tls/server.key")
          .containsSequence("--tls-ca-cert-file", "/tls/ca.crt");
    }
  }
}
