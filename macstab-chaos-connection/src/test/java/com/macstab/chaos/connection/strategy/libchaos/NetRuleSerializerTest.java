/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

@DisplayName("NetRuleSerializer")
class NetRuleSerializerTest {

  @Test
  @DisplayName("errno on TCP renders selector:op:effect_with_value:toxicity")
  void errnoTcp4() {
    final NetRule r =
        NetRule.errno(
            Endpoint.tcp4("db.example.com", 5432),
            NetOperation.CONNECT,
            Errno.ECONNREFUSED,
            0.5);
    assertThat(NetRuleSerializer.serialize(r))
        .isEqualTo("tcp4://db.example.com:5432:connect:ERRNO:ECONNREFUSED:0.5");
  }

  @Test
  @DisplayName("latency renders milliseconds")
  void latency() {
    final NetRule r =
        NetRule.latency(Endpoint.tcp4("h", 80), NetOperation.SEND, Duration.ofMillis(200), 1.0);
    assertThat(NetRuleSerializer.serialize(r)).isEqualTo("tcp4://h:80:send:LATENCY:200:1.0");
  }

  @Test
  @DisplayName("corrupt is RECV-only and renders rate")
  void corrupt() {
    final NetRule r = NetRule.corrupt(Endpoint.tcp4("h", 80), 0.3, 1.0);
    assertThat(NetRuleSerializer.serialize(r)).isEqualTo("tcp4://h:80:recv:CORRUPT:0.3:1.0");
  }

  @Test
  @DisplayName("timeout is POLL-only and renders milliseconds")
  void timeout() {
    final NetRule r = NetRule.timeout(Endpoint.tcp4("h", 80), Duration.ofSeconds(5), 1.0);
    assertThat(NetRuleSerializer.serialize(r)).isEqualTo("tcp4://h:80:poll:TIMEOUT:5000:1.0");
  }

  @Test
  @DisplayName("IPv6 selector keeps brackets through serialization")
  void ipv6() {
    final NetRule r =
        NetRule.errno(Endpoint.tcp6("::1", 5432), NetOperation.CONNECT, Errno.EPIPE, 1.0);
    assertThat(NetRuleSerializer.serialize(r))
        .isEqualTo("tcp6://[::1]:5432:connect:ERRNO:EPIPE:1.0");
  }

  @Test
  @DisplayName("DNS selector renders verbatim")
  void dns() {
    final NetRule r =
        NetRule.errno(Endpoint.dns("db.internal"), NetOperation.CONNECT, Errno.EHOSTUNREACH, 1.0);
    assertThat(NetRuleSerializer.serialize(r))
        .isEqualTo("dns://db.internal:connect:ERRNO:EHOSTUNREACH:1.0");
  }

  @Test
  @DisplayName("Unix socket selector keeps three-slash form")
  void unixSocket() {
    final NetRule r =
        NetRule.errno(
            Endpoint.unix("/var/run/redis.sock"),
            NetOperation.CONNECT,
            Errno.ECONNREFUSED,
            1.0);
    assertThat(NetRuleSerializer.serialize(r))
        .isEqualTo("unix:///var/run/redis.sock:connect:ERRNO:ECONNREFUSED:1.0");
  }

  @Test
  @DisplayName("Wildcard endpoint renders as bare asterisk")
  void wildcard() {
    final NetRule r =
        NetRule.errno(Endpoint.wildcard(), NetOperation.SOCKET, Errno.EMFILE, 1.0);
    assertThat(NetRuleSerializer.serialize(r)).isEqualTo("*:socket:ERRNO:EMFILE:1.0");
  }

  @Test
  @DisplayName("null rule throws NPE")
  void nullRule() {
    assertThatThrownBy(() -> NetRuleSerializer.serialize(null))
        .isInstanceOf(NullPointerException.class);
  }
}
