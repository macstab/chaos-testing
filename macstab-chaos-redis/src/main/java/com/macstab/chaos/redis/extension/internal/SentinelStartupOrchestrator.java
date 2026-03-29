/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import java.util.ArrayList;
import java.util.Collection;
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

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.exception.ClusterStartupException;
import com.macstab.chaos.redis.exception.ClusterStartupException.ClusterStartupFailure;
import com.macstab.chaos.redis.extension.SentinelCluster;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates parallel startup of multiple Redis Sentinel clusters.
 *
 * <p>Handles parallelism, failure isolation, and cleanup on partial failure. All Docker I/O is
 * delegated to the injected {@link SentinelClusterFactory}, making this class fully unit-testable
 * with mock factories.
 *
 * <p><strong>Lifecycle:</strong> Create once per {@code beforeAll}, call {@link #start}, discard.
 *
 * <p><strong>Coverage note:</strong> This class achieves 90%+ unit coverage. The only untested path
 * is the production wiring in {@link DefaultSentinelClusterFactory}, which requires Docker and is
 * covered by Testcontainers integration tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
@Slf4j
public final class SentinelStartupOrchestrator {

  /** Maximum wait time for all clusters to start. */
  static final long STARTUP_TIMEOUT_SECONDS = 120;

  private final SentinelClusterFactory clusterFactory;

  /** Production constructor — uses the default Docker-backed factory. */
  public SentinelStartupOrchestrator() {
    this(DefaultSentinelClusterFactory.INSTANCE);
  }

  /**
   * Injectable constructor for testing — accepts any {@link SentinelClusterFactory}.
   *
   * @param clusterFactory factory that creates and starts clusters (must not be null)
   */
  public SentinelStartupOrchestrator(final SentinelClusterFactory clusterFactory) {
    this.clusterFactory = Objects.requireNonNull(clusterFactory, "clusterFactory");
  }

  /**
   * Starts all Sentinel clusters in parallel with failure isolation.
   *
   * @param annotations cluster annotations (must not be null)
   * @return ordered map of cluster ID → started cluster
   * @throws ClusterStartupException if any cluster fails (after cleanup of successful ones)
   */
  public Map<String, SentinelCluster> start(final RedisSentinel[] annotations)
      throws ClusterStartupException {

    final ExecutorService executor = Executors.newFixedThreadPool(annotations.length);
    final List<Future<ClusterStartupResult>> futures = submitStartupTasks(executor, annotations);
    final Map<String, SentinelCluster> successful = new LinkedHashMap<>();
    final List<ClusterStartupFailure> failures = new ArrayList<>();

    collectResults(futures, annotations, successful, failures);
    shutdownExecutor(executor);

    if (!failures.isEmpty()) {
      final List<String> cleanupActions = cleanupClusters(successful.values());
      throw new ClusterStartupException(failures, cleanupActions);
    }

    return successful;
  }

  /**
   * Submits startup tasks for all annotations to the executor.
   *
   * @param executor executor service
   * @param annotations cluster annotations
   * @return list of futures in annotation order
   */
  List<Future<ClusterStartupResult>> submitStartupTasks(
      final ExecutorService executor, final RedisSentinel[] annotations) {
    final List<Future<ClusterStartupResult>> futures = new ArrayList<>();
    for (final RedisSentinel annotation : annotations) {
      futures.add(executor.submit(() -> startSingleCluster(annotation)));
    }
    return futures;
  }

  /**
   * Collects results from futures, populating success/failure maps.
   *
   * @param futures futures in annotation order
   * @param annotations cluster annotations
   * @param successful out: map of successful clusters
   * @param failures out: list of failures
   */
  void collectResults(
      final List<Future<ClusterStartupResult>> futures,
      final RedisSentinel[] annotations,
      final Map<String, SentinelCluster> successful,
      final List<ClusterStartupFailure> failures) {

    for (int i = 0; i < futures.size(); i++) {
      final String clusterId = annotations[i].id();
      try {
        final ClusterStartupResult result =
            futures.get(i).get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        switch (result) {
          case ClusterStartupResult.Success s -> {
            successful.put(s.clusterId(), s.cluster());
            log.info("Cluster '{}' started successfully", s.clusterId());
          }
          case ClusterStartupResult.Failure f -> {
            failures.add(new ClusterStartupFailure(f.clusterId(), f.errorMessage(), f.cause()));
            log.error("Cluster '{}' failed: {}", f.clusterId(), f.errorMessage());
          }
        }
      } catch (final TimeoutException e) {
        futures.get(i).cancel(true);
        failures.add(
            new ClusterStartupFailure(
                clusterId, "Startup timeout after " + STARTUP_TIMEOUT_SECONDS + "s", e));
        log.error("Cluster '{}' startup timed out", clusterId);
      } catch (final ExecutionException | InterruptedException e) {
        failures.add(
            new ClusterStartupFailure(
                clusterId, "Unexpected error during startup: " + e.getMessage(), e));
        log.error("Cluster '{}' encountered unexpected error", clusterId, e);
      }
    }
  }

  /**
   * Starts a single cluster via the injected factory and returns a typed result.
   *
   * @param annotation cluster annotation
   * @return typed result (never null)
   */
  ClusterStartupResult startSingleCluster(final RedisSentinel annotation) {
    try {
      final SentinelCluster cluster = clusterFactory.create(annotation);
      return new ClusterStartupResult.Success(annotation.id(), cluster);
    } catch (final Exception e) {
      log.error("Failed to start cluster '{}'", annotation.id(), e);
      return new ClusterStartupResult.Failure(annotation.id(), e.getMessage(), e);
    }
  }

  /**
   * Cleans up successfully started clusters after partial failure.
   *
   * @param clusters clusters to stop
   * @return list of cleanup action descriptions
   */
  public List<String> cleanupClusters(final Collection<SentinelCluster> clusters) {
    final List<String> actions = new ArrayList<>();
    for (final SentinelCluster cluster : clusters) {
      try {
        cluster.stop();
        actions.add("Stopped cluster (cleanup after failure)");
        log.info("Stopped cluster during cleanup");
      } catch (final Exception e) {
        actions.add("Failed to stop cluster: " + e.getMessage());
        log.warn("Cleanup stop failed: {}", e.getMessage());
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
