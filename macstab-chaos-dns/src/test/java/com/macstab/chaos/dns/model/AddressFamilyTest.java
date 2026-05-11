/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AddressFamily (unit)")
class AddressFamilyTest {

  @Test
  @DisplayName("wireValue matches Linux AF_* constants")
  void wireValues() {
    assertThat(AddressFamily.INET.wireValue()).isEqualTo(2);
    assertThat(AddressFamily.INET6.wireValue()).isEqualTo(10);
  }
}
