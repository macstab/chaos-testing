/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.AddressFamily;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

@DisplayName("LibchaosDnsChaos (unit)")
class LibchaosDnsChaosUnitTest {

  private LibchaosTransport transport;
  private GenericContainer<?> container;
  private LibchaosDnsChaos chaos;

  @BeforeEach
  void setUp() {
    transport = mock(LibchaosTransport.class);
    container = mock(GenericContainer.class);
    chaos = new LibchaosDnsChaos(transport);
    when(transport.isActive(container)).thenReturn(true);
  }

  @Nested
  @DisplayName("supports")
  class Supports {
    @Test
    @DisplayName("delegates to transport.isActive")
    void delegates() {
      assertThat(chaos.supports(container)).isTrue();
      when(transport.isActive(container)).thenReturn(false);
      assertThat(chaos.supports(container)).isFalse();
    }

    @Test
    @DisplayName("returns false on probe failure")
    void probeFails() {
      when(transport.isActive(container)).thenThrow(new RuntimeException("docker down"));
      assertThat(chaos.supports(container)).isFalse();
    }
  }

  @Nested
  @DisplayName("requirePrepared gate")
  class RequirePrepared {
    @BeforeEach
    void setUnprepared() {
      when(transport.isActive(container)).thenReturn(false);
    }

    @Test
    @DisplayName("blockResolution throws")
    void blockResolution() {
      assertThatThrownBy(() -> chaos.blockResolution(container, "example.com"))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("dns")
          .hasMessageContaining("@SyscallLevelChaos");
    }

    @Test
    @DisplayName("delayResolution throws")
    void delayResolution() {
      assertThatThrownBy(() -> chaos.delayResolution(container, Duration.ofMillis(50)))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    @DisplayName("apply / applyAll / remove / removeAll throw")
    void advancedGate() {
      assertThatThrownBy(
              () -> chaos.apply(container, DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL)))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.applyAll(container, List.of()))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.remove(container, new RuleHandle("r1")))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.removeAll(container))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    @DisplayName("convenience verb throws")
    void convenience() {
      assertThatThrownBy(() -> chaos.failResolution(container, "h", EaiErrno.EAI_FAIL))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }
  }

  @Nested
  @DisplayName("portable verbs")
  class PortableVerbs {
    @Test
    @DisplayName("blockResolution writes EAI_FAIL on host selector")
    void blockResolution() {
      chaos.blockResolution(container, "example.com");
      verify(transport).addRule(eq(container), anyString(), contains("dns://example.com:EAI_FAIL"));
    }

    @Test
    @DisplayName("delayResolution writes wildcard LATENCY")
    void delayResolution() {
      chaos.delayResolution(container, Duration.ofMillis(150));
      verify(transport).addRule(eq(container), anyString(), contains("dns://*:LATENCY:150"));
    }
  }

  @Nested
  @DisplayName("forward convenience verbs")
  class ForwardConvenience {
    @Test
    @DisplayName("failResolution → dns://<host>:EAI_*")
    void failResolution() {
      chaos.failResolution(container, "example.com", EaiErrno.EAI_NONAME);
      verify(transport)
          .addRule(eq(container), anyString(), contains("dns://example.com:EAI_NONAME"));
    }

    @Test
    @DisplayName("slowResolution → dns://<host>:LATENCY:<ms>")
    void slowResolution() {
      chaos.slowResolution(container, "example.com", Duration.ofMillis(50));
      verify(transport)
          .addRule(eq(container), anyString(), contains("dns://example.com:LATENCY:50"));
    }

    @Test
    @DisplayName("rewriteHost → dns://<from>:REWRITE:<to>")
    void rewriteHost() {
      chaos.rewriteHost(container, "example.com", "decoy.org");
      verify(transport)
          .addRule(eq(container), anyString(), contains("dns://example.com:REWRITE:decoy.org"));
    }

    @Test
    @DisplayName("overrideAnswer with IPv4 + IPv6 list — brackets, comma")
    void overrideAnswer() throws Exception {
      chaos.overrideAnswer(
          container,
          "example.com",
          List.of(InetAddress.getByName("1.2.3.4"), InetAddress.getByName("::1")));
      verify(transport)
          .addRule(
              eq(container), anyString(), contains("dns://example.com:OVERRIDE:1.2.3.4,[::1]"));
    }

    @Test
    @DisplayName("filterFamily renders inet4/inet6 string token")
    void filterFamily() {
      chaos.filterFamily(container, "example.com", AddressFamily.INET);
      verify(transport)
          .addRule(eq(container), anyString(), contains("dns://example.com:FILTER_FAMILY:inet4"));
    }

    @Test
    @DisplayName("limitAnswers renders LIMIT:N")
    void limit() {
      chaos.limitAnswers(container, "example.com", 1);
      verify(transport).addRule(eq(container), anyString(), contains("dns://example.com:LIMIT:1"));
    }

    @Test
    @DisplayName("shuffleAnswers renders SHUFFLE singleton")
    void shuffle() {
      chaos.shuffleAnswers(container, "example.com");
      verify(transport).addRule(eq(container), anyString(), contains("dns://example.com:SHUFFLE"));
    }

    @Test
    @DisplayName("rewriteService → dns://<host>:SERVICE:<to>")
    void rewriteService() {
      chaos.rewriteService(container, "example.com", "redis");
      verify(transport)
          .addRule(eq(container), anyString(), contains("dns://example.com:SERVICE:redis"));
    }
  }

  @Nested
  @DisplayName("reverse convenience verbs")
  class ReverseConvenience {
    @Test
    @DisplayName("failReverse on IPv4 → rdns://<addr>:EAI_*")
    void failReverseV4() {
      chaos.failReverse(container, "1.2.3.4", EaiErrno.EAI_NONAME);
      verify(transport).addRule(eq(container), anyString(), contains("rdns://1.2.3.4:EAI_NONAME"));
    }

    @Test
    @DisplayName("failReverse on IPv6 → rdns://[<addr>]:EAI_*")
    void failReverseV6() {
      chaos.failReverse(container, "::1", EaiErrno.EAI_FAIL);
      verify(transport).addRule(eq(container), anyString(), contains("rdns://[::1]:EAI_FAIL"));
    }

    @Test
    @DisplayName("slowReverse adds LATENCY")
    void slowReverse() {
      chaos.slowReverse(container, "1.2.3.4", Duration.ofMillis(100));
      verify(transport).addRule(eq(container), anyString(), contains("rdns://1.2.3.4:LATENCY:100"));
    }

    @Test
    @DisplayName("rewriteReverseHost adds REWRITE")
    void rewriteReverse() {
      chaos.rewriteReverseHost(container, "1.2.3.4", "box.local");
      verify(transport)
          .addRule(eq(container), anyString(), contains("rdns://1.2.3.4:REWRITE:box.local"));
    }

    @Test
    @DisplayName("rewriteReverseService → rdns://<addr>:SERVICE:<to>")
    void rewriteReverseService() {
      chaos.rewriteReverseService(container, "1.2.3.4", "ssh");
      verify(transport).addRule(eq(container), anyString(), contains("rdns://1.2.3.4:SERVICE:ssh"));
    }
  }

  @Nested
  @DisplayName("raw-selector escape hatch")
  class RawEscapeHatch {
    @Test
    @DisplayName("eai(selector, errno) — applies arbitrary selector + EAI code")
    void eaiAnyForward() {
      chaos.eai(container, DnsSelector.anyForward(), EaiErrno.EAI_AGAIN);
      verify(transport).addRule(eq(container), anyString(), contains("dns://*:EAI_AGAIN"));
    }

    @Test
    @DisplayName("eai with wildcard selector")
    void eaiWildcard() {
      chaos.eai(container, DnsSelector.wildcard(), EaiErrno.EAI_SYSTEM);
      verify(transport).addRule(eq(container), anyString(), contains("*:EAI_SYSTEM"));
    }
  }

  @Nested
  @DisplayName("apply / remove plumbing")
  class ApplyRemove {
    @Test
    @DisplayName("apply mints a fresh handle and emits one addRule")
    void apply() {
      final RuleHandle h =
          chaos.apply(container, DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL));
      assertThat(h.owner()).matches("r[0-9]+");
      verify(transport).addRule(eq(container), eq(h.owner()), anyString());
    }

    @Test
    @DisplayName("applyAll empty is no-op")
    void applyAllEmpty() {
      assertThat(chaos.applyAll(container, List.of())).isEmpty();
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("applyAll N → N addRule calls")
    void applyAllN() {
      chaos.applyAll(
          container,
          List.of(
              DnsRule.eai(DnsSelector.host("a"), EaiErrno.EAI_FAIL),
              DnsRule.eai(DnsSelector.host("b"), EaiErrno.EAI_FAIL)));
      verify(transport, times(2)).addRule(eq(container), anyString(), anyString());
    }

    @Test
    @DisplayName("applyAll with a null rule in the list is rejected")
    void applyAllNullElement() {
      final java.util.ArrayList<DnsRule> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> chaos.applyAll(container, withNull))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("remove of applied handle removes from transport")
    void remove() {
      final RuleHandle h =
          chaos.apply(container, DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL));
      chaos.remove(container, h);
      verify(transport).removeRules(container, h.owner());
    }

    @Test
    @DisplayName("remove of unknown handle is silent")
    void removeUnknown() {
      chaos.remove(container, new RuleHandle("r_missing"));
      verify(transport, never()).removeRules(any(), anyString());
    }

    @Test
    @DisplayName("removeAll wipes every applied rule")
    void removeAll() {
      final RuleHandle h1 =
          chaos.apply(container, DnsRule.eai(DnsSelector.host("a"), EaiErrno.EAI_FAIL));
      final RuleHandle h2 =
          chaos.apply(container, DnsRule.eai(DnsSelector.host("b"), EaiErrno.EAI_FAIL));
      chaos.removeAll(container);
      verify(transport).removeRules(container, h1.owner());
      verify(transport).removeRules(container, h2.owner());
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {
    @Test
    @DisplayName("reset on unprepared container is silent")
    void resetUnprepared() {
      when(transport.isActive(container)).thenReturn(false);
      chaos.reset(container);
      verify(transport, never()).removeRules(any(), anyString());
    }

    @Test
    @DisplayName("reset on prepared container removes all rules")
    void resetPrepared() {
      final RuleHandle h =
          chaos.apply(container, DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL));
      chaos.reset(container);
      verify(transport).removeRules(container, h.owner());
    }

    @Test
    @DisplayName("installTools no-op")
    void installToolsNoOp() {
      chaos.installTools(container);
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("isSupported true")
    void isSupported() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }
}
