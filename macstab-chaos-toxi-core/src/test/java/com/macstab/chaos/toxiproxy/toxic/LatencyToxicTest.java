/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.toxiproxy.toxic;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LatencyToxic")
class LatencyToxicTest {

  @Nested
  @DisplayName("Builder defaults")
  class DefaultsTests {

    @Test
    @DisplayName("latencyMs defaults to 0")
    void defaultLatencyMs() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").build();

      assertThat(toxic.latencyMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("jitterMs defaults to 0")
    void defaultJitterMs() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").build();

      assertThat(toxic.jitterMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("toxicity defaults to 1.0")
    void defaultToxicity() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Accessors")
  class AccessorTests {

    @Test
    @DisplayName("name() returns configured name")
    void name() {
      LatencyToxic toxic = LatencyToxic.builder().name("datacenter-lag").build();

      assertThat(toxic.name()).isEqualTo("datacenter-lag");
    }

    @Test
    @DisplayName("type() returns 'latency'")
    void type() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").build();

      assertThat(toxic.type()).isEqualTo("latency");
    }

    @Test
    @DisplayName("latencyMs() returns configured value")
    void latencyMs() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").latencyMs(100).build();

      assertThat(toxic.latencyMs()).isEqualTo(100);
    }

    @Test
    @DisplayName("jitterMs() returns configured value")
    void jitterMs() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").jitterMs(20).build();

      assertThat(toxic.jitterMs()).isEqualTo(20);
    }

    @Test
    @DisplayName("toxicity() returns configured value")
    void toxicity() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").toxicity(0.2).build();

      assertThat(toxic.toxicity()).isEqualTo(0.2);
    }
  }

  @Nested
  @DisplayName("toJson()")
  class ToJsonTests {

    @Test
    @DisplayName("serializes latency and jitter fields")
    void serializesFields() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").latencyMs(80).jitterMs(20).build();

      assertThat(toxic.toJson()).isEqualTo("{\"latency\":80,\"jitter\":20}");
    }

    @Test
    @DisplayName("serializes default values")
    void serializesDefaults() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").build();

      assertThat(toxic.toJson()).isEqualTo("{\"latency\":0,\"jitter\":0}");
    }

    @Test
    @DisplayName("serializes zero jitter when only latency set")
    void serializesZeroJitter() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").latencyMs(500).build();

      assertThat(toxic.toJson()).isEqualTo("{\"latency\":500,\"jitter\":0}");
    }
  }

  @Nested
  @DisplayName("Validation — name")
  class NameValidationTests {

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullNameThrows() {
      assertThatThrownBy(() -> LatencyToxic.builder().name(null).build())
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("omitted name throws NullPointerException")
    void omittedNameThrows() {
      assertThatThrownBy(() -> LatencyToxic.builder().latencyMs(100).build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Validation — latencyMs")
  class LatencyValidationTests {

    @Test
    @DisplayName("negative latencyMs throws IllegalArgumentException")
    void negativeLatencyThrows() {
      assertThatThrownBy(() -> LatencyToxic.builder().name("lag").latencyMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("latencyMs");
    }

    @Test
    @DisplayName("latencyMs=0 is valid (boundary)")
    void zeroLatencyIsValid() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").latencyMs(0).build();

      assertThat(toxic.latencyMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("large latencyMs is valid")
    void largeLatencyIsValid() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").latencyMs(5000).build();

      assertThat(toxic.latencyMs()).isEqualTo(5000);
    }
  }

  @Nested
  @DisplayName("Validation — jitterMs")
  class JitterValidationTests {

    @Test
    @DisplayName("negative jitterMs throws IllegalArgumentException")
    void negativeJitterThrows() {
      assertThatThrownBy(() -> LatencyToxic.builder().name("lag").jitterMs(-1).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jitterMs");
    }

    @Test
    @DisplayName("jitterMs=0 is valid (boundary)")
    void zeroJitterIsValid() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").jitterMs(0).build();

      assertThat(toxic.jitterMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("jitter greater than latency is allowed")
    void jitterGreaterThanLatencyIsAllowed() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").latencyMs(10).jitterMs(50).build();

      assertThat(toxic.jitterMs()).isEqualTo(50);
    }
  }

  @Nested
  @DisplayName("Validation — toxicity")
  class ToxicityValidationTests {

    @Test
    @DisplayName("toxicity below 0.0 throws IllegalArgumentException")
    void toxicityBelowZeroThrows() {
      assertThatThrownBy(() -> LatencyToxic.builder().name("lag").toxicity(-0.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity above 1.0 throws IllegalArgumentException")
    void toxicityAboveOneThrows() {
      assertThatThrownBy(() -> LatencyToxic.builder().name("lag").toxicity(1.01).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("toxicity");
    }

    @Test
    @DisplayName("toxicity=0.0 is valid (boundary)")
    void toxicityZeroIsValid() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").toxicity(0.0).build();

      assertThat(toxic.toxicity()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("toxicity=1.0 is valid (boundary)")
    void toxicityOneIsValid() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").toxicity(1.0).build();

      assertThat(toxic.toxicity()).isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Real-world scenarios")
  class ScenarioTests {

    @Test
    @DisplayName("cross-region latency: 80ms ± 20ms jitter")
    void crossRegionLatency() {
      LatencyToxic toxic = LatencyToxic.builder().name("eu-us").latencyMs(80).jitterMs(20).build();

      assertThat(toxic.latencyMs()).isEqualTo(80);
      assertThat(toxic.jitterMs()).isEqualTo(20);
      assertThat(toxic.toxicity()).isEqualTo(1.0);
      assertThat(toxic.toJson()).isEqualTo("{\"latency\":80,\"jitter\":20}");
    }

    @Test
    @DisplayName("flaky upstream: 500ms latency on 20% of connections")
    void flakyUpstream() {
      LatencyToxic toxic =
          LatencyToxic.builder().name("flaky").latencyMs(500).toxicity(0.2).build();

      assertThat(toxic.latencyMs()).isEqualTo(500);
      assertThat(toxic.jitterMs()).isEqualTo(0);
      assertThat(toxic.toxicity()).isEqualTo(0.2);
    }
  }

  @Nested
  @DisplayName("ToxicConfig interface")
  class InterfaceTests {

    @Test
    @DisplayName("implements ToxicConfig")
    void implementsToxicConfig() {
      LatencyToxic toxic = LatencyToxic.builder().name("lag").build();

      assertThat(toxic).isInstanceOf(ToxicConfig.class);
    }
  }
}
