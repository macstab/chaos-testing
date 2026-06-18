/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.control.role.ContainerRole;

/** Unit tests for the sealed ClusterException hierarchy. */
@DisplayName("ClusterException Sealed Hierarchy")
class ClusterExceptionHierarchyTest {

  @Nested
  @DisplayName("ClusterCreationException")
  class ClusterCreationExceptionTests {

    @Test
    @DisplayName("Should create with message only")
    void shouldCreateWithMessageOnly() {
      final ClusterCreationException ex = new ClusterCreationException("creation failed");
      assertThat(ex).isInstanceOf(ClusterException.class);
      assertThat(ex.getMessage()).contains("creation failed");
      assertThat(ex.getComponentIndex()).isEqualTo(-1);
      assertThat(ex.getTotalComponents()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Should create with component context")
    void shouldCreateWithComponentContext() {
      final RuntimeException cause = new RuntimeException("root");
      final ClusterCreationException ex = new ClusterCreationException("Failed", 2, 3, cause);
      assertThat(ex.getMessage()).contains("2/3");
      assertThat(ex.getCause()).isSameAs(cause);
      assertThat(ex.getComponentIndex()).isEqualTo(2);
      assertThat(ex.getTotalComponents()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should be final (not extensible at runtime)")
    void shouldBeFinal() {
      assertThat(ClusterCreationException.class.getModifiers())
          .satisfies(m -> assertThat(java.lang.reflect.Modifier.isFinal(m)).isTrue());
    }
  }

  @Nested
  @DisplayName("ClusterStartupException")
  class ClusterStartupExceptionTests {

    @Test
    @DisplayName("Should create with failures and cleanup actions")
    void shouldCreateWithFailures() {
      final ClusterStartupException.ClusterStartupFailure failure =
          new ClusterStartupException.ClusterStartupFailure("cluster-1", "timeout", null);
      final ClusterStartupException ex =
          new ClusterStartupException(List.of(failure), List.of("cleaned up cluster-1"));

      assertThat(ex).isInstanceOf(ClusterException.class);
      assertThat(ex.getFailures()).hasSize(1);
      assertThat(ex.getCleanupActions()).containsExactly("cleaned up cluster-1");
    }

    @Test
    @DisplayName("Should throw for empty failures list")
    void shouldThrowForEmptyFailures() {
      assertThatThrownBy(() -> new ClusterStartupException(List.of(), List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ClusterStartupFailure should validate required fields")
    void failureShouldValidateFields() {
      assertThatThrownBy(() -> new ClusterStartupException.ClusterStartupFailure(null, "msg", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("clusterId");
      assertThatThrownBy(() -> new ClusterStartupException.ClusterStartupFailure("id", null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("errorMessage");
    }
  }

  @Nested
  @DisplayName("ClusterTopologyException")
  class ClusterTopologyExceptionTests {

    @Test
    @DisplayName("Should create with simple message")
    void shouldCreateWithSimpleMessage() {
      final ClusterTopologyException ex = new ClusterTopologyException("no master");
      assertThat(ex).isInstanceOf(ClusterException.class);
      assertThat(ex.getMessage()).contains("no master");
      assertThat(ex.getRunningContainers()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Should create with diagnostic context")
    void shouldCreateWithDiagnosticContext() {
      final Map<ContainerRole, Integer> roles = Map.of(ContainerRole.MASTER, 0);
      final ClusterTopologyException ex = new ClusterTopologyException("no master", 3, roles);
      assertThat(ex.getRunningContainers()).isEqualTo(3);
      assertThat(ex.getRoleDistribution()).containsKey(ContainerRole.MASTER);
      assertThat(ex.getMessage()).contains("3 running containers");
    }
  }

  @Nested
  @DisplayName("FailoverException")
  class FailoverExceptionTests {

    @Test
    @DisplayName("Should create with simple message")
    void shouldCreateWithSimpleMessage() {
      final FailoverException ex = new FailoverException("failover timed out");
      assertThat(ex).isInstanceOf(ClusterException.class);
      assertThat(ex.getMessage()).contains("failover timed out");
      assertThat(ex.getTimeout()).isNull();
      assertThat(ex.getElapsed()).isNull();
      assertThat(ex.getRetryCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should create with timing context")
    void shouldCreateWithTimingContext() {
      final FailoverException ex =
          new FailoverException("timed out", Duration.ofSeconds(30), Duration.ofSeconds(35), 5);
      assertThat(ex.getMessage()).contains("30").contains("35").contains("5");
      assertThat(ex.getTimeout()).isEqualTo(Duration.ofSeconds(30));
      assertThat(ex.getElapsed()).isEqualTo(Duration.ofSeconds(35));
      assertThat(ex.getRetryCount()).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Sealed hierarchy")
  class SealedHierarchy {

    @Test
    @DisplayName("Pattern matching should be exhaustive")
    void shouldSupportExhaustivePatternMatching() {
      // ARRANGE: create one of each
      final ClusterException creation = new ClusterCreationException("msg");
      final ClusterException startup =
          new ClusterStartupException(
              List.of(new ClusterStartupException.ClusterStartupFailure("id", "err", null)),
              List.of());
      final ClusterException topology = new ClusterTopologyException("msg");
      final ClusterException failover = new FailoverException("msg");

      // ACT & ASSERT: pattern matching
      assertType(creation, "creation");
      assertType(startup, "startup");
      assertType(topology, "topology");
      assertType(failover, "failover");
    }

    private String assertType(final ClusterException ex, final String expectedType) {
      final String result =
          switch (ex) {
            case ClusterCreationException e -> "creation";
            case ClusterStartupException e -> "startup";
            case ClusterTopologyException e -> "topology";
            case FailoverException e -> "failover";
          };
      assertThat(result).isEqualTo(expectedType);
      return result;
    }
  }
}
