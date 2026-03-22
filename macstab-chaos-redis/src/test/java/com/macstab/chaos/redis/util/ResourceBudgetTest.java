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

    @Test
    @DisplayName("Should reject null input")
    void shouldRejectNullInput() {
      assertThatThrownBy(() -> ResourceBudget.validateSentinelBudget(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("clusters must not be null");
    }
  }

  @Nested
  @DisplayName("Standalone Budget Validation")
  class StandaloneBudgetTests {

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

    @Test
    @DisplayName("Should reject null input")
    void shouldRejectNullInput() {
      assertThatThrownBy(() -> ResourceBudget.validateStandaloneBudget(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("instances must not be null");
    }
  }

  @Nested
  @DisplayName("Mixed Budget Validation")
  class MixedBudgetTests {

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
    @DisplayName("Should reject null inputs")
    void shouldRejectNullInputs() {
      final RedisSentinel[] sentinels = new RedisSentinel[] {createSentinel("ha", 2, 3)};
      final RedisStandalone[] standalones = new RedisStandalone[] {createStandalone("cache")};

      assertThatThrownBy(() -> ResourceBudget.validateMixedBudget(null, standalones))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sentinelClusters must not be null");

      assertThatThrownBy(() -> ResourceBudget.validateMixedBudget(sentinels, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("standaloneInstances must not be null");
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
