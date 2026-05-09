/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NetRule")
class NetRuleTest {

  private static final Endpoint EP = Endpoint.tcp4("db", 5432);

  // ==================== errno() factory ====================

  @Nested
  @DisplayName("errno()")
  class ErrnoFactory {

    @ParameterizedTest
    @EnumSource(
        value = NetOperation.class,
        names = {"POLL"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("accepts every operation except POLL")
    void acceptsAllExceptPoll(final NetOperation op) {
      final NetRule r = NetRule.errno(EP, op, Errno.ECONNREFUSED, 1.0);
      assertThat(r.operation()).isEqualTo(op);
      assertThat(r.effect()).isInstanceOf(Effect.ErrnoFault.class);
    }

    @Test
    @DisplayName("rejects POLL")
    void rejectsPoll() {
      assertThatThrownBy(() -> NetRule.errno(EP, NetOperation.POLL, Errno.ETIMEDOUT, 1.0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("POLL");
    }

    @Test
    @DisplayName("null endpoint is rejected")
    void nullEndpoint() {
      assertThatThrownBy(() -> NetRule.errno(null, NetOperation.CONNECT, Errno.EPIPE, 1.0))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== latency() factory ====================

  @Nested
  @DisplayName("latency()")
  class LatencyFactory {

    @ParameterizedTest
    @EnumSource(NetOperation.class)
    @DisplayName("accepts every operation")
    void acceptsAll(final NetOperation op) {
      final NetRule r = NetRule.latency(EP, op, Duration.ofMillis(100), 1.0);
      assertThat(r.effect()).isInstanceOf(Effect.Latency.class);
    }

    @Test
    @DisplayName("rejects negative delay (delegated to Effect)")
    void negativeDelay() {
      assertThatThrownBy(
              () -> NetRule.latency(EP, NetOperation.SEND, Duration.ofMillis(-1), 1.0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== corrupt() factory ====================

  @Nested
  @DisplayName("corrupt()")
  class CorruptFactory {

    @Test
    @DisplayName("operation is implicitly RECV")
    void implicitRecv() {
      final NetRule r = NetRule.corrupt(EP, 0.3, 1.0);
      assertThat(r.operation()).isEqualTo(NetOperation.RECV);
      assertThat(r.effect()).isInstanceOf(Effect.Corrupt.class);
    }

    @Test
    @DisplayName("rejects rate outside (0, 1] (delegated to Effect)")
    void invalidRate() {
      assertThatThrownBy(() -> NetRule.corrupt(EP, 0.0, 1.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> NetRule.corrupt(EP, 1.5, 1.0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== timeout() factory ====================

  @Nested
  @DisplayName("timeout()")
  class TimeoutFactory {

    @Test
    @DisplayName("operation is implicitly POLL")
    void implicitPoll() {
      final NetRule r = NetRule.timeout(EP, Duration.ofSeconds(5), 1.0);
      assertThat(r.operation()).isEqualTo(NetOperation.POLL);
      assertThat(r.effect()).isInstanceOf(Effect.Timeout.class);
    }

    @Test
    @DisplayName("rejects zero duration (delegated to Effect)")
    void zeroDuration() {
      assertThatThrownBy(() -> NetRule.timeout(EP, Duration.ZERO, 1.0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ==================== toxicity validation ====================

  @Nested
  @DisplayName("toxicity")
  class Toxicity {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, 1.0001, Double.MAX_VALUE, -Double.MAX_VALUE})
    @DisplayName("outside (0.0, 1.0] is rejected")
    void invalid(final double toxicity) {
      assertThatThrownBy(
              () -> NetRule.errno(EP, NetOperation.CONNECT, Errno.ECONNREFUSED, toxicity))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("NaN is rejected")
    void nan() {
      assertThatThrownBy(
              () -> NetRule.errno(EP, NetOperation.CONNECT, Errno.ECONNREFUSED, Double.NaN))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("boundary 1.0 is accepted, near-zero accepted")
    void boundaries() {
      assertThat(NetRule.errno(EP, NetOperation.CONNECT, Errno.ECONNREFUSED, 1.0).toxicity())
          .isEqualTo(1.0);
      assertThat(NetRule.errno(EP, NetOperation.CONNECT, Errno.ECONNREFUSED, 0.0001).toxicity())
          .isEqualTo(0.0001);
    }
  }

  // ==================== canonical constructor (defence-in-depth) ====================

  @Nested
  @DisplayName("canonical constructor enforces op×effect matrix")
  class CanonicalConstructor {

    @Test
    @DisplayName("CORRUPT on non-RECV operation is rejected even bypassing factories")
    void corruptOnSend() {
      assertThatThrownBy(
              () ->
                  new NetRule(
                      EP, NetOperation.SEND, Effect.corrupt(0.5), 1.0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CORRUPT");
    }

    @Test
    @DisplayName("TIMEOUT on non-POLL operation is rejected even bypassing factories")
    void timeoutOnConnect() {
      assertThatThrownBy(
              () ->
                  new NetRule(
                      EP, NetOperation.CONNECT, Effect.timeout(Duration.ofMillis(1)), 1.0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("TIMEOUT");
    }

    @Test
    @DisplayName("ERRNO on POLL is rejected even bypassing factories")
    void errnoOnPoll() {
      assertThatThrownBy(
              () ->
                  new NetRule(
                      EP, NetOperation.POLL, Effect.errno(Errno.ETIMEDOUT), 1.0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("POLL");
    }

    @Test
    @DisplayName("LATENCY on every operation is accepted")
    void latencyOnAllOps() {
      for (final NetOperation op : EnumSet.allOf(NetOperation.class)) {
        new NetRule(EP, op, Effect.latency(Duration.ZERO), 1.0); // must not throw
      }
    }
  }
}
