/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Effect (unit)")
class EffectTest {

  private static InetAddress ipv4(final String s) throws UnknownHostException {
    return InetAddress.getByName(s);
  }

  private static InetAddress ipv6(final String s) throws UnknownHostException {
    return InetAddress.getByName(s);
  }

  @Nested
  @DisplayName("EaiFault")
  class EaiFaultCases {
    @Test
    @DisplayName("wireForm is the EAI code")
    void wireForm() {
      assertThat(Effect.eai(EaiErrno.EAI_FAIL).wireForm()).isEqualTo("EAI_FAIL");
    }

    @Test
    @DisplayName("isForwardOnly false (valid on both kinds)")
    void compat() {
      assertThat(Effect.eai(EaiErrno.EAI_NONAME).isForwardOnly()).isFalse();
    }

    @Test
    @DisplayName("null errno rejected")
    void nullErrno() {
      assertThatThrownBy(() -> Effect.eai(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Latency")
  class LatencyCases {
    @Test
    @DisplayName("wireForm is LATENCY:millis")
    void wireForm() {
      assertThat(Effect.latency(Duration.ofMillis(250)).wireForm()).isEqualTo("LATENCY:250");
    }

    @Test
    @DisplayName("rejects negative delay")
    void rejectsNegative() {
      assertThatThrownBy(() -> Effect.latency(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Rewrite / Service")
  class RewriteAndServiceCases {
    @Test
    @DisplayName("Rewrite wireForm is REWRITE:<to>")
    void rewriteWireForm() {
      assertThat(Effect.rewrite("example.org").wireForm()).isEqualTo("REWRITE:example.org");
    }

    @Test
    @DisplayName("Service wireForm is SERVICE:<to>")
    void serviceWireForm() {
      assertThat(Effect.service("redis").wireForm()).isEqualTo("SERVICE:redis");
    }

    @Test
    @DisplayName("rejects blank / colon / newline")
    void rejects() {
      assertThatThrownBy(() -> Effect.rewrite(" ")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Effect.rewrite("foo:bar"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Effect.rewrite("foo\nbar"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("OverrideAnswer")
  class OverrideCases {
    @Test
    @DisplayName("renders comma-separated IPv4 list")
    void ipv4List() throws Exception {
      assertThat(Effect.override(List.of(ipv4("1.2.3.4"), ipv4("5.6.7.8"))).wireForm())
          .isEqualTo("OVERRIDE:1.2.3.4,5.6.7.8");
    }

    @Test
    @DisplayName("IPv6 addresses are bracketed")
    void ipv6Bracketed() throws Exception {
      assertThat(Effect.override(List.of(ipv4("1.2.3.4"), ipv6("::1"))).wireForm())
          .isEqualTo("OVERRIDE:1.2.3.4,[::1]");
    }

    @Test
    @DisplayName("empty list rejected")
    void empty() {
      assertThatThrownBy(() -> Effect.override(List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("forward-only flag is true")
    void forwardOnly() throws Exception {
      assertThat(Effect.override(List.of(ipv4("1.2.3.4"))).isForwardOnly()).isTrue();
    }
  }

  @Nested
  @DisplayName("FilterFamily / Limit / Shuffle")
  class TransformCases {
    @Test
    @DisplayName("FilterFamily wireForm has Linux AF_* value")
    void filterFamily() {
      assertThat(Effect.filterFamily(AddressFamily.INET).wireForm()).isEqualTo("FILTER_FAMILY:2");
      assertThat(Effect.filterFamily(AddressFamily.INET6).wireForm()).isEqualTo("FILTER_FAMILY:10");
    }

    @Test
    @DisplayName("Limit wireForm is LIMIT:<max>")
    void limit() {
      assertThat(Effect.limit(0).wireForm()).isEqualTo("LIMIT:0");
      assertThat(Effect.limit(3).wireForm()).isEqualTo("LIMIT:3");
      assertThatThrownBy(() -> Effect.limit(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Shuffle is the singleton SHUFFLE token")
    void shuffle() {
      assertThat(Effect.shuffle().wireForm()).isEqualTo("SHUFFLE");
      assertThat(Effect.shuffle()).isSameAs(Effect.Shuffle.INSTANCE);
    }

    @Test
    @DisplayName("all three are forward-only")
    void forwardOnly() {
      assertThat(Effect.filterFamily(AddressFamily.INET).isForwardOnly()).isTrue();
      assertThat(Effect.limit(1).isForwardOnly()).isTrue();
      assertThat(Effect.shuffle().isForwardOnly()).isTrue();
    }
  }
}
