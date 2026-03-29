/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.exception.ClusterStartupException;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.extension.RedisContainerExtension.Store;

/**
 * Unit tests for {@link StandaloneStartupOrchestrator}.
 *
 * <p>All Docker I/O is eliminated via the injected {@link StandaloneContainerInstanceFactory}.
 * Tests cover every orchestration branch: success, typed failure, timeout, execution error,
 * interrupt, cleanup, and executor lifecycle.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StandaloneStartupOrchestrator")
class StandaloneStartupOrchestratorTest {

  // ─── start() ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("start()")
  class StartTests {

    @Test
    @DisplayName("Returns StartupOutcome with connection info and stores when all succeed")
    void shouldReturnOutcomeOnAllSuccess() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Store store = buildStore(info);
      final StandaloneContainerInstanceFactory factory =
          annotation -> new StartupResult.Success(annotation.id(), info, store);
      final StandaloneStartupOrchestrator orchestrator = new StandaloneStartupOrchestrator(factory);

      // ACT
      final StandaloneStartupOrchestrator.StartupOutcome outcome =
          orchestrator.start(new RedisStandalone[] {buildAnnotation("main")});

      // ASSERT
      assertThat(outcome.instances()).containsKey("main");
      assertThat(outcome.stores()).containsKey("main");
    }

    @Test
    @DisplayName("Throws ClusterStartupException when factory returns Failure")
    void shouldThrowOnFactoryFailure() {
      // ARRANGE
      final StandaloneContainerInstanceFactory factory =
          annotation ->
              new StartupResult.Failure(
                  annotation.id(), "docker error", new RuntimeException("docker error"));
      final StandaloneStartupOrchestrator orchestrator = new StandaloneStartupOrchestrator(factory);

      // ACT & ASSERT
      assertThatThrownBy(
              () -> orchestrator.start(new RedisStandalone[] {buildAnnotation("broken")}))
          .isInstanceOf(ClusterStartupException.class);
    }
  }

  // ─── collectResults() ────────────────────────────────────────────────────

  @Nested
  @DisplayName("collectResults()")
  class CollectResultsTests {

    @Test
    @DisplayName("Success result populates both connection info map and stores map")
    void shouldPopulateBothMapsOnSuccess() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Store store = buildStore(info);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(
              annotation -> new StartupResult.Success("x", info, store));

      final Map<String, RedisConnectionInfo> successful = new java.util.LinkedHashMap<>();
      final Map<String, Store> successfulStores = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();
      final List<Store> toCleanup = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(successFuture("main", info, store)),
          new RedisStandalone[] {buildAnnotation("main")},
          successful,
          successfulStores,
          failures,
          toCleanup);

      // ASSERT
      assertThat(successful).containsKey("main");
      assertThat(successful.get("main")).isSameAs(info);
      assertThat(successfulStores).containsKey("main");
      assertThat(successfulStores.get("main")).isSameAs(store);
      assertThat(toCleanup).containsExactly(store);
      assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("Failure result goes to failures list, maps stay empty")
    void shouldPlaceFailureIntoFailuresList() throws Exception {
      // ARRANGE
      final RuntimeException cause = new RuntimeException("image pull failed");
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(
              annotation -> new StartupResult.Failure(annotation.id(), "image pull failed", cause));

      final Map<String, RedisConnectionInfo> successful = new java.util.LinkedHashMap<>();
      final Map<String, Store> successfulStores = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();
      final List<Store> toCleanup = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(failureFuture("broken", "image pull failed", cause)),
          new RedisStandalone[] {buildAnnotation("broken")},
          successful,
          successfulStores,
          failures,
          toCleanup);

      // ASSERT
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("broken");
      assertThat(failures.get(0).getErrorMessage()).isEqualTo("image pull failed");
      assertThat(successful).isEmpty();
      assertThat(successfulStores).isEmpty();
      assertThat(toCleanup).isEmpty();
    }

    @Test
    @DisplayName("TimeoutException cancels future and records failure with timeout message")
    @SuppressWarnings("unchecked")
    void shouldRecordFailureOnTimeout() throws Exception {
      // ARRANGE
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));
      final Future<StartupResult> future = mock(Future.class);
      Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenThrow(new TimeoutException("docker is slow"));

      final Map<String, RedisConnectionInfo> successful = new java.util.LinkedHashMap<>();
      final Map<String, Store> successfulStores = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();
      final List<Store> toCleanup = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(future),
          new RedisStandalone[] {buildAnnotation("slow")},
          successful,
          successfulStores,
          failures,
          toCleanup);

      // ASSERT
      verify(future).cancel(true);
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("slow");
      assertThat(failures.get(0).getErrorMessage()).contains("60s");
      assertThat(successful).isEmpty();
    }

    @Test
    @DisplayName("ExecutionException records failure with message")
    @SuppressWarnings("unchecked")
    void shouldRecordFailureOnExecutionException() throws Exception {
      // ARRANGE
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));
      final Future<StartupResult> future = mock(Future.class);
      Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenThrow(new ExecutionException("wrapped", new RuntimeException("port conflict")));

      final Map<String, RedisConnectionInfo> successful = new java.util.LinkedHashMap<>();
      final Map<String, Store> successfulStores = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();
      final List<Store> toCleanup = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(future),
          new RedisStandalone[] {buildAnnotation("exec-fail")},
          successful,
          successfulStores,
          failures,
          toCleanup);

      // ASSERT
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("exec-fail");
      assertThat(successful).isEmpty();
    }

    @Test
    @DisplayName("InterruptedException records failure")
    @SuppressWarnings("unchecked")
    void shouldRecordFailureOnInterrupt() throws Exception {
      // ARRANGE
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));
      final Future<StartupResult> future = mock(Future.class);
      Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
          .thenThrow(new InterruptedException("test interrupted"));

      final Map<String, RedisConnectionInfo> successful = new java.util.LinkedHashMap<>();
      final Map<String, Store> successfulStores = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();
      final List<Store> toCleanup = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(future),
          new RedisStandalone[] {buildAnnotation("interrupted")},
          successful,
          successfulStores,
          failures,
          toCleanup);

      // ASSERT
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("interrupted");
      assertThat(successful).isEmpty();
      assertThat(toCleanup).isEmpty();
    }

    @Test
    @DisplayName("Mixed outcomes: one success and one failure are correctly separated")
    void shouldSeparateMixedOutcomes() throws Exception {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6380);
      final Store store = buildStore(info);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(
              annotation -> new StartupResult.Success("x", info, store));

      final Map<String, RedisConnectionInfo> successful = new java.util.LinkedHashMap<>();
      final Map<String, Store> successfulStores = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();
      final List<Store> toCleanup = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(
              successFuture("ok", info, store),
              failureFuture("fail", "dead", new RuntimeException())),
          new RedisStandalone[] {buildAnnotation("ok"), buildAnnotation("fail")},
          successful,
          successfulStores,
          failures,
          toCleanup);

      // ASSERT
      assertThat(successful).containsOnlyKeys("ok");
      assertThat(failures).hasSize(1);
      assertThat(failures.get(0).getClusterId()).isEqualTo("fail");
      assertThat(toCleanup).hasSize(1);
    }

    @Test
    @DisplayName("Insertion order of successful instances is preserved (LinkedHashMap)")
    void shouldPreserveInsertionOrder() throws Exception {
      // ARRANGE
      final RedisConnectionInfo i1 = new RedisConnectionInfo("localhost", 6379);
      final RedisConnectionInfo i2 = new RedisConnectionInfo("localhost", 6380);
      final RedisConnectionInfo i3 = new RedisConnectionInfo("localhost", 6381);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(
              annotation -> new StartupResult.Success("x", i1, buildStore(i1)));

      final Map<String, RedisConnectionInfo> successful = new java.util.LinkedHashMap<>();
      final Map<String, Store> successfulStores = new java.util.LinkedHashMap<>();
      final List<ClusterStartupException.ClusterStartupFailure> failures = new ArrayList<>();
      final List<Store> toCleanup = new ArrayList<>();

      // ACT
      orchestrator.collectResults(
          List.of(
              successFuture("alpha", i1, buildStore(i1)),
              successFuture("beta", i2, buildStore(i2)),
              successFuture("gamma", i3, buildStore(i3))),
          new RedisStandalone[] {
            buildAnnotation("alpha"), buildAnnotation("beta"), buildAnnotation("gamma")
          },
          successful,
          successfulStores,
          failures,
          toCleanup);

      // ASSERT
      assertThat(successful.keySet()).containsExactly("alpha", "beta", "gamma");
      assertThat(successfulStores.keySet()).containsExactly("alpha", "beta", "gamma");
    }
  }

  // ─── cleanupInstances() ──────────────────────────────────────────────────

  @Nested
  @DisplayName("cleanupInstances()")
  class CleanupInstancesTests {

    @Test
    @DisplayName("Calls close() on each store and returns action descriptions")
    @SuppressWarnings("rawtypes")
    void shouldCloseAllStoresAndReturnActions() {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final GenericContainer container1 = mock(GenericContainer.class);
      final GenericContainer container2 = mock(GenericContainer.class);
      final Store store1 = new Store(container1, info);
      final Store store2 = new Store(container2, info);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

      // ACT
      final List<String> actions = orchestrator.cleanupInstances(List.of(store1, store2));

      // ASSERT
      verify(container1).stop();
      verify(container2).stop();
      assertThat(actions).hasSize(2);
    }

    @Test
    @DisplayName("Exception during close is swallowed and recorded as failure action")
    @SuppressWarnings("rawtypes")
    void shouldRecordFailedCleanupWithoutRethrow() {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final GenericContainer container = mock(GenericContainer.class);
      Mockito.doThrow(new RuntimeException("stop failed")).when(container).stop();
      final Store store = new Store(container, info);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

      // ACT
      final List<String> actions = orchestrator.cleanupInstances(List.of(store));

      // ASSERT
      assertThat(actions).hasSize(1);
      assertThat(actions.get(0)).contains("stop failed");
    }

    @Test
    @DisplayName("Empty list returns empty action list")
    void shouldReturnEmptyForEmptyInput() {
      // ARRANGE
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

      // ACT & ASSERT
      assertThat(orchestrator.cleanupInstances(List.of())).isEmpty();
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
      final Future<StartupResult> f = mock(Future.class);
      Mockito.when(executor.submit(Mockito.any(java.util.concurrent.Callable.class))).thenReturn(f);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

      // ACT
      final List<Future<StartupResult>> futures =
          orchestrator.submitStartupTasks(
              executor, new RedisStandalone[] {buildAnnotation("a"), buildAnnotation("b")});

      // ASSERT
      assertThat(futures).hasSize(2);
      Mockito.verify(executor, Mockito.times(2))
          .submit(Mockito.any(java.util.concurrent.Callable.class));
    }

    @Test
    @DisplayName("Empty annotation array returns empty future list")
    void shouldReturnEmptyListForNoAnnotations() {
      // ARRANGE
      final ExecutorService executor = mock(ExecutorService.class);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

      // ACT & ASSERT
      assertThat(orchestrator.submitStartupTasks(executor, new RedisStandalone[0])).isEmpty();
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
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

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
          .thenReturn(false);
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

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
      final StandaloneStartupOrchestrator orchestrator =
          new StandaloneStartupOrchestrator(annotation -> mock(StartupResult.class));

      // ACT
      orchestrator.shutdownExecutor(executor);

      // ASSERT
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      Thread.interrupted(); // clean up
    }
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static Future<StartupResult> successFuture(
      final String instanceId, final RedisConnectionInfo info, final Store store) throws Exception {
    final Future<StartupResult> future = mock(Future.class);
    Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
        .thenReturn(new StartupResult.Success(instanceId, info, store));
    return future;
  }

  @SuppressWarnings("unchecked")
  private static Future<StartupResult> failureFuture(
      final String instanceId, final String message, final Exception cause) throws Exception {
    final Future<StartupResult> future = mock(Future.class);
    Mockito.when(future.get(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
        .thenReturn(new StartupResult.Failure(instanceId, message, cause));
    return future;
  }

  private static RedisStandalone buildAnnotation(final String id) {
    return new RedisStandalone() {
      @Override
      public Class<RedisStandalone> annotationType() {
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

  @SuppressWarnings("rawtypes")
  private static Store buildStore(final RedisConnectionInfo info) {
    return new Store(mock(GenericContainer.class), info);
  }
}
