/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TimeoutToxic")
class TimeoutToxicTest {

  @Nested
  @DisplayName("Builder defaults")
  class DefaultsTests {

    @Test
    @DisplayName("timeoutMs defaults to 0 (instant close)")
    void defaultTimeoutMs() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").build();

      assertThat(toxic.timeoutMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("toxicity defaults to 1.0")
    void defaultToxicity() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Accessors")
  class AccessorTests {

    @Test
    @DisplayName("name() returns configured name")
    void name() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("instant-drop").build();

      assertThat(toxic.name()).isEqualTo("instant-drop");
    }

    @Test
    @DisplayName("type() returns 'timeout'")
    void type() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").build();

      assertThat(toxic.type()).isEqualTo("timeout");
    }

    @Test
    @DisplayName("timeoutMs() returns configured value")
    void timeoutMs() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").timeoutMs(5000).build();

      assertThat(toxic.timeoutMs()).isEqualTo(5000);
    }

    @Test
    @DisplayName("toxicity() returns configured value")
    void toxicity() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").toxicity(0.3).build();

      assertThat(toxic.toxicity()).isEqualTo(0.3);
    }
  }

  @Nested
  @DisplayName("toJson()")
  class ToJsonTests {

    @Test
    @DisplayName("serializes timeout field")
    void serializesTimeout() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").timeoutMs(3000).build();

      assertThat(toxic.toJson()).isEqualTo("{\"timeout\":3000}");
    }

    @Test
    @DisplayName("serializes zero timeout (instant drop)")
    void serializesZeroTimeout() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").timeoutMs(0).build();

      assertThat(toxic.toJson()).isEqualTo("{\"timeout\":0}");
    }

    @Test
    @DisplayName("serializes default timeout")
    void serializesDefault() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").build();

      assertThat(toxic.toJson()).isEqualTo("{\"timeout\":0}");
    }
  }

  @Nested
  @DisplayName("Validation — name")
  class NameValidationTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullNameThrows() {
      assertThatThrownBy(() -> TimeoutToxic.builder().name(null).build())
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("omitted name throws NullPointerException")
    void omittedNameThrows() {
      assertThatThrownBy(() -> TimeoutToxic.builder().timeoutMs(100).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Validation — timeoutMs")
  class TimeoutValidationTests {

    @Test
    @DisplayName("negative timeoutMs throws IllegalArgumentException")
    void negativeTimeoutThrows() {
      assertThatThrownBy(() -> TimeoutToxic.builder().name("to").timeoutMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("timeoutMs");
    }

    @Test
    @DisplayName("timeoutMs=0 is valid (instant drop, boundary)")
    void zeroTimeoutIsValid() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").timeoutMs(0).build();

      assertThat(toxic.timeoutMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("large timeoutMs is valid")
    void largeTimeout() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").timeoutMs(60_000).build();

      assertThat(toxic.timeoutMs()).isEqualTo(60_000);
    }
  }

  @Nested
  @DisplayName("Validation — toxicity")
  class ToxicityValidationTests {

    @Test
    @DisplayName("toxicity below 0.0 throws IllegalArgumentException")
    void toxicityBelowZeroThrows() {
      assertThatThrownBy(() -> TimeoutToxic.builder().name("to").toxicity(-0.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity above 1.0 throws IllegalArgumentException")
    void toxicityAboveOneThrows() {
      assertThatThrownBy(() -> TimeoutToxic.builder().name("to").toxicity(1.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity=0.0 is valid (boundary)")
    void toxicityZeroIsValid() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").toxicity(0.0).build();

      assertThat(toxic.toxicity()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toxicity=1.0 is valid (boundary)")
    void toxicityOneIsValid() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").toxicity(1.0).build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Real-world scenarios")
  class ScenarioTests {

    @Test
    @DisplayName("instant drop: circuit breaker opens immediately")
    void instantDrop() {
      TimeoutToxic toxic =
          TimeoutToxic.builder().name("instant-drop").timeoutMs(0).toxicity(1.0).build();

      assertThat(toxic.timeoutMs()).isEqualTo(0);
      assertThat(toxic.toxicity()).isEqualTo(1.0);
      assertThat(toxic.toJson()).isEqualTo("{\"timeout\":0}");
    }

    @Test
    @DisplayName("overloaded upstream: 5s timeout on 30% of connections")
    void overloadedUpstream() {
      TimeoutToxic toxic =
          TimeoutToxic.builder().name("overloaded").timeoutMs(5000).toxicity(0.3).build();

      assertThat(toxic.timeoutMs()).isEqualTo(5000);
      assertThat(toxic.toxicity()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("trip client timeout: just above client's configured timeout")
    void tripClientTimeout() {
      TimeoutToxic toxic =
          TimeoutToxic.builder().name("trip-timeout").timeoutMs(3100).toxicity(1.0).build();

      assertThat(toxic.timeoutMs()).isEqualTo(3100);
      assertThat(toxic.toJson()).isEqualTo("{\"timeout\":3100}");
    }
  }

  @Nested
  @DisplayName("ToxicConfig interface")
  class InterfaceTests {

    @Test
    @DisplayName("implements ToxicConfig")
    void implementsToxicConfig() {
      TimeoutToxic toxic = TimeoutToxic.builder().name("to").build();

      assertThat(toxic).isInstanceOf(ToxicConfig.class);
    }
  }
}
