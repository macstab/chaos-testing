/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DownToxic")
class DownToxicTest {

  @Nested
  @DisplayName("Builder defaults")
  class DefaultsTests {

    @Test
    @DisplayName("name defaults to 'down'")
    void defaultName() {
      DownToxic toxic = DownToxic.builder().build();

      assertThat(toxic.name()).isEqualTo("down");
    }

    @Test
    @DisplayName("toxicity defaults to 1.0")
    void defaultToxicity() {
      DownToxic toxic = DownToxic.builder().build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Accessors")
  class AccessorTests {

    @Test
    @DisplayName("name() returns configured name")
    void name() {
      DownToxic toxic = DownToxic.builder().name("upstream-down").build();

      assertThat(toxic.name()).isEqualTo("upstream-down");
    }

    @Test
    @DisplayName("type() returns 'down'")
    void type() {
      DownToxic toxic = DownToxic.builder().build();

      assertThat(toxic.type()).isEqualTo("down");
    }

    @Test
    @DisplayName("toxicity() returns configured value")
    void toxicity() {
      DownToxic toxic = DownToxic.builder().toxicity(0.3).build();

      assertThat(toxic.toxicity()).isEqualTo(0.3);
    }
  }

  @Nested
  @DisplayName("toJson()")
  class ToJsonTests {

    @Test
    @DisplayName("serializes full down toxic JSON with name, type, toxicity and empty attributes")
    void serializesFullJson() {
      DownToxic toxic = DownToxic.builder().name("my-down").toxicity(1.0).build();

      assertThat(toxic.toJson())
          .isEqualTo("{\"name\":\"my-down\",\"type\":\"down\",\"toxicity\":1.00,\"attributes\":{}}");
    }

    @Test
    @DisplayName("serializes default toxic JSON")
    void serializesDefaultJson() {
      DownToxic toxic = DownToxic.builder().build();

      assertThat(toxic.toJson())
          .isEqualTo("{\"name\":\"down\",\"type\":\"down\",\"toxicity\":1.00,\"attributes\":{}}");
    }

    @Test
    @DisplayName("serializes partial toxicity correctly")
    void serializesPartialToxicity() {
      DownToxic toxic = DownToxic.builder().name("down").toxicity(0.3).build();

      assertThat(toxic.toJson())
          .isEqualTo("{\"name\":\"down\",\"type\":\"down\",\"toxicity\":0.30,\"attributes\":{}}");
    }
  }

  @Nested
  @DisplayName("Validation — name")
  class NameValidationTests {

    @Test
    @DisplayName("null name in builder throws NullPointerException")
    void nullNameInBuilderThrows() {
      assertThatThrownBy(() -> DownToxic.builder().name(null).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Validation — toxicity")
  class ToxicityValidationTests {

    @Test
    @DisplayName("toxicity below 0.0 throws IllegalArgumentException")
    void toxicityBelowZeroThrows() {
      assertThatThrownBy(() -> DownToxic.builder().toxicity(-0.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity above 1.0 throws IllegalArgumentException")
    void toxicityAboveOneThrows() {
      assertThatThrownBy(() -> DownToxic.builder().toxicity(1.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity=0.0 is valid (boundary)")
    void toxicityZeroIsValid() {
      DownToxic toxic = DownToxic.builder().toxicity(0.0).build();

      assertThat(toxic.toxicity()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toxicity=1.0 is valid (boundary)")
    void toxicityOneIsValid() {
      DownToxic toxic = DownToxic.builder().toxicity(1.0).build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("toxicity=0.05 is valid (5% packet loss)")
    void lowToxicityIsValid() {
      DownToxic toxic = DownToxic.builder().toxicity(0.05).build();

      assertThat(toxic.toxicity()).isEqualTo(0.05);
    }
  }

  @Nested
  @DisplayName("Real-world scenarios")
  class ScenarioTests {

    @Test
    @DisplayName("complete outage: all connections dropped")
    void completeOutage() {
      DownToxic toxic = DownToxic.builder().name("outage").toxicity(1.0).build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
      assertThat(toxic.type()).isEqualTo("down");
    }

    @Test
    @DisplayName("intermittent packet loss: 30% drop rate")
    void intermittentPacketLoss() {
      DownToxic toxic = DownToxic.builder().name("packet-loss").toxicity(0.3).build();

      assertThat(toxic.name()).isEqualTo("packet-loss");
      assertThat(toxic.toxicity()).isEqualTo(0.3);
    }
  }

  @Nested
  @DisplayName("ToxicConfig interface")
  class InterfaceTests {

    @Test
    @DisplayName("implements ToxicConfig")
    void implementsToxicConfig() {
      DownToxic toxic = DownToxic.builder().build();

      assertThat(toxic).isInstanceOf(ToxicConfig.class);
    }
  }
}
