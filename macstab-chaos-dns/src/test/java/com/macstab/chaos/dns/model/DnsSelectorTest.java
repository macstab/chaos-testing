/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DnsSelector (unit)")
class DnsSelectorTest {

  @Nested
  @DisplayName("Forward.ExactHost")
  class ExactHostCases {

    @Test
    @DisplayName("renders as dns://<name>")
    void renders() {
      assertThat(DnsSelector.host("api.example.com").toSelector())
          .isEqualTo("dns://api.example.com");
    }

    @Test
    @DisplayName("kind is FORWARD")
    void kind() {
      assertThat(DnsSelector.host("h").kind()).isEqualTo(DnsSelector.Kind.FORWARD);
    }

    @Test
    @DisplayName("rejects null / blank / newline / colon / whitespace / too-long")
    void rejects() {
      assertThatThrownBy(() -> DnsSelector.host(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> DnsSelector.host(" "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blank");
      assertThatThrownBy(() -> DnsSelector.host("foo\nbar"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("newline");
      assertThatThrownBy(() -> DnsSelector.host("foo:bar"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(":");
      assertThatThrownBy(() -> DnsSelector.host("foo bar"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("whitespace");
      assertThatThrownBy(() -> DnsSelector.host("a".repeat(DnsSelector.MAX_HOSTNAME_LENGTH + 1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Forward.SuffixWildcard")
  class SuffixWildcardCases {

    @Test
    @DisplayName("renders as dns://*.<suffix>")
    void renders() {
      assertThat(DnsSelector.suffix("example.com").toSelector()).isEqualTo("dns://*.example.com");
    }

    @Test
    @DisplayName("rejects suffix with leading dot or asterisk")
    void rejectsLeadingPunctuation() {
      assertThatThrownBy(() -> DnsSelector.suffix(".example.com"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> DnsSelector.suffix("*.example.com"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Forward.AllForward")
  class AllForwardCases {

    @Test
    @DisplayName("renders as dns://* and is singleton")
    void rendersAndSingleton() {
      assertThat(DnsSelector.anyForward().toSelector()).isEqualTo("dns://*");
      assertThat(DnsSelector.anyForward()).isSameAs(DnsSelector.Forward.AllForward.ANY);
      assertThat(DnsSelector.anyForward().kind()).isEqualTo(DnsSelector.Kind.FORWARD);
    }
  }

  @Nested
  @DisplayName("Reverse.Ipv4")
  class Ipv4Cases {

    @Test
    @DisplayName("renders as rdns://<addr>")
    void renders() {
      assertThat(DnsSelector.reverseIpv4("1.2.3.4").toSelector()).isEqualTo("rdns://1.2.3.4");
    }

    @Test
    @DisplayName("kind is REVERSE")
    void kind() {
      assertThat(DnsSelector.reverseIpv4("1.2.3.4").kind()).isEqualTo(DnsSelector.Kind.REVERSE);
    }

    @Test
    @DisplayName("rejects hostnames and IPv6 literals")
    void rejectsNonIpv4() {
      assertThatThrownBy(() -> DnsSelector.reverseIpv4("example.com"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> DnsSelector.reverseIpv4("::1"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Reverse.Ipv6")
  class Ipv6Cases {

    @Test
    @DisplayName("renders as rdns://[<addr>] with brackets")
    void renders() {
      assertThat(DnsSelector.reverseIpv6("::1").toSelector()).isEqualTo("rdns://[::1]");
    }

    @Test
    @DisplayName("rejects bracketed input (renderer adds brackets)")
    void rejectsBracketed() {
      assertThatThrownBy(() -> DnsSelector.reverseIpv6("[::1]"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unbracketed");
    }

    @Test
    @DisplayName("rejects IPv4 literals")
    void rejectsIpv4() {
      assertThatThrownBy(() -> DnsSelector.reverseIpv6("1.2.3.4"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Reverse.AllReverse")
  class AllReverseCases {
    @Test
    @DisplayName("renders as rdns://* and is singleton")
    void renders() {
      assertThat(DnsSelector.anyReverse().toSelector()).isEqualTo("rdns://*");
      assertThat(DnsSelector.anyReverse()).isSameAs(DnsSelector.Reverse.AllReverse.ANY);
      assertThat(DnsSelector.anyReverse().kind()).isEqualTo(DnsSelector.Kind.REVERSE);
    }
  }

  @Nested
  @DisplayName("Wildcard")
  class WildcardCases {
    @Test
    @DisplayName("renders as '*' and kind is ANY")
    void renders() {
      assertThat(DnsSelector.wildcard().toSelector()).isEqualTo("*");
      assertThat(DnsSelector.wildcard().kind()).isEqualTo(DnsSelector.Kind.ANY);
    }
  }
}
