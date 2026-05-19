/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.translators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.macstab.chaos.connection.annotation.l1.accept.ChaosAcceptEmfile;
import com.macstab.chaos.connection.annotation.l1.connect.ChaosConnectEconnrefused;
import com.macstab.chaos.connection.annotation.l1.connect.ChaosConnectEhostunreach;
import com.macstab.chaos.connection.annotation.l1.connect.ChaosConnectLatency;
import com.macstab.chaos.connection.annotation.l1.poll.ChaosPollLatency;
import com.macstab.chaos.connection.annotation.l1.poll.ChaosPollTimeout;
import com.macstab.chaos.connection.annotation.l1.recv.ChaosRecvCorrupt;
import com.macstab.chaos.connection.annotation.l1.recv.ChaosRecvEagain;
import com.macstab.chaos.connection.annotation.l1.send.ChaosSendEpipe;
import com.macstab.chaos.connection.annotation.l1.send.ChaosSendLatency;
import com.macstab.chaos.connection.model.Effect;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

@DisplayName("Connection L1 translators — table-driven")
class ConnectionTranslatorsTest {

  @ChaosConnectEconnrefused
  static class ConnectEconnrefused {}

  @ChaosConnectEhostunreach
  static class ConnectEhostunreach {}

  @ChaosAcceptEmfile
  static class AcceptEmfile {}

  @ChaosSendEpipe
  static class SendEpipe {}

  @ChaosRecvEagain
  static class RecvEagain {}

  @ChaosConnectEconnrefused(toxicity = 0.1)
  static class ConnectEconnrefusedCustom {}

  @ChaosConnectLatency
  static class ConnectLatencyDefault {}

  @ChaosSendLatency(delayMs = 500L, toxicity = 0.5)
  static class SendLatencyCustom {}

  @ChaosPollLatency
  static class PollLatencyDefault {}

  @ChaosRecvCorrupt
  static class RecvCorruptDefault {}

  @ChaosRecvCorrupt(rate = 0.05, toxicity = 0.5)
  static class RecvCorruptCustom {}

  @ChaosPollTimeout
  static class PollTimeoutDefault {}

  @ChaosPollTimeout(timeoutMs = 1000L)
  static class PollTimeoutCustom {}

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface PlainNoBinding {}

  @PlainNoBinding
  static class WithPlainNoBinding {}

  private static Annotation pick(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation on " + clazz);
  }

  @Nested
  @DisplayName("ConnectionErrnoTranslator")
  class Errno {

    static Stream<Arguments> tuples() {
      return Stream.of(
          Arguments.of(ConnectEconnrefused.class, NetOperation.CONNECT, com.macstab.chaos.connection.model.Errno.ECONNREFUSED),
          Arguments.of(ConnectEhostunreach.class, NetOperation.CONNECT, com.macstab.chaos.connection.model.Errno.EHOSTUNREACH),
          Arguments.of(AcceptEmfile.class, NetOperation.ACCEPT, com.macstab.chaos.connection.model.Errno.EMFILE),
          Arguments.of(SendEpipe.class, NetOperation.SEND, com.macstab.chaos.connection.model.Errno.EPIPE),
          Arguments.of(RecvEagain.class, NetOperation.RECV, com.macstab.chaos.connection.model.Errno.EAGAIN));
    }

    @ParameterizedTest(name = "{0} → ({1}, {2})")
    @MethodSource("tuples")
    void translates(
        final Class<?> fixture, final NetOperation op, final com.macstab.chaos.connection.model.Errno expectedErrno) {
      final NetRule rule = ConnectionErrnoTranslator.buildRule(pick(fixture));
      assertThat(rule.endpoint()).isInstanceOf(Endpoint.Wildcard.class);
      assertThat(rule.operation()).isEqualTo(op);
      assertThat(rule.effect()).isInstanceOf(Effect.ErrnoFault.class);
      assertThat(((Effect.ErrnoFault) rule.effect()).errno()).isEqualTo(expectedErrno);
      assertThat(rule.toxicity()).isEqualTo(1.0);
    }

    @Test
    void customToxicity() {
      final NetRule rule = ConnectionErrnoTranslator.buildRule(pick(ConnectEconnrefusedCustom.class));
      assertThat(rule.toxicity()).isEqualTo(0.1);
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> ConnectionErrnoTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@ConnectionErrnoBinding meta-annotation missing");
    }
  }

  @Nested
  @DisplayName("ConnectionLatencyTranslator")
  class Latency {

    @Test
    void defaultDelay100ms() {
      final NetRule rule = ConnectionLatencyTranslator.buildRule(pick(ConnectLatencyDefault.class));
      assertThat(rule.operation()).isEqualTo(NetOperation.CONNECT);
      assertThat(((Effect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(100));
      assertThat(rule.toxicity()).isEqualTo(1.0);
    }

    @Test
    void pollLatencyAlsoWorks() {
      final NetRule rule = ConnectionLatencyTranslator.buildRule(pick(PollLatencyDefault.class));
      assertThat(rule.operation()).isEqualTo(NetOperation.POLL);
    }

    @Test
    void customDelayAndToxicity() {
      final NetRule rule = ConnectionLatencyTranslator.buildRule(pick(SendLatencyCustom.class));
      assertThat(rule.operation()).isEqualTo(NetOperation.SEND);
      assertThat(((Effect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(500));
      assertThat(rule.toxicity()).isEqualTo(0.5);
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> ConnectionLatencyTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@ConnectionLatencyBinding meta-annotation missing");
    }
  }

  @Nested
  @DisplayName("ConnectionCorruptTranslator")
  class Corrupt {

    @Test
    void defaults() {
      final NetRule rule = ConnectionCorruptTranslator.buildRule(pick(RecvCorruptDefault.class));
      assertThat(rule.operation()).isEqualTo(NetOperation.RECV);
      assertThat(rule.effect()).isInstanceOf(Effect.Corrupt.class);
      assertThat(((Effect.Corrupt) rule.effect()).rate()).isEqualTo(0.001);
      assertThat(rule.toxicity()).isEqualTo(1.0);
    }

    @Test
    void customRateAndToxicity() {
      final NetRule rule = ConnectionCorruptTranslator.buildRule(pick(RecvCorruptCustom.class));
      assertThat(((Effect.Corrupt) rule.effect()).rate()).isEqualTo(0.05);
      assertThat(rule.toxicity()).isEqualTo(0.5);
    }
  }

  @Nested
  @DisplayName("ConnectionTimeoutTranslator")
  class Timeout {

    @Test
    void defaultTimeout5s() {
      final NetRule rule = ConnectionTimeoutTranslator.buildRule(pick(PollTimeoutDefault.class));
      assertThat(rule.operation()).isEqualTo(NetOperation.POLL);
      assertThat(rule.effect()).isInstanceOf(Effect.Timeout.class);
      assertThat(((Effect.Timeout) rule.effect()).duration()).isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void customTimeout() {
      final NetRule rule = ConnectionTimeoutTranslator.buildRule(pick(PollTimeoutCustom.class));
      assertThat(((Effect.Timeout) rule.effect()).duration()).isEqualTo(Duration.ofMillis(1000));
    }
  }
}
