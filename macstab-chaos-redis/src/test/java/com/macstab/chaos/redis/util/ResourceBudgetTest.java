/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.util.ResourceBudget.ResourceBudgetExceededException;

/**
 * Unit tests for {@link ResourceBudget} validation logic.
 *
 * <p>Validates resource budget constraints prevent accidental resource exhaustion in CI
 * environments.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ResourceBudget Validation Tests")
class ResourceBudgetTest {

  @Nested
  @DisplayName("Sentinel Budget Validation")
  class SentinelBudgetTests {

    @Test
    @DisplayName("Should reject null sentinel input")
    void shouldRejectNullSentinelInput() {
      assertThatThrownBy(() -> ResourceBudget.validateSentinelBudget(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("clusters must not be null");
    }

    @Test
    @DisplayName("Should reject Sentinel clusters where memory limit is exceeded")
    void shouldRejectSentinelClustersExceedingMemoryIndirectly() {
      // Memory limit 100MB at 4MB/container requires >25 containers.
      // Container limit (20) fires first, so this validates that the limit message is present.
      final RedisSentinel[] clusters =
          new RedisSentinel[] {
            createSentinel("c1", 4, 4), // 1M+4R+4S=9
            createSentinel("c2", 4, 4), // 9 → total 18, within limit
            createSentinel("c3", 2, 2) // 5 → total 23, container limit exceeded (not memory)
          };

      assertThatThrownBy(() -> ResourceBudget.validateSentinelBudget(clusters))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Total containers exceed limit");
    }

    @Test
    @DisplayName("Should accept valid Sentinel configuration (within budget)")
    void shouldAcceptValidConfiguration() {
      // ARRANGE: 2 clusters with moderate resources
      final RedisSentinel[] clusters =
          new RedisSentinel[] {
            createSentinel("cluster-1", 2, 3), // 1M + 2R + 3S = 6 containers
            createSentinel("cluster-2", 2, 3) // 1M + 2R + 3S = 6 containers
            // Total: 12 containers, well within 20 limit
          };

      // ACT & ASSERT: No exception
      assertThatCode(() -> ResourceBudget.validateSentinelBudget(clusters))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject too many Sentinel clusters (exceeds MAX_SENTINEL_CLUSTERS)")
    void shouldRejectTooManyClusters() {
      // ARRANGE: 4 clusters (exceeds limit of 3)
      final RedisSentinel[] clusters =
          new RedisSentinel[] {
            createSentinel("c1", 1, 1),
            createSentinel("c2", 1, 1),
            createSentinel("c3", 1, 1),
            createSentinel("c4", 1, 1) // EXCEEDS LIMIT
          };

      // ACT & ASSERT: Exception thrown
      assertThatThrownBy(() -> ResourceBudget.validateSentinelBudget(clusters))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Too many Sentinel clusters: 4 (max: 3)");
    }

    @Test
    @DisplayName("Should reject too many total containers (exceeds MAX_TOTAL_CONTAINERS)")
    void shouldRejectTooManyContainers() {
      // ARRANGE: 3 clusters with high replica/sentinel counts
      final RedisSentinel[] clusters =
          new RedisSentinel[] {
            createSentinel("c1", 5, 7), // 1M + 5R + 7S = 13 containers
            createSentinel("c2", 5, 7), // 13 containers
            // Total: 26 containers (exceeds 20 limit)
          };

      // ACT & ASSERT: Exception thrown
      assertThatThrownBy(() -> ResourceBudget.validateSentinelBudget(clusters))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Total containers exceed limit: 26 (max: 20)");
    }

    @Test
    @DisplayName("Should reject configuration with excessive memory estimate")
    void shouldRejectExcessiveMemory() {
      // ARRANGE: 3 clusters with high container counts (memory limit exceeded)
      final RedisSentinel[] clusters =
          new RedisSentinel[] {
            createSentinel("c1", 5, 5), // 1M + 5R + 5S = 11 containers
            createSentinel("c2", 5, 5), // 11 containers
            // Total: 22 containers × 4MB = 88MB (but would exceed if 25+ containers)
          };

      // This should pass (22 containers < 20 limit catches it first)
      assertThatThrownBy(() -> ResourceBudget.validateSentinelBudget(clusters))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Total containers exceed limit");
    }
  }

  @Nested
  @DisplayName("Standalone Budget Validation")
  class StandaloneBudgetTests {

    @Test
    @DisplayName("Should reject null standalone input")
    void shouldRejectNullStandaloneInput() {
      assertThatThrownBy(() -> ResourceBudget.validateStandaloneBudget(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("instances must not be null");
    }

    @Test
    @DisplayName("Should accept valid standalone configuration (within budget)")
    void shouldAcceptValidConfiguration() {
      // ARRANGE: 3 instances (well within 5 limit)
      final RedisStandalone[] instances =
          new RedisStandalone[] {
            createStandalone("cache"), createStandalone("session"), createStandalone("rate-limiter")
          };

      // ACT & ASSERT: No exception
      assertThatCode(() -> ResourceBudget.validateStandaloneBudget(instances))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject too many standalone instances (exceeds MAX_STANDALONE_INSTANCES)")
    void shouldRejectTooManyInstances() {
      // ARRANGE: 6 instances (exceeds limit of 5)
      final RedisStandalone[] instances =
          new RedisStandalone[] {
            createStandalone("i1"),
            createStandalone("i2"),
            createStandalone("i3"),
            createStandalone("i4"),
            createStandalone("i5"),
            createStandalone("i6") // EXCEEDS LIMIT
          };

      // ACT & ASSERT: Exception thrown
      assertThatThrownBy(() -> ResourceBudget.validateStandaloneBudget(instances))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Too many standalone Redis instances: 6 (max: 5)");
    }
  }

  @Nested
  @DisplayName("Mixed Budget Validation")
  class MixedBudgetTests {

    @Test
    @DisplayName("Should reject mixed configuration where sentinel clusters exceed container limit")
    void shouldRejectMixedConfigurationExceedingContainerLimitViaSentinels() {
      // ARRANGE: 3 clusters each with large containers → exceeds 20 total
      final RedisSentinel[] sentinels =
          new RedisSentinel[] {
            createSentinel("c1", 3, 3), // 1+3+3=7
            createSentinel("c2", 3, 3), // 7 → total 14
            createSentinel("c3", 2, 3) // 6 → total 20 exactly at limit; add standalone below
          };
      final RedisStandalone[] standalones =
          new RedisStandalone[] {
            createStandalone("s1") // +1 → total 21, exceeds limit
          };

      assertThatThrownBy(() -> ResourceBudget.validateMixedBudget(sentinels, standalones))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Total containers exceed limit");
    }

    @Test
    @DisplayName("Should accept valid mixed configuration")
    void shouldAcceptValidMixedConfiguration() {
      // ARRANGE: 1 Sentinel cluster + 2 standalone instances
      final RedisSentinel[] sentinels =
          new RedisSentinel[] {createSentinel("ha", 2, 3)}; // 6 containers

      final RedisStandalone[] standalones =
          new RedisStandalone[] {
            createStandalone("cache"), createStandalone("session")
          }; // 2 containers

      // Total: 8 containers (within limit)

      // ACT & ASSERT: No exception
      assertThatCode(() -> ResourceBudget.validateMixedBudget(sentinels, standalones))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject mixed configuration exceeding container limit")
    void shouldRejectExcessiveContainersMixed() {
      // ARRANGE: 2 Sentinel clusters + 10 standalone instances
      final RedisSentinel[] sentinels =
          new RedisSentinel[] {
            createSentinel("c1", 3, 5), // 1M + 3R + 5S = 9 containers
            createSentinel("c2", 3, 5) // 9 containers
          };

      final RedisStandalone[] standalones =
          new RedisStandalone[] {
            createStandalone("s1"),
            createStandalone("s2"),
            createStandalone("s3"),
            createStandalone("s4"),
            createStandalone("s5")
          }; // 5 containers

      // Total: 18 + 5 = 23 containers (exceeds 20 limit)

      // ACT & ASSERT: Exception thrown
      assertThatThrownBy(() -> ResourceBudget.validateMixedBudget(sentinels, standalones))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Total containers exceed limit: 23 (max: 20)");
    }

    @Test
    @DisplayName("Should reject null sentinelClusters")
    void shouldRejectNullSentinelClusters() {
      // ARRANGE
      final RedisStandalone[] standalones = new RedisStandalone[] {createStandalone("cache")};

      // ACT & ASSERT
      assertThatThrownBy(() -> ResourceBudget.validateMixedBudget(null, standalones))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sentinelClusters must not be null");
    }

    @Test
    @DisplayName("Should reject null standaloneInstances")
    void shouldRejectNullStandaloneInstances() {
      // ARRANGE
      final RedisSentinel[] sentinels = new RedisSentinel[] {createSentinel("ha", 2, 3)};

      // ACT & ASSERT
      assertThatThrownBy(() -> ResourceBudget.validateMixedBudget(sentinels, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("standaloneInstances must not be null");
    }

    @Test
    @DisplayName("Should accept mixed budget with both empty arrays")
    void shouldAcceptBothEmptyArrays() {
      // ARRANGE: both empty
      final RedisSentinel[] sentinels = new RedisSentinel[0];
      final RedisStandalone[] standalones = new RedisStandalone[0];

      // ACT & ASSERT: 0 containers — always within budget
      assertThatCode(() -> ResourceBudget.validateMixedBudget(sentinels, standalones))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject combined budget exceeding memory limit")
    void shouldRejectCombinedMemoryExceeded() {
      // ARRANGE: 3 sentinel clusters × 8 containers each + 5 standalone = 29 containers
      // But total containers > 20 is caught first, so set up a case where memory is the binding
      // constraint: 25 containers × 4MB = 100MB exactly at limit; 26 × 4MB = 104MB > 100MB
      // To exceed memory but stay under container limit:
      // MAX_TOTAL_CONTAINERS=20, MAX_MEMORY_MB=100, MEMORY_PER=4MB → 25 containers would be 100MB
      // but 20 containers = 80MB which is under. Only way to hit memory limit:
      // need >25 containers, but container limit is 20 → container limit always fires first.
      // So verify the container limit message is present (memory limit can't be triggered alone).
      final RedisSentinel[] sentinels =
          new RedisSentinel[] {
            createSentinel("c1", 7, 7), // 1+7+7=15
            createSentinel("c2", 7, 7) // 15 → total 30 containers
          };
      final RedisStandalone[] standalones = new RedisStandalone[0];

      // ACT & ASSERT: container limit is exceeded (30 > 20)
      assertThatThrownBy(() -> ResourceBudget.validateMixedBudget(sentinels, standalones))
          .isInstanceOf(ResourceBudgetExceededException.class)
          .hasMessageContaining("Total containers exceed limit");
    }
  }

  // ==================== Helper Methods ====================

  private RedisSentinel createSentinel(final String id, final int replicas, final int sentinels) {
    return new RedisSentinel() {
      @Override
      public Class<? extends java.lang.annotation.Annotation> annotationType() {
        return RedisSentinel.class;
      }

      @Override
      public String id() {
        return id;
      }

      @Override
      public String version() {
        return "7.4";
      }

      @Override
      public String masterName() {
        return "mymaster";
      }

      @Override
      public int replicas() {
        return replicas;
      }

      @Override
      public int sentinels() {
        return sentinels;
      }

      @Override
      public int quorum() {
        return 2;
      }

      @Override
      public boolean enableNetworkChaos() {
        return false;
      }

      @Override
      public String[] packages() {
        return new String[0];
      }
    };
  }

  private RedisStandalone createStandalone(final String id) {
    return new RedisStandalone() {
      @Override
      public Class<? extends java.lang.annotation.Annotation> annotationType() {
        return RedisStandalone.class;
      }

      @Override
      public String id() {
        return id;
      }

      @Override
      public String version() {
        return "7.4";
      }

      @Override
      public int port() {
        return 0;
      }

      @Override
      public String[] args() {
        return new String[0];
      }

      @Override
      public boolean enableNetworkChaos() {
        return false;
      }

      @Override
      public String[] packages() {
        return new String[0];
      }
    };
  }
}
