/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.syscall.LibchaosTransport;

@DisplayName("LibchaosNetConnectionChaos (unit)")
class LibchaosNetConnectionChaosUnitTest {

  private LibchaosTransport transport;
  private GenericContainer<?> container;
  private LibchaosNetConnectionChaos chaos;

  @BeforeEach
  void setUp() {
    transport = mock(LibchaosTransport.class);
    container = mock(GenericContainer.class);
    chaos = new LibchaosNetConnectionChaos(transport);
    when(transport.isActive(container)).thenReturn(true);
  }

  @Nested
  @DisplayName("supports")
  class Supports {

    @Test
    @DisplayName("delegates to transport.isActive")
    void delegatesActive() {
      assertThat(chaos.supports(container)).isTrue();
    }

    @Test
    @DisplayName("returns false on probe failure (defensive)")
    void probeFailure() {
      when(transport.isActive(container)).thenThrow(new RuntimeException("docker died"));
      assertThat(chaos.supports(container)).isFalse();
    }
  }

  @Nested
  @DisplayName("requirePrepared gate")
  class RequirePrepared {

    @BeforeEach
    void setNotActive() {
      when(transport.isActive(container)).thenReturn(false);
    }

    @Test
    @DisplayName("apply throws LibchaosNotPreparedException")
    void applyThrows() {
      final NetRule r =
          NetRule.errno(Endpoint.tcp4("db", 5432), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      assertThatThrownBy(() -> chaos.apply(container, r))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("net")
          .hasMessageContaining("@SyscallLevelChaos");
    }

    @Test
    @DisplayName("addLatency throws LibchaosNotPreparedException")
    void addLatencyThrows() {
      assertThatThrownBy(() -> chaos.addLatency(container, "db:5432", Duration.ofMillis(100)))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    @DisplayName("removeToxic does NOT throw (best-effort, idempotent)")
    void removeToxicSilent() {
      chaos.removeToxic(container, "db:5432", "latency"); // must not throw
      verify(transport, never()).removeRules(any(), anyString());
    }
  }

  @Nested
  @DisplayName("apply (advanced)")
  class Apply {

    @Test
    @DisplayName("calls transport.addRule with serialized rule and returns a handle")
    void appliesRule() {
      final NetRule r =
          NetRule.errno(Endpoint.tcp4("db", 5432), NetOperation.CONNECT, Errno.EPIPE, 0.5);
      final RuleHandle handle = chaos.apply(container, r);
      assertThat(handle.owner()).matches("r[0-9]+");
      verify(transport)
          .addRule(eq(container), eq(handle.owner()), contains("tcp4://db:5432:connect:ERRNO:EPIPE:0.5"));
    }

    @Test
    @DisplayName("each call mints a fresh, unique owner")
    void uniqueOwners() {
      final NetRule r =
          NetRule.errno(Endpoint.tcp4("db", 5432), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      assertThat(chaos.apply(container, r).owner())
          .isNotEqualTo(chaos.apply(container, r).owner());
    }
  }

  @Nested
  @DisplayName("applyAll")
  class ApplyAll {

    @Test
    @DisplayName("returns one handle per rule, in the input order")
    void perRule() {
      final NetRule r1 =
          NetRule.errno(Endpoint.tcp4("a", 1), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      final NetRule r2 =
          NetRule.errno(Endpoint.tcp4("b", 2), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      final List<RuleHandle> handles = chaos.applyAll(container, List.of(r1, r2));
      assertThat(handles).hasSize(2);
      verify(transport, times(2)).addRule(eq(container), anyString(), anyString());
    }

    @Test
    @DisplayName("empty list yields empty result and no transport calls")
    void empty() {
      assertThat(chaos.applyAll(container, List.of())).isEmpty();
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("remove(handle)")
  class Remove {

    @Test
    @DisplayName("calls transport.removeRules with the handle's owner")
    void removes() {
      final NetRule r =
          NetRule.errno(Endpoint.tcp4("db", 5432), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      final RuleHandle h = chaos.apply(container, r);
      chaos.remove(container, h);
      verify(transport).removeRules(container, h.owner());
    }

    @Test
    @DisplayName("unknown handle is silently no-op")
    void unknown() {
      chaos.remove(container, new RuleHandle("unknown_owner"));
      verify(transport, never()).removeRules(any(), eq("unknown_owner"));
    }
  }

  @Nested
  @DisplayName("removeAll")
  class RemoveAll {

    @Test
    @DisplayName("removes every applied rule via transport")
    void wipes() {
      final NetRule r1 =
          NetRule.errno(Endpoint.tcp4("a", 1), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      final NetRule r2 =
          NetRule.errno(Endpoint.tcp4("b", 2), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      chaos.apply(container, r1);
      chaos.apply(container, r2);
      chaos.removeAll(container);
      verify(transport, times(2)).removeRules(eq(container), anyString());
    }

    @Test
    @DisplayName("transport failure on one rule does not block remaining removals")
    void resilientOnFailure() {
      final NetRule r1 =
          NetRule.errno(Endpoint.tcp4("a", 1), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      final NetRule r2 =
          NetRule.errno(Endpoint.tcp4("b", 2), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      final RuleHandle h1 = chaos.apply(container, r1);
      chaos.apply(container, r2);
      doThrow(new ChaosOperationFailedException("first remove failed"))
          .when(transport)
          .removeRules(container, h1.owner());

      chaos.removeAll(container); // must not throw
      verify(transport, times(2)).removeRules(eq(container), anyString());
    }
  }

  @Nested
  @DisplayName("portable verb mappings")
  class PortableVerbs {

    @Test
    @DisplayName("addLatency emits SEND and RECV latency rules")
    void addLatency() {
      chaos.addLatency(container, "db:5432", Duration.ofMillis(150));
      verify(transport).addRule(eq(container), anyString(), contains("send:LATENCY:150"));
      verify(transport).addRule(eq(container), anyString(), contains("recv:LATENCY:150"));
    }

    @Test
    @DisplayName("dropPackets emits ERRNO ECONNRESET on RECV with toxicity=rate")
    void dropPackets() {
      chaos.dropPackets(container, "db:5432", 0.25);
      verify(transport)
          .addRule(eq(container), anyString(), contains("recv:ERRNO:ECONNRESET:0.25"));
    }

    @Test
    @DisplayName("dropPackets rejects rate outside (0,1]")
    void dropPacketsBadRate() {
      assertThatThrownBy(() -> chaos.dropPackets(container, "db:5432", 0.0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejectConnections emits ERRNO ECONNREFUSED on CONNECT toxicity=1.0")
    void rejectConnections() {
      chaos.rejectConnections(container, "db:5432");
      verify(transport)
          .addRule(eq(container), anyString(), contains("connect:ERRNO:ECONNREFUSED:1.0"));
    }

    @Test
    @DisplayName("timeoutConnections emits ERRNO ETIMEDOUT on CONNECT")
    void timeoutConnections() {
      chaos.timeoutConnections(container, "db:5432", Duration.ofSeconds(3));
      verify(transport)
          .addRule(eq(container), anyString(), contains("connect:ERRNO:ETIMEDOUT:1.0"));
    }

    @Test
    @DisplayName("slowClose emits LATENCY on SHUTDOWN")
    void slowClose() {
      chaos.slowClose(container, "db:5432", Duration.ofMillis(500));
      verify(transport).addRule(eq(container), anyString(), contains("shutdown:LATENCY:500"));
    }

    @Test
    @DisplayName("limitBandwidth throws ChaosUnsupportedOperationException")
    void limitBandwidth() {
      assertThatThrownBy(() -> chaos.limitBandwidth(container, "db:5432", 1024))
          .isInstanceOf(ChaosUnsupportedOperationException.class)
          .hasMessageContaining("bandwidth");
    }

    @Test
    @DisplayName("addLatency rejects malformed target")
    void badTarget() {
      assertThatThrownBy(() -> chaos.addLatency(container, "no-port", Duration.ofMillis(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("removeToxic")
  class RemoveToxic {

    @Test
    @DisplayName("removes only entries tagged with the matching toxicName for the target")
    void filtersByTagAndTarget() {
      chaos.addLatency(container, "db:5432", Duration.ofMillis(100)); // tag=latency, 2 rules
      chaos.rejectConnections(container, "db:5432"); // tag=down, 1 rule

      chaos.removeToxic(container, "db:5432", "latency");

      // 2 latency rules removed, 1 down rule untouched
      verify(transport, times(2)).removeRules(eq(container), anyString());
    }

    @Test
    @DisplayName("does not remove advanced-API rules (which are untagged)")
    void leavesAdvancedRules() {
      final NetRule r =
          NetRule.errno(Endpoint.tcp4("db", 5432), NetOperation.CONNECT, Errno.EPIPE, 1.0);
      chaos.apply(container, r);
      chaos.removeToxic(container, "db:5432", "latency");
      verify(transport, never()).removeRules(any(), anyString());
    }
  }

  @Nested
  @DisplayName("convenience verbs")
  class Convenience {

    @Test
    @DisplayName("failDnsResolve emits dns:// selector")
    void failDns() {
      chaos.failDnsResolve(container, "db.internal", Errno.EHOSTUNREACH, 1.0);
      verify(transport, atLeastOnce())
          .addRule(eq(container), anyString(), contains("dns://db.internal:connect:ERRNO:EHOSTUNREACH"));
    }

    @Test
    @DisplayName("corruptRecv emits CORRUPT effect on RECV")
    void corruptRecv() {
      chaos.corruptRecv(container, Endpoint.tcp4("db", 5432), 0.5, 1.0);
      verify(transport, atLeastOnce())
          .addRule(eq(container), anyString(), contains("recv:CORRUPT:0.5"));
    }

    @Test
    @DisplayName("forcePollTimeout emits TIMEOUT on POLL")
    void pollTimeout() {
      chaos.forcePollTimeout(container, Endpoint.tcp4("db", 5432), Duration.ofSeconds(2), 1.0);
      verify(transport, atLeastOnce())
          .addRule(eq(container), anyString(), contains("poll:TIMEOUT:2000"));
    }

    @Test
    @DisplayName("refuseUnix uses unix:// selector")
    void refuseUnix() {
      chaos.refuseUnix(container, "/var/run/redis.sock", Errno.ECONNREFUSED, 1.0);
      verify(transport, atLeastOnce())
          .addRule(
              eq(container), anyString(), contains("unix:///var/run/redis.sock:connect:ERRNO:ECONNREFUSED"));
    }

    @Test
    @DisplayName("exhaustFds uses wildcard endpoint and EMFILE")
    void exhaustFds() {
      chaos.exhaustFds(container, 1.0);
      verify(transport, atLeastOnce())
          .addRule(eq(container), anyString(), contains("*:socket:ERRNO:EMFILE:1.0"));
    }
  }
}
