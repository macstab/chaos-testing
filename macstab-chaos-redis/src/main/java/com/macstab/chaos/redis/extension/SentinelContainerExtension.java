/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisSentinels;
import com.macstab.chaos.redis.api.SentinelRedis;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.extension.internal.SentinelStartupOrchestrator;
import com.macstab.chaos.redis.util.ResourceBudget;

import lombok.extern.slf4j.Slf4j;

/**
 * JUnit 5 extension managing Redis Sentinel clusters for {@link RedisSentinel} tests.
 *
 * <p><strong>Multi-Instance Support (v2.0):</strong>
 *
 * <ul>
 *   <li>✅ 1-3 Sentinel clusters per test class
 *   <li>✅ Parallel cluster startup (40-50% faster)
 *   <li>✅ Failure isolation (cleanup on error)
 *   <li>✅ Flexible parameter injection (single or {@code List<SentinelCluster>})
 *   <li>✅ Resource budget validation
 * </ul>
 *
 * <p><strong>Architecture Changes (v2.0 vs v1.x):</strong>
 *
 * <table border="1">
 *   <caption>v2.0 Architecture Enhancements</caption>
 *   <tr><th>Feature</th><th>v1.x</th><th>v2.0</th></tr>
 *   <tr><td>Max clusters</td><td>1</td><td>3 (budget-enforced)</td></tr>
 *   <tr><td>Startup mode</td><td>Sequential</td><td>Parallel</td></tr>
 *   <tr><td>Parameter injection</td><td>{@code SentinelCluster}</td><td>{@code SentinelCluster} or {@code List<SentinelCluster>}</td></tr>
 *   <tr><td>Failure handling</td><td>Basic</td><td>Isolated + detailed errors</td></tr>
 * </table>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0 (enhanced in 2.0)
 */
@Slf4j
public final class SentinelContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(SentinelContainerExtension.class);

  private static final String CLUSTER_MAP_KEY = "sentinel-clusters-map";
  private static final String ANNOTATIONS_KEY = "sentinel-annotations";

  /** ThreadLocal to hold current test context for INSTANCE.get() calls. */
  private static final ThreadLocal<ExtensionContext> CURRENT_CONTEXT = new ThreadLocal<>();

  /**
   * System property key for sentinel nodes (for Spring @TestPropertySource
   * / @DynamicPropertySource).
   */
  public static final String SENTINEL_NODES_PROPERTY = "sentinel.nodes";

  /**
   * Creates extension (stateless, instantiated by JUnit 5).
   *
   * <p>Extension state stored in {@link ExtensionContext.Store}.
   */
  public SentinelContainerExtension() {
    // Stateless - no initialization needed
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    CURRENT_CONTEXT.set(context);
    context.getStore(NAMESPACE).put("threadlocal-cleanup", new ThreadLocalCleanup());

    final RedisSentinel[] annotations = extractAnnotations(context);
    if (annotations.length == 0) {
      log.warn("No @RedisSentinel annotations found, skipping cluster startup");
      return;
    }

    validateBudgetOrThrow(annotations);
    context.getStore(NAMESPACE).put(ANNOTATIONS_KEY, annotations);
    log.info(
        "Starting {} Sentinel cluster(s) in parallel (budget validated: {} total containers expected)",
        annotations.length,
        calculateTotalContainers(annotations));

    final Map<String, SentinelCluster> clusters =
        new SentinelStartupOrchestrator().start(annotations);
    storeClustersAndExposeSystemProperty(context, clusters);
    log.info("Successfully started {} Sentinel cluster(s)", clusters.size());
  }

  private void validateBudgetOrThrow(final RedisSentinel[] annotations) {
    try {
      ResourceBudget.validateSentinelBudget(annotations);
    } catch (final ResourceBudget.ResourceBudgetExceededException e) {
      log.error("Resource budget validation failed: {}", e.getMessage());
      throw e;
    }
  }

  private void storeClustersAndExposeSystemProperty(
      final ExtensionContext context, final Map<String, SentinelCluster> clusters) {
    context.getStore(NAMESPACE).put(CLUSTER_MAP_KEY, new CloseableClusterMap(clusters));

    // Register each cluster's SentinelRedis into ChaosTestingExtension so that
    // RedisSentinel.INSTANCE.get(id) and ChaosContainers.get(RedisSentinel.class, id) work.
    clusters.forEach(
        (id, cluster) ->
            ChaosTestingExtension.registerExternalConnectionInfo(
                RedisSentinel.class, id, cluster.toSentinelRedis()));

    if (!clusters.isEmpty()) {
      final SentinelCluster firstCluster = clusters.values().iterator().next();
      final RedisConnectionInfo firstSentinel = firstCluster.getSentinels().get(0);
      final String sentinelNodes = firstSentinel.getHost() + ":" + firstSentinel.getPort();
      System.setProperty(SENTINEL_NODES_PROPERTY, sentinelNodes);
      log.info("Set system property {}={}", SENTINEL_NODES_PROPERTY, sentinelNodes);
    }
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    CURRENT_CONTEXT.remove();
    System.clearProperty(SENTINEL_NODES_PROPERTY);
    // Clusters auto-stop via Store.CloseableResource (SentinelCluster.close())
  }

  /**
   * Public accessor for {@code RedisSentinel.INSTANCE} ({@link
   * com.macstab.chaos.core.api.ContainerManager}).
   *
   * <p>Called via {@code RedisSentinel.INSTANCE.get(id)}.
   *
   * @param id cluster ID from {@code @RedisSentinel(id = "...")}
   * @return cluster info
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   */
  public static SentinelCluster getCluster(final String id) {
    final ExtensionContext context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException(
          "RedisSentinel.INSTANCE.get() called outside @RedisSentinel test context");
    }

    final Map<String, SentinelCluster> clusters = getClustersMap(context);

    // Smart default resolution: if only one cluster, return it regardless of ID
    if ("default".equals(id) && clusters.size() == 1) {
      return clusters.values().iterator().next();
    }

    final SentinelCluster cluster = clusters.get(id);
    if (cluster == null) {
      if ("default".equals(id) && clusters.size() > 1) {
        throw new IllegalArgumentException(
            "Multiple Sentinel clusters exist ("
                + clusters.keySet()
                + ") but no cluster with id=\"default\". "
                + "Use List<SentinelCluster> parameter or add @RedisSentinel(id=\"default\").");
      }
      throw new IllegalArgumentException(
          "No Sentinel cluster found with id='"
              + id
              + "'. Available: "
              + clusters.keySet()
              + ". "
              + "Did you add @RedisSentinel(id=\""
              + id
              + "\") to your test class?");
    }

    return cluster;
  }

  /**
   * Gets all clusters in annotation declaration order.
   *
   * <p>Called via {@code RedisSentinel.INSTANCE.getAll()}.
   *
   * @return list of all clusters in declaration order
   * @throws IllegalStateException if called outside test context
   */
  public static List<SentinelCluster> getAllClusters() {
    final ExtensionContext context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException(
          "RedisSentinel.INSTANCE.getAll() called outside @RedisSentinel test context");
    }

    final RedisSentinel[] annotations =
        context.getStore(NAMESPACE).get(ANNOTATIONS_KEY, RedisSentinel[].class);
    if (annotations == null || annotations.length == 0) {
      return Collections.emptyList();
    }

    final Map<String, SentinelCluster> clusters = getClustersMap(context);

    // Return in annotation declaration order
    final List<SentinelCluster> orderedClusters = new ArrayList<>(annotations.length);
    for (final RedisSentinel annotation : annotations) {
      final SentinelCluster cluster = clusters.get(annotation.id());
      if (cluster != null) {
        orderedClusters.add(cluster);
      }
    }

    return Collections.unmodifiableList(orderedClusters);
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {

    final Class<?> type = parameterContext.getParameter().getType();

    if (type.equals(SentinelCluster.class) || type.equals(SentinelRedis.class)) {
      return true;
    }

    if (List.class.isAssignableFrom(type)) {
      final Type paramType = parameterContext.getParameter().getParameterizedType();
      if (paramType instanceof final ParameterizedType pType) {
        final Type actualType = pType.getActualTypeArguments()[0];
        return actualType.equals(SentinelCluster.class) || actualType.equals(SentinelRedis.class);
      }
    }

    return false;
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException {

    final Class<?> type = parameterContext.getParameter().getType();

    if (type.equals(SentinelRedis.class)) {
      return getCluster("default").toSentinelRedis();
    }

    if (List.class.isAssignableFrom(type)) {
      final Type paramType = parameterContext.getParameter().getParameterizedType();
      if (paramType instanceof final ParameterizedType pType) {
        final Type actualType = pType.getActualTypeArguments()[0];
        if (actualType.equals(SentinelRedis.class)) {
          return getAllClusters().stream().map(SentinelCluster::toSentinelRedis).toList();
        }
      }
      return getAllClusters();
    }

    return getCluster("default");
  }

  // ==================== Private Helper Methods ====================

  /**
   * Extracts annotations from test class (handles both single and container).
   *
   * @param context extension context
   * @return array of annotations (may be empty)
   */
  private RedisSentinel[] extractAnnotations(final ExtensionContext context) {
    final Class<?> testClass = context.getRequiredTestClass();

    // Check for container annotation (multiple clusters)
    final RedisSentinels container = testClass.getAnnotation(RedisSentinels.class);
    if (container != null) {
      return container.value();
    }

    // Fallback: single annotation
    final RedisSentinel single = testClass.getAnnotation(RedisSentinel.class);
    if (single != null) {
      return new RedisSentinel[] {single};
    }

    return new RedisSentinel[0];
  }

  /**
   * Gets clusters map from store.
   *
   * @param context extension context
   * @return clusters map
   */
  @SuppressWarnings("unchecked")
  private static Map<String, SentinelCluster> getClustersMap(final ExtensionContext context) {
    final CloseableClusterMap wrapper =
        context.getStore(NAMESPACE).get(CLUSTER_MAP_KEY, CloseableClusterMap.class);
    return wrapper != null ? wrapper.getClusters() : java.util.Collections.emptyMap();
  }

  /**
   * Calculates total containers for budget validation logging.
   *
   * @param annotations cluster annotations
   * @return total container count
   */
  private int calculateTotalContainers(final RedisSentinel[] annotations) {
    int total = 0;
    for (final RedisSentinel ann : annotations) {
      total += 1 + ann.replicas() + ann.sentinels();
    }
    return total;
  }

  // ==================== Inner Classes ====================

  /**
   * Sentinel cluster holder (network + all containers).
   *
   * <p>Implements {@link ExtensionContext.Store.CloseableResource} for automatic cleanup.
   */
  private static final class CloseableClusterMap
      implements ExtensionContext.Store.CloseableResource {
    private final Map<String, SentinelCluster> clusters;

    CloseableClusterMap(final Map<String, SentinelCluster> clusters) {
      this.clusters = Objects.requireNonNull(clusters, "clusters");
    }

    @Override
    public void close() {
      log.info("Cleaning up {} Sentinel cluster(s)", clusters.size());
      clusters.values().forEach(SentinelCluster::close);
    }

    Map<String, SentinelCluster> getClusters() {
      return clusters;
    }
  }

  /** ThreadLocal cleanup wrapper for automatic resource management. */
  private static final class ThreadLocalCleanup
      implements ExtensionContext.Store.CloseableResource {

    @Override
    public void close() {
      CURRENT_CONTEXT.remove();
    }
  }
}
