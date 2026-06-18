/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.macstab.chaos.redis.extension.SentinelCluster;

/** Unit tests for {@link ClusterStartupResult} sealed interface. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClusterStartupResult sealed interface")
class ClusterStartupResultTest {

  @Nested
  @DisplayName("Success record")
  class SuccessRecord {

    @Test
    @DisplayName("clusterId and cluster are accessible")
    void shouldExposeClusterIdAndCluster() {
      // ARRANGE
      final SentinelCluster cluster = mock(SentinelCluster.class);

      // ACT
      final ClusterStartupResult result = new ClusterStartupResult.Success("primary", cluster);

      // ASSERT
      assertThat(result.clusterId()).isEqualTo("primary");
      assertThat(((ClusterStartupResult.Success) result).cluster()).isSameAs(cluster);
    }

    @Test
    @DisplayName("Pattern matching via switch resolves to Success")
    void shouldMatchAsSuccess() {
      // ARRANGE
      final SentinelCluster cluster = mock(SentinelCluster.class);
      final ClusterStartupResult result = new ClusterStartupResult.Success("id", cluster);

      // ACT
      final String matched;
      switch (result) {
        case ClusterStartupResult.Success s -> matched = "success:" + s.clusterId();
        case ClusterStartupResult.Failure f -> matched = "failure";
      }

      // ASSERT
      assertThat(matched).isEqualTo("success:id");
    }
  }

  @Nested
  @DisplayName("Failure record")
  class FailureRecord {

    @Test
    @DisplayName("clusterId, errorMessage, and cause are accessible")
    void shouldExposeAllFields() {
      // ARRANGE
      final RuntimeException cause = new RuntimeException("boom");

      // ACT
      final ClusterStartupResult result =
          new ClusterStartupResult.Failure("cluster-1", "Connection refused", cause);

      // ASSERT
      assertThat(result.clusterId()).isEqualTo("cluster-1");
      final ClusterStartupResult.Failure f = (ClusterStartupResult.Failure) result;
      assertThat(f.errorMessage()).isEqualTo("Connection refused");
      assertThat(f.cause()).isSameAs(cause);
    }

    @Test
    @DisplayName("Pattern matching via switch resolves to Failure")
    void shouldMatchAsFailure() {
      // ARRANGE
      final ClusterStartupResult result =
          new ClusterStartupResult.Failure("id", "error", new Exception("oops"));

      // ACT
      final String matched;
      switch (result) {
        case ClusterStartupResult.Success s -> matched = "success";
        case ClusterStartupResult.Failure f -> matched = "failure:" + f.clusterId();
      }

      // ASSERT
      assertThat(matched).isEqualTo("failure:id");
    }

    @Test
    @DisplayName("Failure with null cause is valid")
    void shouldAcceptNullCause() {
      // ACT
      final ClusterStartupResult result = new ClusterStartupResult.Failure("id", "timeout", null);

      // ASSERT
      assertThat(((ClusterStartupResult.Failure) result).cause()).isNull();
    }
  }
}
