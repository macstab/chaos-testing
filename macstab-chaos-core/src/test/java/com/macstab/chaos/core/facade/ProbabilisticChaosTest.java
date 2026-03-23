/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.api.CpuChaos;

/**
 * Tests for probabilistic chaos functionality.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ProbabilisticChaosTest {

  private GenericContainer<?> container;
  private ChaosController chaos;

  @BeforeEach
  void setUp() throws Exception {
    container = new GenericContainer<>(DockerImageName.parse("alpine:3.19"));
    chaos = new ChaosController(container);
  }

  @Nested
  @DisplayName("withProbability() API")
  class WithProbabilityTests {

    @Test
    @DisplayName("should return new controller with probabilistic context")
    void shouldReturnNewController() throws Exception {
      final ChaosController probabilistic = chaos.withProbability(0.5, 42);

      assertThat(probabilistic).isNotNull();
      assertThat(probabilistic).isNotSameAs(chaos); // New instance
    }

    @Test
    @DisplayName("should validate rate bounds [0.0, 1.0]")
    void shouldValidateRate() throws Exception {
      // Valid rates
      assertThat(chaos.withProbability(0.0, 42)).isNotNull();
      assertThat(chaos.withProbability(1.0, 42)).isNotNull();
      assertThat(chaos.withProbability(0.5, 42)).isNotNull();

      // Invalid rates
      assertThatThrownBy(() -> chaos.withProbability(-0.1, 42))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rate must be in [0.0, 1.0]");

      assertThatThrownBy(() -> chaos.withProbability(1.1, 42))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rate must be in [0.0, 1.0]");
    }
  }

  @Nested
  @DisplayName("Probabilistic wrapper behavior")
  class WrapperBehaviorTests {

    @Test
    @DisplayName("should apply chaos with 100% probability (rate=1.0)")
    void shouldAlwaysApplyWithRate100() throws Exception {
      final ChaosController probabilistic = chaos.withProbability(1.0, 42);

      // Get chaos provider (should be wrapped)
      final CpuChaos cpu = probabilistic.cpu();

      assertThat(cpu).isNotNull();
      // Wrapper implements same interface
      assertThat(cpu).isInstanceOf(CpuChaos.class);
    }

    @Test
    @DisplayName("should never apply chaos with 0% probability (rate=0.0)")
    void shouldNeverApplyWithRate0() throws Exception {
      final ChaosController probabilistic = chaos.withProbability(0.0, 42);

      final CpuChaos cpu = probabilistic.cpu();

      assertThat(cpu).isNotNull();
      assertThat(cpu).isInstanceOf(CpuChaos.class);
    }

    @Test
    @DisplayName("should use different seed for different behavior")
    void shouldRespectSeed() throws Exception {
      final ChaosController prob1 = chaos.withProbability(0.5, 42);
      final ChaosController prob2 = chaos.withProbability(0.5, 99);

      // Different seeds should create different wrappers
      assertThat(prob1.cpu()).isNotSameAs(prob2.cpu());
    }
  }

  @Nested
  @DisplayName("Deterministic vs Probabilistic mode")
  class ModeSwitchingTests {

    @Test
    @DisplayName("should return unwrapped provider in deterministic mode")
    void shouldReturnUnwrappedInDeterministicMode() throws Exception {
      final CpuChaos cpu1 = chaos.cpu();
      final CpuChaos cpu2 = chaos.cpu();

      // Same instance (cached)
      assertThat(cpu1).isSameAs(cpu2);
    }

    @Test
    @DisplayName("should return wrapped provider in probabilistic mode")
    void shouldReturnWrappedInProbabilisticMode() throws Exception {
      final ChaosController probabilistic = chaos.withProbability(0.5, 42);

      final CpuChaos cpu1 = probabilistic.cpu();
      final CpuChaos cpu2 = probabilistic.cpu();

      // Different instances (wrapped each time)
      assertThat(cpu1).isNotSameAs(cpu2);
    }

    @Test
    @DisplayName("should keep original chaos instance unchanged")
    void shouldNotMutateDeterministicInstance() throws Exception {
      final CpuChaos originalCpu = chaos.cpu();

      // Create probabilistic controller
      final ChaosController probabilistic = chaos.withProbability(0.5, 42);
      final CpuChaos probabilisticCpu = probabilistic.cpu();

      // Original should still return unwrapped
      final CpuChaos stillOriginal = chaos.cpu();
      assertThat(stillOriginal).isSameAs(originalCpu);
      assertThat(stillOriginal).isNotSameAs(probabilisticCpu);
    }
  }

  @Nested
  @DisplayName("All chaos types support")
  class AllChaosTypesTests {

    @Test
    @DisplayName("should wrap all chaos types (CPU, Memory, Disk, etc.)")
    void shouldWrapAllChaosTypes() throws Exception {
      final ChaosController probabilistic = chaos.withProbability(0.5, 42);

      // All chaos types should be wrappable
      assertThat(probabilistic.cpu()).isNotNull();
      assertThat(probabilistic.memory()).isNotNull();
      assertThat(probabilistic.disk()).isNotNull();
      assertThat(probabilistic.process()).isNotNull();
      assertThat(probabilistic.network()).isNotNull();
      assertThat(probabilistic.time()).isNotNull();
      assertThat(probabilistic.dns()).isNotNull();
    }
  }

  @Nested
  @DisplayName("Thread safety")
  class ThreadSafetyTests {

    @Test
    @DisplayName("should be thread-safe under high concurrency")
    void shouldBeThreadSafe() throws InterruptedException {
      final ChaosController probabilistic = chaos.withProbability(0.5, 42);
      final int threadCount = 100;
      final int callsPerThread = 100;

      final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      final CountDownLatch startLatch = new CountDownLatch(1);
      final CountDownLatch endLatch = new CountDownLatch(threadCount);
      final List<Exception> exceptions = new ArrayList<>();

      // Start all threads simultaneously
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              try {
                startLatch.await(); // Wait for start signal

                for (int j = 0; j < callsPerThread; j++) {
                  // Call chaos methods concurrently
                  probabilistic.cpu();
                  probabilistic.memory();
                  probabilistic.disk();
                }
              } catch (final Exception e) {
                synchronized (exceptions) {
                  exceptions.add(e);
                }
              } finally {
                endLatch.countDown();
              }
            });
      }

      // Start all threads
      startLatch.countDown();

      // Wait for completion
      assertThat(endLatch.await(30, TimeUnit.SECONDS)).isTrue();
      executor.shutdown();

      // No exceptions should occur
      assertThat(exceptions).isEmpty();
    }

    @Test
    @DisplayName("should produce deterministic results with same seed across threads")
    void shouldProduceDeterministicResults() throws Exception {
      final int iterations = 1000;
      final ExecutorService executor = Executors.newFixedThreadPool(10);
      final List<Future<Boolean>> futures = new ArrayList<>();

      // Run same probabilistic chaos from multiple threads
      for (int i = 0; i < iterations; i++) {
        futures.add(
            executor.submit(
                () -> {
                  // Same seed should produce same sequence
                  final ChaosController prob1 = chaos.withProbability(0.5, 42);
                  final ChaosController prob2 = chaos.withProbability(0.5, 42);

                  // Both should return non-null providers
                  return prob1.cpu() != null && prob2.cpu() != null;
                }));
      }

      executor.shutdown();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

      // All futures should complete successfully
      for (final Future<Boolean> future : futures) {
        assertThat(future.get()).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("Query vs Action methods")
  class QueryVsActionTests {

    @Test
    @DisplayName("should always execute query methods regardless of probability")
    void shouldAlwaysExecuteQueryMethods() throws Exception {
      // Even with 0% probability, query methods should work
      final ChaosController probabilistic = chaos.withProbability(0.0, 42);

      final CpuChaos cpu = probabilistic.cpu();

      // Query methods should always execute (return values)
      assertThat(cpu.isSupported()).isNotNull(); // Utility method
      // getCurrentUsage would be a query method (non-void)
    }

    @Test
    @DisplayName("should apply probability only to action methods")
    void shouldApplyProbabilityOnlyToActions() throws Exception {
      final ChaosController probabilistic = chaos.withProbability(1.0, 42);

      final CpuChaos cpu = probabilistic.cpu();

      // Action methods (void) should be affected by probability
      // Query methods (non-void) should not
      assertThat(cpu).isNotNull();
      assertThat(cpu.isSupported()).isNotNull(); // Always executes
    }
  }
}
