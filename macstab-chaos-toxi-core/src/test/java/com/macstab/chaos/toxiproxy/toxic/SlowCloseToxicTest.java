/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SlowCloseToxic")
class SlowCloseToxicTest {

  @Nested
  @DisplayName("Builder defaults")
  class DefaultsTests {

    @Test
    @DisplayName("delayMs defaults to 0")
    void defaultDelayMs() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").build();

      assertThat(toxic.delayMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("toxicity defaults to 1.0")
    void defaultToxicity() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Accessors")
  class AccessorTests {

    @Test
    @DisplayName("name() returns configured name")
    void name() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("slow-close").build();

      assertThat(toxic.name()).isEqualTo("slow-close");
    }

    @Test
    @DisplayName("type() returns 'slow_close'")
    void type() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").build();

      assertThat(toxic.type()).isEqualTo("slow_close");
    }

    @Test
    @DisplayName("delayMs() returns configured value")
    void delayMs() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").delayMs(5000).build();

      assertThat(toxic.delayMs()).isEqualTo(5000);
    }

    @Test
    @DisplayName("toxicity() returns configured value")
    void toxicity() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").toxicity(0.2).build();

      assertThat(toxic.toxicity()).isEqualTo(0.2);
    }
  }

  @Nested
  @DisplayName("toJson()")
  class ToJsonTests {

    @Test
    @DisplayName("serializes delay field")
    void serializesDelay() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").delayMs(5000).build();

      assertThat(toxic.toJson()).isEqualTo("{\"delay\":5000}");
    }

    @Test
    @DisplayName("serializes zero delay (default)")
    void serializesZeroDelay() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").build();

      assertThat(toxic.toJson()).isEqualTo("{\"delay\":0}");
    }
  }

  @Nested
  @DisplayName("Validation — name")
  class NameValidationTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullNameThrows() {
      assertThatThrownBy(() -> SlowCloseToxic.builder().name(null).build())
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("omitted name throws NullPointerException")
    void omittedNameThrows() {
      assertThatThrownBy(() -> SlowCloseToxic.builder().delayMs(100).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Validation — delayMs")
  class DelayValidationTests {

    @Test
    @DisplayName("negative delayMs throws IllegalArgumentException")
    void negativeDelayThrows() {
      assertThatThrownBy(() -> SlowCloseToxic.builder().name("sc").delayMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("delayMs");
    }

    @Test
    @DisplayName("delayMs=0 is valid (no delay, boundary)")
    void zeroDelayIsValid() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").delayMs(0).build();

      assertThat(toxic.delayMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("large delayMs is valid")
    void largeDelay() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").delayMs(60_000).build();

      assertThat(toxic.delayMs()).isEqualTo(60_000);
    }
  }

  @Nested
  @DisplayName("Validation — toxicity")
  class ToxicityValidationTests {

    @Test
    @DisplayName("toxicity below 0.0 throws IllegalArgumentException")
    void toxicityBelowZeroThrows() {
      assertThatThrownBy(() -> SlowCloseToxic.builder().name("sc").toxicity(-0.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity above 1.0 throws IllegalArgumentException")
    void toxicityAboveOneThrows() {
      assertThatThrownBy(() -> SlowCloseToxic.builder().name("sc").toxicity(1.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity=0.0 is valid (boundary)")
    void toxicityZeroIsValid() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").toxicity(0.0).build();

      assertThat(toxic.toxicity()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toxicity=1.0 is valid (boundary)")
    void toxicityOneIsValid() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").toxicity(1.0).build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Real-world scenarios")
  class ScenarioTests {

    @Test
    @DisplayName("pool exhaustion: 5s close delay on all connections")
    void poolExhaustion() {
      SlowCloseToxic toxic =
          SlowCloseToxic.builder().name("slow-close").delayMs(5000).toxicity(1.0).build();

      assertThat(toxic.delayMs()).isEqualTo(5000);
      assertThat(toxic.toxicity()).isEqualTo(1.0);
      assertThat(toxic.toJson()).isEqualTo("{\"delay\":5000}");
    }

    @Test
    @DisplayName("partial slow close: 2s delay on 20% of connections")
    void partialSlowClose() {
      SlowCloseToxic toxic =
          SlowCloseToxic.builder().name("partial-sc").delayMs(2000).toxicity(0.2).build();

      assertThat(toxic.delayMs()).isEqualTo(2000);
      assertThat(toxic.toxicity()).isEqualTo(0.2);
    }
  }

  @Nested
  @DisplayName("ToxicConfig interface")
  class InterfaceTests {

    @Test
    @DisplayName("implements ToxicConfig")
    void implementsToxicConfig() {
      SlowCloseToxic toxic = SlowCloseToxic.builder().name("sc").build();

      assertThat(toxic).isInstanceOf(ToxicConfig.class);
    }
  }
}
