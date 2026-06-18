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

@DisplayName("DnsRule (unit)")
class DnsRuleTest {

  private static InetAddress addr(final String s) throws UnknownHostException {
    return InetAddress.getByName(s);
  }

  @Nested
  @DisplayName("null checks")
  class NullChecks {
    @Test
    @DisplayName("null selector / effect rejected")
    void nulls() {
      assertThatThrownBy(() -> new DnsRule(null, Effect.eai(EaiErrno.EAI_FAIL)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new DnsRule(DnsSelector.host("h"), null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("selector × effect compatibility matrix")
  class CompatibilityMatrix {

    @Test
    @DisplayName("EAI/LATENCY/REWRITE/SERVICE work on every selector kind")
    void universalEffectsAllSelectors() throws Exception {
      // forward
      new DnsRule(DnsSelector.host("h"), Effect.eai(EaiErrno.EAI_FAIL));
      new DnsRule(DnsSelector.host("h"), Effect.latency(Duration.ofMillis(50)));
      new DnsRule(DnsSelector.host("h"), Effect.rewrite("other"));
      new DnsRule(DnsSelector.host("h"), Effect.service("redis"));
      // reverse
      new DnsRule(DnsSelector.reverseIpv4("1.2.3.4"), Effect.eai(EaiErrno.EAI_NONAME));
      new DnsRule(DnsSelector.reverseIpv4("1.2.3.4"), Effect.latency(Duration.ofMillis(50)));
      new DnsRule(DnsSelector.reverseIpv4("1.2.3.4"), Effect.rewrite("box1"));
      new DnsRule(DnsSelector.reverseIpv4("1.2.3.4"), Effect.service("ssh"));
      // wildcard
      new DnsRule(DnsSelector.wildcard(), Effect.eai(EaiErrno.EAI_AGAIN));
    }

    @Test
    @DisplayName("OVERRIDE on forward OK")
    void overrideForwardOk() throws Exception {
      new DnsRule(DnsSelector.host("h"), Effect.override(List.of(addr("1.2.3.4"))));
    }

    @Test
    @DisplayName("OVERRIDE on reverse rejected")
    void overrideReverseRejected() throws Exception {
      assertThatThrownBy(
              () ->
                  new DnsRule(
                      DnsSelector.reverseIpv4("1.2.3.4"),
                      Effect.override(List.of(addr("1.2.3.4")))))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OVERRIDE on wildcard rejected")
    void overrideWildcardRejected() throws Exception {
      assertThatThrownBy(
              () -> new DnsRule(DnsSelector.wildcard(), Effect.override(List.of(addr("1.2.3.4")))))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FILTER_FAMILY / LIMIT / SHUFFLE rejected on non-forward selectors")
    void forwardOnlyTransforms() {
      assertThatThrownBy(
              () ->
                  new DnsRule(
                      DnsSelector.reverseIpv4("1.2.3.4"), Effect.filterFamily(AddressFamily.INET)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new DnsRule(DnsSelector.wildcard(), Effect.limit(3)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new DnsRule(DnsSelector.anyReverse(), Effect.shuffle()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("static factories")
  class Factories {
    @Test
    @DisplayName("convenience factories build the expected variant")
    void factories() throws Exception {
      assertThat(DnsRule.eai(DnsSelector.host("h"), EaiErrno.EAI_FAIL).effect())
          .isInstanceOf(Effect.EaiFault.class);
      assertThat(DnsRule.latency(DnsSelector.host("h"), Duration.ofMillis(50)).effect())
          .isInstanceOf(Effect.Latency.class);
      assertThat(DnsRule.rewrite(DnsSelector.host("h"), "other").effect())
          .isInstanceOf(Effect.Rewrite.class);
      assertThat(DnsRule.service(DnsSelector.host("h"), "redis").effect())
          .isInstanceOf(Effect.Service.class);
      assertThat(DnsRule.override(DnsSelector.host("h"), List.of(addr("1.2.3.4"))).effect())
          .isInstanceOf(Effect.OverrideAnswer.class);
      assertThat(DnsRule.filterFamily(DnsSelector.host("h"), AddressFamily.INET).effect())
          .isInstanceOf(Effect.FilterFamily.class);
      assertThat(DnsRule.limit(DnsSelector.host("h"), 3).effect()).isInstanceOf(Effect.Limit.class);
      assertThat(DnsRule.shuffle(DnsSelector.host("h")).effect())
          .isInstanceOf(Effect.Shuffle.class);
    }
  }
}
