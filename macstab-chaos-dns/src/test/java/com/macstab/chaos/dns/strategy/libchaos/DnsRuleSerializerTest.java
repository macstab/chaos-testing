/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.dns.model.AddressFamily;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

@DisplayName("DnsRuleSerializer (unit)")
class DnsRuleSerializerTest {

  private static InetAddress addr(final String s) throws UnknownHostException {
    return InetAddress.getByName(s);
  }

  @Test
  @DisplayName("forward EAI on exact host")
  void forwardEai() {
    assertThat(
            DnsRuleSerializer.serialize(
                DnsRule.eai(DnsSelector.host("api.example.com"), EaiErrno.EAI_FAIL)))
        .isEqualTo("dns://api.example.com:EAI_FAIL");
  }

  @Test
  @DisplayName("latency on suffix wildcard")
  void suffixLatency() {
    assertThat(
            DnsRuleSerializer.serialize(
                DnsRule.latency(DnsSelector.suffix("example.com"), Duration.ofMillis(200))))
        .isEqualTo("dns://*.example.com:LATENCY:200");
  }

  @Test
  @DisplayName("reverse EAI on IPv4 selector")
  void reverseIpv4() {
    assertThat(
            DnsRuleSerializer.serialize(
                DnsRule.eai(DnsSelector.reverseIpv4("1.2.3.4"), EaiErrno.EAI_NONAME)))
        .isEqualTo("rdns://1.2.3.4:EAI_NONAME");
  }

  @Test
  @DisplayName("reverse REWRITE on IPv6 selector — brackets in selector, value verbatim")
  void reverseIpv6Rewrite() {
    assertThat(
            DnsRuleSerializer.serialize(
                DnsRule.rewrite(DnsSelector.reverseIpv6("::1"), "loopback")))
        .isEqualTo("rdns://[::1]:REWRITE:loopback");
  }

  @Test
  @DisplayName("OVERRIDE with mixed v4/v6 list — IPv6 bracketed, comma-separated")
  void overrideMixedList() throws Exception {
    assertThat(
            DnsRuleSerializer.serialize(
                DnsRule.override(DnsSelector.host("h"), List.of(addr("1.2.3.4"), addr("::1")))))
        .isEqualTo("dns://h:OVERRIDE:1.2.3.4,[::1]");
  }

  @Test
  @DisplayName("FILTER_FAMILY uses inet4/inet6 string tokens")
  void filterFamily() {
    assertThat(
            DnsRuleSerializer.serialize(
                DnsRule.filterFamily(DnsSelector.host("h"), AddressFamily.INET6)))
        .isEqualTo("dns://h:FILTER_FAMILY:inet6");
  }

  @Test
  @DisplayName("LIMIT renders integer")
  void limit() {
    assertThat(DnsRuleSerializer.serialize(DnsRule.limit(DnsSelector.host("h"), 3)))
        .isEqualTo("dns://h:LIMIT:3");
  }

  @Test
  @DisplayName("SHUFFLE is the singleton token")
  void shuffle() {
    assertThat(DnsRuleSerializer.serialize(DnsRule.shuffle(DnsSelector.host("h"))))
        .isEqualTo("dns://h:SHUFFLE");
  }

  @Test
  @DisplayName("wildcard EAI")
  void wildcardEai() {
    assertThat(DnsRuleSerializer.serialize(DnsRule.eai(DnsSelector.wildcard(), EaiErrno.EAI_AGAIN)))
        .isEqualTo("*:EAI_AGAIN");
  }

  @Test
  @DisplayName("null rule rejected")
  void nullRule() {
    assertThatThrownBy(() -> DnsRuleSerializer.serialize(null))
        .isInstanceOf(NullPointerException.class);
  }
}
