/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LimitDataToxic")
class LimitDataToxicTest {

  @Nested
  @DisplayName("Builder defaults")
  class DefaultsTests {

    @Test
    @DisplayName("bytes defaults to 0 (instant close on connect)")
    void defaultBytes() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").build();

      assertThat(toxic.bytes()).isEqualTo(0L);
    }

    @Test
    @DisplayName("toxicity defaults to 1.0")
    void defaultToxicity() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Accessors")
  class AccessorTests {

    @Test
    @DisplayName("name() returns configured name")
    void name() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("truncate-response").build();

      assertThat(toxic.name()).isEqualTo("truncate-response");
    }

    @Test
    @DisplayName("type() returns 'limit_data'")
    void type() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").build();

      assertThat(toxic.type()).isEqualTo("limit_data");
    }

    @Test
    @DisplayName("bytes() returns configured value")
    void bytes() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").bytes(1024).build();

      assertThat(toxic.bytes()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("toxicity() returns configured value")
    void toxicity() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").toxicity(0.25).build();

      assertThat(toxic.toxicity()).isEqualTo(0.25);
    }
  }

  @Nested
  @DisplayName("toJson()")
  class ToJsonTests {

    @Test
    @DisplayName("serializes bytes field")
    void serializesBytes() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").bytes(1024).build();

      assertThat(toxic.toJson()).isEqualTo("{\"bytes\":1024}");
    }

    @Test
    @DisplayName("serializes zero bytes (instant close)")
    void serializesZeroBytes() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("instant-reset").bytes(0).build();

      assertThat(toxic.toJson()).isEqualTo("{\"bytes\":0}");
    }

    @Test
    @DisplayName("serializes large byte value")
    void serializesLargeBytes() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").bytes(102_400L).build();

      assertThat(toxic.toJson()).isEqualTo("{\"bytes\":102400}");
    }
  }

  @Nested
  @DisplayName("Validation — name")
  class NameValidationTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullNameThrows() {
      assertThatThrownBy(() -> LimitDataToxic.builder().name(null).build())
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("omitted name throws NullPointerException")
    void omittedNameThrows() {
      assertThatThrownBy(() -> LimitDataToxic.builder().bytes(100).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Validation — bytes")
  class BytesValidationTests {

    @Test
    @DisplayName("negative bytes throws IllegalArgumentException")
    void negativeBytesThrows() {
      assertThatThrownBy(() -> LimitDataToxic.builder().name("limit").bytes(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("bytes");
    }

    @Test
    @DisplayName("bytes=0 is valid (instant close on connect)")
    void zeroBytesIsValid() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("instant").bytes(0).build();

      assertThat(toxic.bytes()).isEqualTo(0L);
    }

    @Test
    @DisplayName("bytes=1 is valid (boundary)")
    void oneByte() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").bytes(1).build();

      assertThat(toxic.bytes()).isEqualTo(1L);
    }

    @Test
    @DisplayName("large byte value is valid")
    void largeBytes() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").bytes(Long.MAX_VALUE).build();

      assertThat(toxic.bytes()).isEqualTo(Long.MAX_VALUE);
    }
  }

  @Nested
  @DisplayName("Validation — toxicity")
  class ToxicityValidationTests {

    @Test
    @DisplayName("toxicity below 0.0 throws IllegalArgumentException")
    void toxicityBelowZeroThrows() {
      assertThatThrownBy(() -> LimitDataToxic.builder().name("limit").toxicity(-0.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity above 1.0 throws IllegalArgumentException")
    void toxicityAboveOneThrows() {
      assertThatThrownBy(() -> LimitDataToxic.builder().name("limit").toxicity(1.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity=0.0 is valid (boundary)")
    void toxicityZeroIsValid() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").toxicity(0.0).build();

      assertThat(toxic.toxicity()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toxicity=1.0 is valid (boundary)")
    void toxicityOneIsValid() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").toxicity(1.0).build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Real-world scenarios")
  class ScenarioTests {

    @Test
    @DisplayName("truncate after 1KB — exercises partial-read error handling")
    void truncateAfter1Kb() {
      LimitDataToxic toxic =
          LimitDataToxic.builder().name("truncate").bytes(1024).toxicity(1.0).build();

      assertThat(toxic.bytes()).isEqualTo(1024L);
      assertThat(toxic.toJson()).isEqualTo("{\"bytes\":1024}");
    }

    @Test
    @DisplayName("intermittent partial download on 25% of connections")
    void intermittentPartialDownload() {
      LimitDataToxic toxic =
          LimitDataToxic.builder().name("partial-download").bytes(102_400L).toxicity(0.25).build();

      assertThat(toxic.bytes()).isEqualTo(102_400L);
      assertThat(toxic.toxicity()).isEqualTo(0.25);
    }
  }

  @Nested
  @DisplayName("ToxicConfig interface")
  class InterfaceTests {

    @Test
    @DisplayName("implements ToxicConfig")
    void implementsToxicConfig() {
      LimitDataToxic toxic = LimitDataToxic.builder().name("limit").build();

      assertThat(toxic).isInstanceOf(ToxicConfig.class);
    }
  }
}
