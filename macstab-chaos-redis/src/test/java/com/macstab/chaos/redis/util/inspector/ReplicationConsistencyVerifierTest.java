/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.util.inspector.executor.RedisCommandExecutor;
import com.macstab.chaos.redis.util.inspector.model.ConsistencyResult;

/** Comprehensive unit tests for {@link ReplicationConsistencyVerifier}. */
@DisplayName("ReplicationConsistencyVerifier")
class ReplicationConsistencyVerifierTest {

  private RedisCommandExecutor mockExecutorReturning(final Function<String, String> responseFor) {
    final RedisCommandExecutor executor = mock(RedisCommandExecutor.class);
    when(executor.execute(startsWith("GET ")))
        .thenAnswer(
            inv -> {
              final String cmd = inv.getArgument(0, String.class);
              final String key = cmd.substring(4).trim();
              return responseFor.apply(key);
            });
    return executor;
  }

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null masterExecutor")
    void shouldThrowForNullMaster() {
      // ARRANGE
      final RedisCommandExecutor replicaExecutor = mock(RedisCommandExecutor.class);

      // ACT / ASSERT
      assertThatThrownBy(() -> new ReplicationConsistencyVerifier(null, replicaExecutor))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw NPE for null replicaExecutor")
    void shouldThrowForNullReplica() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mock(RedisCommandExecutor.class);

      // ACT / ASSERT
      assertThatThrownBy(() -> new ReplicationConsistencyVerifier(masterExecutor, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("verify() — argument validation")
  class VerifyArgumentValidation {

    @Test
    @DisplayName("Should throw IAE for keyCount zero")
    void shouldThrowForKeyCountZero() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mock(RedisCommandExecutor.class);
      final RedisCommandExecutor replicaExecutor = mock(RedisCommandExecutor.class);
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);

      // ACT / ASSERT
      assertThatThrownBy(() -> verifier.verify(0, Duration.ofSeconds(5)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should throw IAE for negative keyCount")
    void shouldThrowForNegativeKeyCount() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mock(RedisCommandExecutor.class);
      final RedisCommandExecutor replicaExecutor = mock(RedisCommandExecutor.class);
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);

      // ACT / ASSERT
      assertThatThrownBy(() -> verifier.verify(-1, Duration.ofSeconds(5)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should throw NPE for null timeout")
    void shouldThrowForNullTimeout() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mock(RedisCommandExecutor.class);
      final RedisCommandExecutor replicaExecutor = mock(RedisCommandExecutor.class);
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);

      // ACT / ASSERT
      assertThatThrownBy(() -> verifier.verify(10, null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("verify() — full consistency")
  class VerifyFullConsistency {

    @Test
    @DisplayName("Should report full consistency when replica matches all keys")
    void shouldReportFullConsistency() {
      // ARRANGE: replica always returns the key as value
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final RedisCommandExecutor replicaExecutor = mockExecutorReturning(key -> key);
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);

      // ACT
      final ConsistencyResult result = verifier.verify(10, Duration.ofSeconds(5));

      // ASSERT
      assertThat(result.matchingKeys()).isEqualTo(10);
      assertThat(result.consistencyRatio()).isEqualTo(1.0);
      assertThat(result.isFullyConsistent()).isTrue();
    }
  }

  @Nested
  @DisplayName("verify() — zero consistency")
  class VerifyZeroConsistency {

    @Test
    @DisplayName("Should report zero consistency when replica has no keys")
    void shouldReportZeroConsistency() {
      // ARRANGE: master returns key, replica returns null (simulating no replication)
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final RedisCommandExecutor replicaExecutor = mockExecutorReturning(key -> "(nil)");
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);

      // ACT
      final ConsistencyResult result = verifier.verify(5, Duration.ofSeconds(5));

      // ASSERT
      assertThat(result.matchingKeys()).isEqualTo(0);
      assertThat(result.missingKeys()).isEqualTo(5);
      assertThat(result.consistencyRatio()).isEqualTo(0.0);
    }
  }

  @Nested
  @DisplayName("verify() — partial consistency")
  class VerifyPartialConsistency {

    @Test
    @DisplayName("Should report partial consistency")
    void shouldReportPartialConsistency() {
      // ARRANGE: replica returns key for keys ending in even char, null otherwise (≈50% hit rate)
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final java.util.concurrent.atomic.AtomicInteger counter =
          new java.util.concurrent.atomic.AtomicInteger(0);
      final RedisCommandExecutor replicaExecutor =
          mockExecutorReturning(key -> counter.getAndIncrement() % 2 == 0 ? key : "(nil)");
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);

      // ACT
      final ConsistencyResult result = verifier.verify(10, Duration.ofSeconds(5));

      // ASSERT
      assertThat(result.consistencyRatio()).isBetween(0.4, 0.6);
    }
  }

  @Nested
  @DisplayName("verify() — cleanup")
  class VerifyCleanup {

    @Test
    @DisplayName("Should delete all test keys after verify")
    void shouldDeleteTestKeys() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final RedisCommandExecutor replicaExecutor = mockExecutorReturning(key -> key);
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);

      // ACT
      verifier.verify(3, Duration.ofSeconds(5));

      // ASSERT
      verify(masterExecutor, org.mockito.Mockito.times(3)).execute(startsWith("DEL "));
    }
  }

  @Nested
  @DisplayName("ConsistencyResult.assertFullConsistency()")
  class ConsistencyResultAssertFullConsistency {

    @Test
    @DisplayName("Should pass when ratio is 1.0")
    void shouldPassWhenFullyConsistent() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final RedisCommandExecutor replicaExecutor = mockExecutorReturning(key -> key);
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);
      final ConsistencyResult result = verifier.verify(5, Duration.ofSeconds(5));

      // ACT / ASSERT
      result.assertFullConsistency();
    }

    @Test
    @DisplayName("Should throw AssertionError when partial consistency")
    void shouldThrowWhenPartial() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final java.util.concurrent.atomic.AtomicInteger cnt2 =
          new java.util.concurrent.atomic.AtomicInteger(0);
      final RedisCommandExecutor replicaExecutor =
          mockExecutorReturning(
              key ->
                  cnt2.getAndIncrement() % 2 == 0
                      ? "(nil)"
                      : "(nil)"); // all nil → definitely partial
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);
      final ConsistencyResult result = verifier.verify(10, Duration.ofSeconds(1));

      // ACT / ASSERT
      assertThatThrownBy(result::assertFullConsistency).isInstanceOf(AssertionError.class);
    }
  }

  @Nested
  @DisplayName("ConsistencyResult.assertConsistencyAtLeast()")
  class ConsistencyResultAssertConsistencyAtLeast {

    @Test
    @DisplayName("Should pass when ratio meets threshold")
    void shouldPassWhenMeetsThreshold() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final RedisCommandExecutor replicaExecutor = mockExecutorReturning(key -> key);
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);
      final ConsistencyResult result = verifier.verify(5, Duration.ofSeconds(5));

      // ACT / ASSERT
      result.assertConsistencyAtLeast(0.9);
    }

    @Test
    @DisplayName("Should throw AssertionError when below threshold")
    void shouldThrowWhenBelowThreshold() {
      // ARRANGE
      final RedisCommandExecutor masterExecutor = mockExecutorReturning(key -> key);
      final RedisCommandExecutor replicaExecutor = mockExecutorReturning(key -> "(nil)");
      final ReplicationConsistencyVerifier verifier =
          new ReplicationConsistencyVerifier(masterExecutor, replicaExecutor);
      final ConsistencyResult result = verifier.verify(5, Duration.ofSeconds(5));

      // ACT / ASSERT
      assertThatThrownBy(() -> result.assertConsistencyAtLeast(0.5))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("50.00%");
    }
  }
}
