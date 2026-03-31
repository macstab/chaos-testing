/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BandwidthToxic")
class BandwidthToxicTest {

  @Nested
  @DisplayName("Builder defaults")
  class DefaultsTests {

    @Test
    @DisplayName("rateKbps defaults to 100")
    void defaultRateKbps() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").build();

      assertThat(toxic.rateKbps()).isEqualTo(100);
    }

    @Test
    @DisplayName("toxicity defaults to 1.0")
    void defaultToxicity() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Accessors")
  class AccessorTests {

    @Test
    @DisplayName("name() returns configured name")
    void name() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("mobile-3g").rateKbps(100).build();

      assertThat(toxic.name()).isEqualTo("mobile-3g");
    }

    @Test
    @DisplayName("type() returns 'bandwidth'")
    void type() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").build();

      assertThat(toxic.type()).isEqualTo("bandwidth");
    }

    @Test
    @DisplayName("rateKbps() returns configured rate")
    void rateKbps() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").rateKbps(1000).build();

      assertThat(toxic.rateKbps()).isEqualTo(1000);
    }

    @Test
    @DisplayName("toxicity() returns configured toxicity")
    void toxicity() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").toxicity(0.5).build();

      assertThat(toxic.toxicity()).isEqualTo(0.5);
    }
  }

  @Nested
  @DisplayName("toJson()")
  class ToJsonTests {

    @Test
    @DisplayName("serializes rate field")
    void serializesRate() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").rateKbps(250).build();

      assertThat(toxic.toJson()).isEqualTo("{\"rate\":250}");
    }

    @Test
    @DisplayName("serializes default rate")
    void serializesDefaultRate() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").build();

      assertThat(toxic.toJson()).isEqualTo("{\"rate\":100}");
    }
  }

  @Nested
  @DisplayName("Validation — name")
  class NameValidationTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullNameThrows() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name(null).build())
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("omitted name throws NullPointerException")
    void omittedNameThrows() {
      assertThatThrownBy(() -> BandwidthToxic.builder().rateKbps(100).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Validation — rateKbps")
  class RateValidationTests {

    @Test
    @DisplayName("rateKbps=0 throws IllegalArgumentException")
    void zeroRateThrows() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("bw").rateKbps(0).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rateKbps");
    }

    @Test
    @DisplayName("negative rateKbps throws IllegalArgumentException")
    void negativeRateThrows() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("bw").rateKbps(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rateKbps");
    }

    @Test
    @DisplayName("rateKbps=1 is valid (boundary)")
    void rateKbpsOne() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").rateKbps(1).build();

      assertThat(toxic.rateKbps()).isEqualTo(1);
    }

    @Test
    @DisplayName("large rateKbps is valid")
    void largeRate() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").rateKbps(100_000).build();

      assertThat(toxic.rateKbps()).isEqualTo(100_000);
    }
  }

  @Nested
  @DisplayName("Validation — toxicity")
  class ToxicityValidationTests {

    @Test
    @DisplayName("toxicity below 0.0 throws IllegalArgumentException")
    void toxicityBelowZeroThrows() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("bw").toxicity(-0.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity above 1.0 throws IllegalArgumentException")
    void toxicityAboveOneThrows() {
      assertThatThrownBy(() -> BandwidthToxic.builder().name("bw").toxicity(1.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity=0.0 is valid (boundary)")
    void toxicityZeroIsValid() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").toxicity(0.0).build();

      assertThat(toxic.toxicity()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toxicity=1.0 is valid (boundary)")
    void toxicityOneIsValid() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").toxicity(1.0).build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("toxicity=0.3 is valid (partial)")
    void partialToxicityIsValid() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").toxicity(0.3).build();

      assertThat(toxic.toxicity()).isEqualTo(0.3);
    }
  }

  @Nested
  @DisplayName("ToxicConfig interface")
  class InterfaceTests {

    @Test
    @DisplayName("implements ToxicConfig")
    void implementsToxicConfig() {
      BandwidthToxic toxic = BandwidthToxic.builder().name("bw").build();

      assertThat(toxic).isInstanceOf(ToxicConfig.class);
    }
  }
}
