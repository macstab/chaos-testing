/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.api.Endpoint;

/**
 * Unit tests for {@link ConnectionEndpointExtractor}.
 *
 * <p>Pure string parsing — no mocking, no Docker required.
 */
@DisplayName("ConnectionEndpointExtractor")
class ConnectionEndpointExtractorTest {

  @Nested
  @DisplayName("extractFromString() — REDIS_URI pattern")
  class RedisUriPattern {

    @Test
    @DisplayName("Should extract endpoint from redis://host:port")
    void shouldExtractFromRedisUri() {
      // ARRANGE
      final String connectionString = "StatefulRedisConnectionImpl[redis://127.0.0.1:6379, ...]";

      // ACT
      final Optional<Endpoint> result =
          ConnectionEndpointExtractor.extractFromString(connectionString);

      // ASSERT
      assertThat(result).isPresent();
      assertThat(result.get().host()).isEqualTo("127.0.0.1");
      assertThat(result.get().port()).isEqualTo(6379);
    }

    @Test
    @DisplayName("Should extract from redis://localhost:6380")
    void shouldExtractFromRedisUriLocalhost() {
      // ARRANGE
      final String cs = "redis://localhost:6380 connected";

      // ACT
      final Optional<Endpoint> result = ConnectionEndpointExtractor.extractFromString(cs);

      // ASSERT
      assertThat(result).isPresent();
      assertThat(result.get().host()).isEqualTo("localhost");
      assertThat(result.get().port()).isEqualTo(6380);
    }
  }

  @Nested
  @DisplayName("extractFromString() — remoteAddress pattern")
  class RemoteAddressPattern {

    @Test
    @DisplayName("Should extract from remoteAddress=host:port")
    void shouldExtractFromRemoteAddress() {
      // ARRANGE
      final String cs =
          "StatefulRedisConnectionImpl[remoteAddress=172.18.0.2:6379, localAddress=...]";

      // ACT
      final Optional<Endpoint> result = ConnectionEndpointExtractor.extractFromString(cs);

      // ASSERT
      assertThat(result).isPresent();
      assertThat(result.get().host()).isEqualTo("172.18.0.2");
      assertThat(result.get().port()).isEqualTo(6379);
    }
  }

  @Nested
  @DisplayName("extractFromString() — generic host:port pattern")
  class HostPortPattern {

    @Test
    @DisplayName("Should extract from generic host:port")
    void shouldExtractFromGenericHostPort() {
      // ARRANGE
      final String cs = "connection to myredis.local:6379 is open";

      // ACT
      final Optional<Endpoint> result = ConnectionEndpointExtractor.extractFromString(cs);

      // ASSERT
      assertThat(result).isPresent();
      assertThat(result.get().port()).isEqualTo(6379);
    }
  }

  @Nested
  @DisplayName("extractFromString() — failure cases")
  class FailureCases {

    @Test
    @DisplayName("Should return empty for malformed string with no recognizable pattern")
    void shouldReturnEmptyForMalformedString() {
      // ARRANGE
      final String cs = "this has no host or port info at all";

      // ACT
      final Optional<Endpoint> result = ConnectionEndpointExtractor.extractFromString(cs);

      // ASSERT
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty for blank string")
    void shouldReturnEmptyForBlankString() {
      // ACT
      final Optional<Endpoint> result = ConnectionEndpointExtractor.extractFromString("   ");

      // ASSERT
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should throw NPE for null connectionString")
    void shouldThrowNpeForNullString() {
      // ACT & ASSERT
      assertThatThrownBy(() -> ConnectionEndpointExtractor.extractFromString(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("extractEndpoint() — null connection")
  class NullConnection {

    @Test
    @DisplayName("Should throw NPE for null connection")
    void shouldThrowNpeForNullConnection() {
      // ACT & ASSERT
      assertThatThrownBy(() -> ConnectionEndpointExtractor.extractEndpoint(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("extractFromString() — port edge cases")
  class PortEdgeCases {

    @Test
    @DisplayName("Should return empty for port 0 (invalid)")
    void shouldReturnEmptyForPortZero() {
      // Port 0 is invalid per Endpoint record validation
      final Optional<Endpoint> result =
          ConnectionEndpointExtractor.extractFromString("redis://127.0.0.1:0");

      // Port 0 causes Endpoint construction to throw → tryPattern catches and returns null
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty for port 65536 (out of range)")
    void shouldReturnEmptyForPortTooHigh() {
      // 65536 exceeds max port — Endpoint throws IAE, tryPattern catches → empty
      final Optional<Endpoint> result =
          ConnectionEndpointExtractor.extractFromString("redis://127.0.0.1:65536");

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
        "Should extract endpoint from string with multiple host:port patterns — returns first match")
    void shouldReturnFirstMatchForMultiplePatterns() {
      // The extractor returns the first matching endpoint (redis:// pattern has priority)
      final String cs =
          "StatefulRedisConnectionImpl[redis://172.18.0.2:6379, remoteAddress=172.18.0.2:6380]";

      final Optional<Endpoint> result = ConnectionEndpointExtractor.extractFromString(cs);

      assertThat(result).isPresent();
      // First pattern (redis://) matches first → port 6379
      assertThat(result.get().port()).isEqualTo(6379);
    }

    @Test
    @DisplayName("Should extract from string with valid high port number")
    void shouldExtractValidHighPort() {
      // Port 6380 is a valid Redis port variant
      final String cs = "redis://some-host.local:6380 connected";

      final Optional<Endpoint> result = ConnectionEndpointExtractor.extractFromString(cs);

      assertThat(result).isPresent();
      assertThat(result.get().host()).isEqualTo("some-host.local");
      assertThat(result.get().port()).isEqualTo(6380);
    }
  }
}
