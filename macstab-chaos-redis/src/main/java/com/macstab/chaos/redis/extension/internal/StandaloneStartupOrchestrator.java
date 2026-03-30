/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.exception.ClusterStartupException;
import com.macstab.chaos.redis.exception.ClusterStartupException.ClusterStartupFailure;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.extension.RedisContainerExtension.Store;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates parallel startup of multiple standalone Redis instances.
 *
 * <p>Handles parallelism, failure isolation, and cleanup on partial failure. All Docker I/O is
 * delegated to the injected {@link StandaloneContainerInstanceFactory}, making this class fully
 * unit-testable with mock factories.
 *
 * <p><strong>Coverage note:</strong> This class achieves 90%+ unit coverage. The only untested path
 * is the production wiring in {@link DefaultStandaloneContainerInstanceFactory}, which requires
 * Docker and is covered by Testcontainers integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
@Slf4j
public final class StandaloneStartupOrchestrator {

  /** Maximum wait time for all instances to start. */
  static final long STARTUP_TIMEOUT_SECONDS = 60;

  private final StandaloneContainerInstanceFactory instanceFactory;

  /** Production constructor — uses the default Docker-backed factory. */
  public StandaloneStartupOrchestrator() {
    this(DefaultStandaloneContainerInstanceFactory.INSTANCE);
  }

  /**
   * Injectable constructor for testing — accepts any {@link StandaloneContainerInstanceFactory}.
   *
   * @param instanceFactory factory that creates and starts instances (must not be null)
   */
  public StandaloneStartupOrchestrator(final StandaloneContainerInstanceFactory instanceFactory) {
    this.instanceFactory = Objects.requireNonNull(instanceFactory, "instanceFactory");
  }

  /**
   * Outcome record holding both connection info map and store map.
   *
   * @param instances map of instance ID → connection info
   * @param stores map of instance ID → store (container + connection info)
   */
  public record StartupOutcome(
      Map<String, RedisConnectionInfo> instances, Map<String, Store> stores) {}

  /**
   * Starts all standalone instances in parallel with failure isolation.
   *
   * @param annotations instance annotations (must not be null)
   * @return StartupOutcome with all connection infos and stores
   * @throws ClusterStartupException if any instance fails
   */
  public StartupOutcome start(final RedisStandalone[] annotations) throws ClusterStartupException {

    final ExecutorService executor = Executors.newFixedThreadPool(annotations.length);
    final List<Future<StartupResult>> futures = submitStartupTasks(executor, annotations);

    final Map<String, RedisConnectionInfo> successful = new LinkedHashMap<>();
    final Map<String, Store> successfulStores = new LinkedHashMap<>();
    final List<ClusterStartupFailure> failures = new ArrayList<>();
    final List<Store> toCleanup = new ArrayList<>();

    collectResults(futures, annotations, successful, successfulStores, failures, toCleanup);
    shutdownExecutor(executor);

    if (!failures.isEmpty()) {
      final List<String> cleanupActions = cleanupInstances(toCleanup);
      throw new ClusterStartupException(failures, cleanupActions);
    }

    return new StartupOutcome(successful, successfulStores);
  }

  /**
   * Submits startup tasks for all annotations to the executor.
   *
   * @param executor executor service
   * @param annotations instance annotations
   * @return list of futures in annotation order
   */
  List<Future<StartupResult>> submitStartupTasks(
      final ExecutorService executor, final RedisStandalone[] annotations) {
    final List<Future<StartupResult>> futures = new ArrayList<>();
    for (final RedisStandalone annotation : annotations) {
      futures.add(executor.submit(() -> instanceFactory.create(annotation)));
    }
    return futures;
  }

  /**
   * Collects results from all futures.
   *
   * @param futures futures in annotation order
   * @param annotations instance annotations
   * @param successful out: successful connection infos
   * @param successfulStores out: successful stores
   * @param failures out: failures
   * @param toCleanup out: stores to clean up on failure
   */
  void collectResults(
      final List<Future<StartupResult>> futures,
      final RedisStandalone[] annotations,
      final Map<String, RedisConnectionInfo> successful,
      final Map<String, Store> successfulStores,
      final List<ClusterStartupFailure> failures,
      final List<Store> toCleanup) {

    for (int i = 0; i < futures.size(); i++) {
      final String instanceId = annotations[i].id();
      try {
        final StartupResult result = futures.get(i).get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        switch (result) {
          case StartupResult.Success s -> {
            successful.put(s.instanceId(), s.connectionInfo());
            successfulStores.put(s.instanceId(), s.store());
            toCleanup.add(s.store());
            log.info("Instance '{}' started successfully", s.instanceId());
          }
          case StartupResult.Failure f -> {
            failures.add(new ClusterStartupFailure(f.instanceId(), f.errorMessage(), f.error()));
            log.error("Instance '{}' failed: {}", f.instanceId(), f.errorMessage());
          }
        }
      } catch (final TimeoutException e) {
        futures.get(i).cancel(true);
        failures.add(
            new ClusterStartupFailure(
                instanceId, "Startup timeout after " + STARTUP_TIMEOUT_SECONDS + "s", e));
        log.error("Instance '{}' startup timed out", instanceId);
      } catch (final ExecutionException | InterruptedException e) {
        failures.add(
            new ClusterStartupFailure(instanceId, "Unexpected error: " + e.getMessage(), e));
        log.error("Instance '{}' unexpected error", instanceId, e);
      }
    }
  }

  /** Cleans up stores (stops containers) after partial failure. */
  List<String> cleanupInstances(final List<Store> stores) {
    final List<String> actions = new ArrayList<>();
    for (final Store store : stores) {
      try {
        store.close();
        actions.add("Stopped container: " + store.getConnectionInfo());
      } catch (final Exception e) {
        actions.add("Failed to stop container: " + e.getMessage());
        log.warn("Cleanup failed: {}", e.getMessage());
      }
    }
    return actions;
  }

  /** Shuts down the executor gracefully. */
  void shutdownExecutor(final ExecutorService executor) {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Some startup threads did not terminate within 5 seconds");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
