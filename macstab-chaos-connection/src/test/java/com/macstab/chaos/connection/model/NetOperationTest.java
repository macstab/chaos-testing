/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("NetOperation")
class NetOperationTest {

  @Nested
  @DisplayName("wireForm")
  class WireForm {

    @ParameterizedTest
    @EnumSource(NetOperation.class)
    @DisplayName("is non-null and non-blank for every value")
    void nonBlank(final NetOperation op) {
      assertThat(op.wireForm()).isNotNull().isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(NetOperation.class)
    @DisplayName("is lowercase (libchaos-net contract)")
    void lowercase(final NetOperation op) {
      assertThat(op.wireForm()).isEqualTo(op.wireForm().toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("is unique across all values")
    void uniqueAcrossValues() {
      final Map<String, Long> counts =
          Arrays.stream(NetOperation.values())
              .map(NetOperation::wireForm)
              .collect(Collectors.groupingBy(form -> form, Collectors.counting()));
      assertThat(counts).allSatisfy((wire, count) -> assertThat(count).isEqualTo(1L));
    }

    @Test
    @DisplayName("matches the libchaos-net grammar exactly")
    void exactGrammarMapping() {
      assertThat(NetOperation.SOCKET.wireForm()).isEqualTo("socket");
      assertThat(NetOperation.BIND.wireForm()).isEqualTo("bind");
      assertThat(NetOperation.LISTEN.wireForm()).isEqualTo("listen");
      assertThat(NetOperation.CONNECT.wireForm()).isEqualTo("connect");
      assertThat(NetOperation.ACCEPT.wireForm()).isEqualTo("accept");
      assertThat(NetOperation.SHUTDOWN.wireForm()).isEqualTo("shutdown");
      assertThat(NetOperation.SEND.wireForm()).isEqualTo("send");
      assertThat(NetOperation.RECV.wireForm()).isEqualTo("recv");
      assertThat(NetOperation.POLL.wireForm()).isEqualTo("poll");
    }
  }

  @Nested
  @DisplayName("enum surface")
  class Surface {

    @Test
    @DisplayName("declares exactly nine operations matching the libchaos-net rule grammar")
    void valueCount() {
      assertThat(NetOperation.values()).hasSize(9);
    }
  }
}
