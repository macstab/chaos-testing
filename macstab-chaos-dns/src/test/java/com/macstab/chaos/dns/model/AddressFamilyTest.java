/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AddressFamily (unit)")
class AddressFamilyTest {

  @Test
  @DisplayName("wireForm matches libchaos-dns family-filter tokens")
  void wireValues() {
    assertThat(AddressFamily.INET.wireForm()).isEqualTo("inet4");
    assertThat(AddressFamily.INET6.wireForm()).isEqualTo("inet6");
  }
}
