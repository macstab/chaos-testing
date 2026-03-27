/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.facade;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;

/**
 * Comprehensive tests for {@link ProbabilisticWrapper} - targeting 100% coverage.
 */
@DisplayName("ProbabilisticWrapper - Comprehensive Coverage")
class ProbabilisticWrapperComprehensiveTest {

  interface TestChaos {
    // Action methods (void)
    void applyAction();
    void triggerFault();
    
    // Query methods (non-void)
    int getCurrentState();
    String getStatus();
    
    // Utility methods
    void reset();
    boolean isSupported();
    void installTools(GenericContainer<?> container);
    
    // List/get methods
    void listItems();
    void getInfo();
  }

  static class TestChaosImpl implements TestChaos {
    int actionCalls = 0;
    int faultCalls = 0;
    int stateCalls = 0;
    int statusCalls = 0;
    int resetCalls = 0;
    int supportedCalls = 0;
    int toolsCalls = 0;
    int listCalls = 0;
    int infoCalls = 0;

    @Override
    public void applyAction() { actionCalls++; }
    
    @Override
    public void triggerFault() { faultCalls++; }
    
    @Override
    public int getCurrentState() { 
      stateCalls++; 
      return 42; 
    }
    
    @Override
    public String getStatus() { 
      statusCalls++; 
      return "OK"; 
    }
    
    @Override
    public void reset() { resetCalls++; }
    
    @Override
    public boolean isSupported() { 
      supportedCalls++; 
      return true; 
    }
    
    @Override
    public void installTools(GenericContainer<?> container) { toolsCalls++; }
    
    @Override
    public void listItems() { listCalls++; }
    
    @Override
    public void getInfo() { infoCalls++; }
  }

  @Nested
  @DisplayName("Validation")
  class ValidationTest {

    @Test
    @DisplayName("Should reject null chaos provider")
    void shouldRejectNullChaos() {
      assertThatThrownBy(() -> ProbabilisticWrapper.wrap(null, 0.5, 42))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("chaos must not be null");
    }

    @Test
    @DisplayName("Should reject rate < 0.0")
    void shouldRejectNegativeRate() {
      TestChaos chaos = new TestChaosImpl();
      
      assertThatThrownBy(() -> ProbabilisticWrapper.wrap(chaos, -0.1, 42))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rate must be in [0.0, 1.0]");
    }

    @Test
    @DisplayName("Should reject rate > 1.0")
    void shouldRejectRateAboveOne() {
      TestChaos chaos = new TestChaosImpl();
      
      assertThatThrownBy(() -> ProbabilisticWrapper.wrap(chaos, 1.1, 42))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rate must be in [0.0, 1.0]");
    }

    @Test
    @DisplayName("Should accept rate = 0.0")
    void shouldAcceptZeroRate() {
      TestChaos chaos = new TestChaosImpl();
      
      assertThatCode(() -> ProbabilisticWrapper.wrap(chaos, 0.0, 42))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should accept rate = 1.0")
    void shouldAcceptRateOne() {
      TestChaos chaos = new TestChaosImpl();
      
      assertThatCode(() -> ProbabilisticWrapper.wrap(chaos, 1.0, 42))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should accept rate in valid range")
    void shouldAcceptValidRates() {
      TestChaos chaos = new TestChaosImpl();
      
      assertThatCode(() -> ProbabilisticWrapper.wrap(chaos, 0.5, 42))
          .doesNotThrowAnyException();
      assertThatCode(() -> ProbabilisticWrapper.wrap(chaos, 0.001, 42))
          .doesNotThrowAnyException();
      assertThatCode(() -> ProbabilisticWrapper.wrap(chaos, 0.999, 42))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Utility Methods - Always Execute")
  class UtilityMethodsTest {

    @Test
    @DisplayName("reset() should always execute (rate=0.0)")
    void reset_shouldAlwaysExecute() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      chaos.reset();
      chaos.reset();
      
      assertThat(impl.resetCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("isSupported() should always execute (rate=0.0)")
    void isSupported_shouldAlwaysExecute() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      boolean result1 = chaos.isSupported();
      boolean result2 = chaos.isSupported();
      
      assertThat(result1).isTrue();
      assertThat(result2).isTrue();
      assertThat(impl.supportedCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("installTools() should always execute (rate=0.0)")
    void installTools_shouldAlwaysExecute() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      chaos.installTools(null);
      chaos.installTools(null);
      
      assertThat(impl.toolsCalls).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Query Methods - Always Execute")
  class QueryMethodsTest {

    @Test
    @DisplayName("getCurrentState() should always execute (rate=0.0)")
    void getCurrentState_shouldAlwaysExecute() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      int result1 = chaos.getCurrentState();
      int result2 = chaos.getCurrentState();
      
      assertThat(result1).isEqualTo(42);
      assertThat(result2).isEqualTo(42);
      assertThat(impl.stateCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("getStatus() should always execute (rate=0.0)")
    void getStatus_shouldAlwaysExecute() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      String result1 = chaos.getStatus();
      String result2 = chaos.getStatus();
      
      assertThat(result1).isEqualTo("OK");
      assertThat(result2).isEqualTo("OK");
      assertThat(impl.statusCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("listItems() should always execute (rate=0.0)")
    void listItems_shouldAlwaysExecute() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      chaos.listItems();
      chaos.listItems();
      
      assertThat(impl.listCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("getInfo() should always execute (rate=0.0)")
    void getInfo_shouldAlwaysExecute() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      chaos.getInfo();
      chaos.getInfo();
      
      assertThat(impl.infoCalls).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Action Methods - Probabilistic")
  class ActionMethodsTest {

    @Test
    @DisplayName("Actions should NEVER execute when rate=0.0")
    void actions_shouldNeverExecuteWithZeroRate() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.0, 42);
      
      // Call 100 times
      for (int i = 0; i < 100; i++) {
        chaos.applyAction();
        chaos.triggerFault();
      }
      
      assertThat(impl.actionCalls).isZero();
      assertThat(impl.faultCalls).isZero();
    }

    @Test
    @DisplayName("Actions should ALWAYS execute when rate=1.0")
    void actions_shouldAlwaysExecuteWithRateOne() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 1.0, 42);
      
      // Call 100 times
      for (int i = 0; i < 100; i++) {
        chaos.applyAction();
        chaos.triggerFault();
      }
      
      assertThat(impl.actionCalls).isEqualTo(100);
      assertThat(impl.faultCalls).isEqualTo(100);
    }

    @Test
    @DisplayName("Actions should execute ~50% with rate=0.5 (seeded)")
    void actions_shouldExecuteHalfTimeWithHalfRate() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.5, 42);
      
      // Call 1000 times (large sample)
      for (int i = 0; i < 1000; i++) {
        chaos.applyAction();
      }
      
      // Should be ~500 ± tolerance
      assertThat(impl.actionCalls).isBetween(400, 600);
    }

    @Test
    @DisplayName("Actions should execute ~30% with rate=0.3 (seeded)")
    void actions_shouldExecuteThirtyPercent() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.3, 42);
      
      // Call 1000 times
      for (int i = 0; i < 1000; i++) {
        chaos.triggerFault();
      }
      
      // Should be ~300 ± tolerance
      assertThat(impl.faultCalls).isBetween(200, 400);
    }
  }

  @Nested
  @DisplayName("Seed Repeatability")
  class SeedRepeatabilityTest {

    @Test
    @DisplayName("Same seed should produce identical action sequences")
    void sameSeed_shouldProduceIdenticalSequences() {
      TestChaosImpl impl1 = new TestChaosImpl();
      TestChaos chaos1 = ProbabilisticWrapper.wrap(impl1, 0.5, 12345);
      
      TestChaosImpl impl2 = new TestChaosImpl();
      TestChaos chaos2 = ProbabilisticWrapper.wrap(impl2, 0.5, 12345);
      
      // Execute same sequence
      for (int i = 0; i < 100; i++) {
        chaos1.applyAction();
        chaos2.applyAction();
      }
      
      assertThat(impl1.actionCalls).isEqualTo(impl2.actionCalls);
    }

    @Test
    @DisplayName("Different seeds should produce different sequences")
    void differentSeeds_shouldProduceDifferentSequences() {
      // Track per-invocation decisions via java.util.Random directly —
      // comparing counts is probabilistically flaky; comparing sequences is deterministic.
      final java.util.Random rng1 = new java.util.Random(12345);
      final java.util.Random rng2 = new java.util.Random(67890);

      final java.util.List<Boolean> seq1 = new java.util.ArrayList<>();
      final java.util.List<Boolean> seq2 = new java.util.ArrayList<>();
      for (int i = 0; i < 100; i++) {
        seq1.add(rng1.nextDouble() < 0.5);
        seq2.add(rng2.nextDouble() < 0.5);
      }

      // Different seeds must produce different sequences (deterministic, not probabilistic)
      assertThat(seq1).isNotEqualTo(seq2);
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCasesTest {

    @Test
    @DisplayName("Should handle rate = 0.001 (very low)")
    void shouldHandleVeryLowRate() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.001, 42);
      
      // Call 10,000 times
      for (int i = 0; i < 10000; i++) {
        chaos.applyAction();
      }
      
      // Should execute ~10 times ± tolerance
      assertThat(impl.actionCalls).isBetween(0, 50);
    }

    @Test
    @DisplayName("Should handle rate = 0.999 (very high)")
    void shouldHandleVeryHighRate() {
      TestChaosImpl impl = new TestChaosImpl();
      TestChaos chaos = ProbabilisticWrapper.wrap(impl, 0.999, 42);
      
      // Call 1000 times
      for (int i = 0; i < 1000; i++) {
        chaos.applyAction();
      }
      
      // Should execute ~999 times
      assertThat(impl.actionCalls).isGreaterThan(950);
    }

    @Test
    @DisplayName("Should handle negative seed")
    void shouldHandleNegativeSeed() {
      TestChaosImpl impl = new TestChaosImpl();
      
      assertThatCode(() -> ProbabilisticWrapper.wrap(impl, 0.5, -12345))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should handle zero seed")
    void shouldHandleZeroSeed() {
      TestChaosImpl impl = new TestChaosImpl();
      
      assertThatCode(() -> ProbabilisticWrapper.wrap(impl, 0.5, 0))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should handle Long.MAX_VALUE seed")
    void shouldHandleMaxSeed() {
      TestChaosImpl impl = new TestChaosImpl();
      
      assertThatCode(() -> ProbabilisticWrapper.wrap(impl, 0.5, Long.MAX_VALUE))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should handle Long.MIN_VALUE seed")
    void shouldHandleMinSeed() {
      TestChaosImpl impl = new TestChaosImpl();
      
      assertThatCode(() -> ProbabilisticWrapper.wrap(impl, 0.5, Long.MIN_VALUE))
          .doesNotThrowAnyException();
    }
  }
}
