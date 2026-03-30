/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.exception.ClusterStartupException;
import com.macstab.chaos.redis.extension.SentinelCluster;

/**
 * Unit tests for {@link SentinelStartupOrchestrator}.
 *
 * <p>All Docker I/O is eliminated via the injected {@link SentinelClusterFactory}. Tests cover
 * every orchestration branch: success, typed failure, timeout, execution error, interrupt, cleanup,
 * and executor lifecycle.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SentinelStartupOrchestrator")
class SentinelStartupOrchestratorTest {

  // ─── start() ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("start()")
  class StartTests {

    @Test
    @DisplayName("Returns map of started clusters when all succeed")
    void shouldReturnStartedClustersOnSuccess() throws Exception {
      // ARRANGE
      final SentinelCluster cluster = mock(SentinelCluster.class);
      final SentinelClusterFactory factory = annotation -> cluster;
      final SentinelStartupOrchestrator orchestrator = new SentinelStartupOrchestrator(factory);

      // ACT
      final Map<String, SentinelCluster> result =
          orchestrator.start(new RedisSentinel[] {buildAnnotation("alpha")});

      // ASSERT
      assertThat(result).containsOnlyKeys("alpha");
      assertThat(result.get("alpha")).isSameAs(cluster);
    }

    @Test
    @DisplayName("Throws ClusterStartupException and cleans up on factory failure")
    void shouldThrowAndCleanupOnFactoryFailure() {
      // ARRANGE
      final SentinelClusterFactory factory =
          annotation -> {
            throw new RuntimeException("docker unavailable");
          };
      final SentinelStartupOrchestrator orchestrator = new SentinelStartupOrchestrator(factory);

      // ACT & ASSERT
      assertThatThrownBy(() -> orchestrator.start(new RedisSentinel[] {buildAnnotation("broken")}))
          .isInstanceOf(ClusterStartupException.class);
    }

    @Test
    @DisplayName("Stops successful clusters when a parallel cluster fails")
    void shouldCleanupSuccessfulClustersOnPartialFailure() throws Exception {
      // ARRANGE
      final SentinelCluster okCluster = mock(SentinelCluster.class);
      final SentinelClusterFactory factory =
          annotation -> {
            if ("ok".equals(annotation.id())) {
              return okCluster;
            }
            throw new RuntimeException("fail");
          };
      final SentinelStartupOrchestrator orchestrator = new SentinelStartupOrchestrator(factory);

      // ACT
      assertThatThrownBy(
              () ->
                  orchestrator.start(
                      new RedisSentinel[] {buildAnnotation("ok"), buildAnnotation("broken")}))
          .isInstanceOf(ClusterStartupException.class);

      // ASSERT — ok cluster must be stopped during cleanup
      verify(okCluster).stop();
    }
  }

  // ─── startSingleCluster() ────────────────────────────────────────────────

  @Nested
  @DisplayName("startSingleCluster()")
  class StartSingleClusterTests {

    @Test
    @DisplayName("Returns Success when factory creates cluster without exception")
    void shouldReturnSuccessOnFactorySuccess() throws Exception {
      // ARRANGE
      final SentinelCluster cluster = mock(SentinelCluster.class);
      final SentinelClusterFactory factory = annotation -> cluster;
      final SentinelStartupOrchestrator orchestrator = new SentinelStartupOrchestrator(factory);

      // ACT
      final ClusterStartupResult result = orchestrator.startSingleCluster(buildAnnotation("id1"));

      // ASSERT
      assertThat(result).isInstanceOf(ClusterStartupResult.Success.class);
      final ClusterStartupResult.Success success = (ClusterStartupResult.Success) result;
      assertThat(success.clusterId()).isEqualTo("id1");
      assertThat(success.cluster()).isSameAs(cluster);
    }

    @Test
    @DisplayName("Returns Failure when factory throws exception")
    void shouldReturnFailureOnFactoryException() {
      // ARRANGE
      final RuntimeException cause = new RuntimeException("image pull failed");
      final SentinelClusterFactory factory =
          annotation -> {
            throw cause;
          };
      final SentinelStartupOrchestrator orchestrator = new SentinelStartupOrchestrator(factory);

      // ACT
      final ClusterStartupResult result = orchestrator.startSingleCluster(buildAnnotation("id2"));

      // ASSERT
      assertThat(result).isInstanceOf(ClusterStartupResult.Failure.class);
      final ClusterStartupResult.Failure failure = (ClusterStartupResult.Failure) result;
      assertThat(failure.clusterId()).isEqualTo("id2");
      assertThat(failure.cause()).isSameAs(cause);
    }

    @Test
    @DisplayName("Failure message contains original exception message")
    void shouldIncludeExceptionMessageInFailure() {
      // ARRANGE
      final SentinelClusterFactory factory =
          annotation -> {
            throw new RuntimeException("network timeout: connection refused");
          };
      final SentinelStartupOrchestrator orchestrator = new SentinelStartupOrchestrator(factory);

      // ACT
      final ClusterStartupResult result = orchestrator.startSingleCluster(buildAnnotation("id3"));

      // ASSERT
      assertThat(((ClusterStartupResult.Failure) result).errorMessage())
          .contains("network timeout");
    }
  }

  // ─── collectResults() ────────────────────────────────────────────────────

  @Nested
  @DisplayName("collectResults()")
  class CollectResultsTests {

    @Test
    @DisplayName("Success result is placed into the successful map with correct cluster ID")
    void shouldPlaceSuccessIntoSuccessfulMap() throws Exception {
      // ARRANGE
      final SentinelCluster cluster = mock(SentinelCluster.class);
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> cluster);

      final Map<String, SentinelCluster> successful = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(successFuture("primary", cluster)),
          new RedisSentinel[] {buildAnnotation("primary")},
          successful,
          failures);

      // ASSERT
      assertThat(successful).containsKey("primary");
      assertThat(successful.get("primary")).isSameAs(cluster);
      assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("Failure result is placed into the failures list with correct message")
    void shouldPlaceFailureIntoFailuresList() throws Exception {
      // ARRANGE
      final RuntimeException cause = new RuntimeException("container boom");
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(
              annotation -> {
                throw cause;
              });

      final Map<String, SentinelCluster> successful = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(failureFuture("secondary", "container boom", cause)),
          new RedisSentinel[] {buildAnnotation("secondary")},
          successful,
          failures);

      // ASSERT
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("secondary");
      assertThat(failures.get(0).getErrorMessage()).isEqualTo("container boom");
      assertThat(successful).isEmpty();
    }

    @Test
    @DisplayName("TimeoutException cancels future and records failure with timeout message")
    @SuppressWarnings("unchecked")
    void shouldRecordFailureOnTimeout() throws Exception {
      // ARRANGE
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));
      final Future<ClusterStartupResult> future = mock(Future.class);
      Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenThrow(new TimeoutException("took too long"));

      final Map<String, SentinelCluster> successful = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(future),
          new RedisSentinel[] {buildAnnotation("slow-cluster")},
          successful,
          failures);

      // ASSERT
      verify(future).cancel(true);
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("slow-cluster");
      assertThat(failures.get(0).getErrorMessage()).contains("120s");
      assertThat(successful).isEmpty();
    }

    @Test
    @DisplayName("ExecutionException records failure with wrapped message")
    @SuppressWarnings("unchecked")
    void shouldRecordFailureOnExecutionException() throws Exception {
      // ARRANGE
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));
      final Future<ClusterStartupResult> future = mock(Future.class);
      Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenThrow(new ExecutionException("wrapped", new RuntimeException("root cause")));

      final Map<String, SentinelCluster> successful = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(future),
          new RedisSentinel[] {buildAnnotation("failing-cluster")},
          successful,
          failures);

      // ASSERT
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("failing-cluster");
      assertThat(successful).isEmpty();
    }

    @Test
    @DisplayName("InterruptedException records failure")
    @SuppressWarnings("unchecked")
    void shouldRecordFailureOnInterrupt() throws Exception {
      // ARRANGE
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));
      final Future<ClusterStartupResult> future = mock(Future.class);
      Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenThrow(new InterruptedException("interrupted"));

      final Map<String, SentinelCluster> successful = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(future),
          new RedisSentinel[] {buildAnnotation("interrupted")},
          successful,
          failures);

      // ASSERT
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("interrupted");
      assertThat(successful).isEmpty();
    }

    @Test
    @DisplayName("Mixed outcomes: one success and one failure are correctly separated")
    void shouldSeparateMixedOutcomes() throws Exception {
      // ARRANGE
      final SentinelCluster cluster = mock(SentinelCluster.class);
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> cluster);

      final Map<String, SentinelCluster> successful = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(
              successFuture("ok", cluster),
              failureFuture("broken", "no docker", new RuntimeException())),
          new RedisSentinel[] {buildAnnotation("ok"), buildAnnotation("broken")},
          successful,
          failures);

      // ASSERT
      assertThat(successful).containsOnlyKeys("ok");
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("broken");
    }

    @Test
    @DisplayName("Insertion order of successful clusters is preserved (LinkedHashMap)")
    void shouldPreserveInsertionOrder() throws Exception {
      // ARRANGE
      final SentinelCluster c1 = mock(SentinelCluster.class);
      final SentinelCluster c2 = mock(SentinelCluster.class);
      final SentinelCluster c3 = mock(SentinelCluster.class);
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> c1);

      final Map<String, SentinelCluster> successful = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(
              successFuture("alpha", c1), successFuture("beta", c2), successFuture("gamma", c3)),
          new RedisSentinel[] {
            buildAnnotation("alpha"), buildAnnotation("beta"), buildAnnotation("gamma")
          },
          successful,
          failures);

      // ASSERT
      assertThat(successful.keySet()).containsExactly("alpha", "beta", "gamma");
    }
  }

  // ─── cleanupClusters() ───────────────────────────────────────────────────

  @Nested
  @DisplayName("cleanupClusters()")
  class CleanupClustersTests {

    @Test
    @DisplayName("Calls stop() on each cluster and returns action descriptions")
    void shouldStopAllClustersAndReturnActions() {
      // ARRANGE
      final SentinelCluster c1 = mock(SentinelCluster.class);
      final SentinelCluster c2 = mock(SentinelCluster.class);
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT
      final List<String> actions = orchestrator.cleanupClusters(List.of(c1, c2));

      // ASSERT
      verify(c1).stop();
      verify(c2).stop();
      assertThat(actions).hasSize(2);
    }

    @Test
    @DisplayName("Exception during stop is swallowed and recorded as failure action")
    void shouldRecordFailedCleanupWithoutRethrow() {
      // ARRANGE
      final SentinelCluster failing = mock(SentinelCluster.class);
      doThrow(new RuntimeException("stop failed")).when(failing).stop();
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT
      final List<String> actions = orchestrator.cleanupClusters(List.of(failing));

      // ASSERT
      assertThat(actions).hasSize(1);
      assertThat(actions.get(0)).contains("stop failed");
    }

    @Test
    @DisplayName("Empty collection returns empty action list")
    void shouldReturnEmptyListForEmptyInput() {
      // ARRANGE
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT & ASSERT
      assertThat(orchestrator.cleanupClusters(List.of())).isEmpty();
    }
  }

  // ─── submitStartupTasks() ────────────────────────────────────────────────

  @Nested
  @DisplayName("submitStartupTasks()")
  class SubmitStartupTasksTests {

    @Test
    @DisplayName("Returns one future per annotation")
    @SuppressWarnings("unchecked")
    void shouldReturnOneFuturePerAnnotation() {
      // ARRANGE
      final ExecutorService executor = mock(ExecutorService.class);
      final Future<ClusterStartupResult> f = mock(Future.class);
      Mockito.when(executor.submit(Mockito.any(java.util.concurrent.Callable.class))).thenReturn(f);
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT
      final List<Future<ClusterStartupResult>> futures =
          orchestrator.submitStartupTasks(
              executor,
              new RedisSentinel[] {
                buildAnnotation("a"), buildAnnotation("b"), buildAnnotation("c")
              });

      // ASSERT
      assertThat(futures).hasSize(3);
      Mockito.verify(executor, Mockito.times(3))
          .submit(Mockito.any(java.util.concurrent.Callable.class));
    }

    @Test
    @DisplayName("Empty annotation array returns empty future list")
    void shouldReturnEmptyListForNoAnnotations() {
      // ARRANGE
      final ExecutorService executor = mock(ExecutorService.class);
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT & ASSERT
      assertThat(orchestrator.submitStartupTasks(executor, new RedisSentinel[0])).isEmpty();
    }
  }

  // ─── shutdownExecutor() ──────────────────────────────────────────────────

  @Nested
  @DisplayName("shutdownExecutor()")
  class ShutdownExecutorTests {

    @Test
    @DisplayName("Calls shutdownNow() and awaitTermination on executor")
    void shouldShutdownAndAwaitTermination() throws Exception {
      // ARRANGE
      final ExecutorService executor = mock(ExecutorService.class);
      Mockito.when(executor.awaitTermination(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenReturn(true);
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT
      orchestrator.shutdownExecutor(executor);

      // ASSERT
      verify(executor).shutdownNow();
      verify(executor).awaitTermination(5L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Logs warning when executor does not terminate within timeout")
    void shouldHandleTerminationTimeout() throws Exception {
      // ARRANGE
      final ExecutorService executor = mock(ExecutorService.class);
      Mockito.when(executor.awaitTermination(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenReturn(false); // did not terminate

      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT — must not throw
      orchestrator.shutdownExecutor(executor);

      // ASSERT
      verify(executor).shutdownNow();
    }

    @Test
    @DisplayName("Sets interrupt flag when awaitTermination is interrupted")
    void shouldSetInterruptFlagOnInterrupt() throws Exception {
      // ARRANGE
      final ExecutorService executor = mock(ExecutorService.class);
      Mockito.when(executor.awaitTermination(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenThrow(new InterruptedException("shutdown interrupted"));
      final SentinelStartupOrchestrator orchestrator =
          new SentinelStartupOrchestrator(annotation -> mock(SentinelCluster.class));

      // ACT
      orchestrator.shutdownExecutor(executor);

      // ASSERT
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      Thread.interrupted(); // clean up
    }
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static Future<ClusterStartupResult> successFuture(
      final String clusterId, final SentinelCluster cluster) throws Exception {
    final Future<ClusterStartupResult> future = mock(Future.class);
    Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
        .thenReturn(new ClusterStartupResult.Success(clusterId, cluster));
    return future;
  }

  @SuppressWarnings("unchecked")
  private static Future<ClusterStartupResult> failureFuture(
      final String clusterId, final String message, final Exception cause) throws Exception {
    final Future<ClusterStartupResult> future = mock(Future.class);
    Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
        .thenReturn(new ClusterStartupResult.Failure(clusterId, message, cause));
    return future;
  }

  private static RedisSentinel buildAnnotation(final String id) {
    return new RedisSentinel() {
      @Override
      public Class<RedisSentinel> annotationType() {
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
        return 2;
      }

      @Override
      public int sentinels() {
        return 3;
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
}
