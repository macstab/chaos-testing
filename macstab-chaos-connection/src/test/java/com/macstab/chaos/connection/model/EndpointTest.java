/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Endpoint")
class EndpointTest {

  // ==================== Tcp4 ====================

  @Nested
  @DisplayName("Tcp4")
  class Tcp4Tests {

    @Test
    @DisplayName("renders host:port with tcp4 scheme")
    void renders() {
      assertThat(Endpoint.tcp4("db.example.com", 5432).toSelector())
          .isEqualTo("tcp4://db.example.com:5432");
    }

    @Test
    @DisplayName("null host is rejected")
    void nullHost() {
      assertThatThrownBy(() -> Endpoint.tcp4(null, 5432))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("host");
    }

    @Test
    @DisplayName("blank host is rejected")
    void blankHost() {
      assertThatThrownBy(() -> Endpoint.tcp4("   ", 5432))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"db\nhost", "db\rhost", "host\r\ninjected"})
    @DisplayName("host containing newline is rejected (config-file injection guard)")
    void newlineHost(final String evil) {
      assertThatThrownBy(() -> Endpoint.tcp4(evil, 5432))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("newline");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE})
    @DisplayName("out-of-range port is rejected")
    void invalidPort(final int port) {
      assertThatThrownBy(() -> Endpoint.tcp4("db", port))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("port");
    }

    @Test
    @DisplayName("boundary ports 1 and 65535 are accepted")
    void boundaryPorts() {
      assertThat(Endpoint.tcp4("db", 1).toSelector()).isEqualTo("tcp4://db:1");
      assertThat(Endpoint.tcp4("db", 65535).toSelector()).isEqualTo("tcp4://db:65535");
    }

    @Test
    @DisplayName("equal records compare equal (record contract)")
    void equality() {
      assertThat(Endpoint.tcp4("db", 5432)).isEqualTo(Endpoint.tcp4("db", 5432));
    }
  }

  // ==================== Tcp6 ====================

  @Nested
  @DisplayName("Tcp6")
  class Tcp6Tests {

    @Test
    @DisplayName("renders bracketed host with tcp6 scheme")
    void renders() {
      assertThat(Endpoint.tcp6("::1", 5432).toSelector()).isEqualTo("tcp6://[::1]:5432");
    }

    @Test
    @DisplayName("link-local with zone id renders unmodified inside brackets")
    void linkLocal() {
      assertThat(Endpoint.tcp6("fe80::1", 80).toSelector()).isEqualTo("tcp6://[fe80::1]:80");
    }

    @Test
    @DisplayName("blank host is rejected")
    void blankHost() {
      assertThatThrownBy(() -> Endpoint.tcp6(" ", 5432))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== Udp4 / Udp6 ====================

  @Nested
  @DisplayName("Udp4")
  class Udp4Tests {

    @Test
    @DisplayName("renders host:port with udp4 scheme")
    void renders() {
      assertThat(Endpoint.udp4("8.8.8.8", 53).toSelector()).isEqualTo("udp4://8.8.8.8:53");
    }
  }

  @Nested
  @DisplayName("Udp6")
  class Udp6Tests {

    @Test
    @DisplayName("renders bracketed host with udp6 scheme")
    void renders() {
      assertThat(Endpoint.udp6("2001:4860:4860::8888", 53).toSelector())
          .isEqualTo("udp6://[2001:4860:4860::8888]:53");
    }
  }

  // ==================== Unix ====================

  @Nested
  @DisplayName("Unix")
  class UnixTests {

    @Test
    @DisplayName("renders absolute path with three-slash form")
    void renders() {
      assertThat(Endpoint.unix("/var/run/redis.sock").toSelector())
          .isEqualTo("unix:///var/run/redis.sock");
    }

    @Test
    @DisplayName("relative path is rejected")
    void relativePath() {
      assertThatThrownBy(() -> Endpoint.unix("var/run/redis.sock"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("absolute");
    }

    @Test
    @DisplayName("null path is rejected")
    void nullPath() {
      assertThatThrownBy(() -> Endpoint.unix(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("blank path is rejected")
    void blankPath() {
      assertThatThrownBy(() -> Endpoint.unix("  "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("path containing newline is rejected (config-file injection guard)")
    void newlinePath() {
      assertThatThrownBy(() -> Endpoint.unix("/var/run/x\n/etc/passwd"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("newline");
    }
  }

  // ==================== Dns ====================

  @Nested
  @DisplayName("Dns")
  class DnsTests {

    @Test
    @DisplayName("renders hostname with dns scheme")
    void renders() {
      assertThat(Endpoint.dns("db.internal").toSelector()).isEqualTo("dns://db.internal");
    }

    @Test
    @DisplayName("blank hostname is rejected")
    void blankHostname() {
      assertThatThrownBy(() -> Endpoint.dns(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("hostname containing newline is rejected")
    void newlineHostname() {
      assertThatThrownBy(() -> Endpoint.dns("db\nevil"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("newline");
    }
  }

  // ==================== Wildcard ====================

  @Nested
  @DisplayName("Wildcard")
  class WildcardTests {

    @Test
    @DisplayName("renders as bare asterisk")
    void renders() {
      assertThat(Endpoint.wildcard().toSelector()).isEqualTo("*");
    }

    @Test
    @DisplayName("factory returns the singleton enum instance")
    void singleton() {
      assertThat(Endpoint.wildcard()).isSameAs(Endpoint.Wildcard.ANY);
    }

    @Test
    @DisplayName("Wildcard.values() has exactly one entry")
    void singleValue() {
      assertThat(Endpoint.Wildcard.values()).hasSize(1);
    }
  }

  // ==================== Sealed-hierarchy contract ====================

  @Nested
  @DisplayName("sealed hierarchy")
  class SealedHierarchy {

    @Test
    @DisplayName("pattern matching covers all seven variants exhaustively")
    void patternMatchExhaustive() {
      assertThat(describe(Endpoint.tcp4("h", 1))).isEqualTo("tcp4");
      assertThat(describe(Endpoint.tcp6("h", 1))).isEqualTo("tcp6");
      assertThat(describe(Endpoint.udp4("h", 1))).isEqualTo("udp4");
      assertThat(describe(Endpoint.udp6("h", 1))).isEqualTo("udp6");
      assertThat(describe(Endpoint.unix("/p"))).isEqualTo("unix");
      assertThat(describe(Endpoint.dns("h"))).isEqualTo("dns");
      assertThat(describe(Endpoint.wildcard())).isEqualTo("wildcard");
    }

    /** Exhaustive switch — compilation fails if a new variant is added without updating here. */
    private String describe(final Endpoint endpoint) {
      return switch (endpoint) {
        case Endpoint.Tcp4 t -> "tcp4";
        case Endpoint.Tcp6 t -> "tcp6";
        case Endpoint.Udp4 u -> "udp4";
        case Endpoint.Udp6 u -> "udp6";
        case Endpoint.Unix u -> "unix";
        case Endpoint.Dns d -> "dns";
        case Endpoint.Wildcard w -> "wildcard";
      };
    }
  }
}
