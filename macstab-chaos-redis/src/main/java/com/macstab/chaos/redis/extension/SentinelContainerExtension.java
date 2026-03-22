/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
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

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisSentinels;
import com.macstab.chaos.redis.control.ControlFacade;
import com.macstab.chaos.redis.control.inspection.ConnectionInfo;
import com.macstab.chaos.redis.control.role.ContainerRole;
import com.macstab.chaos.redis.exception.ClusterStartupException;
import com.macstab.chaos.redis.exception.ClusterStartupException.ClusterStartupFailure;
import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.factory.RedisContainerFactory;
import com.macstab.chaos.redis.util.ResourceBudget;

import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
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

  /** Maximum wait time for all clusters to start (parallel). */
  private static final long STARTUP_TIMEOUT_SECONDS = 120;

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

    // Register automatic ThreadLocal cleanup
    context.getStore(NAMESPACE).put("threadlocal-cleanup", new ThreadLocalCleanup());

    // Extract annotations (handle both single and container)
    final RedisSentinel[] annotations = extractAnnotations(context);

    if (annotations.length == 0) {
      log.warn("No @RedisSentinel annotations found, skipping cluster startup");
      return;
    }

    // Validate resource budget BEFORE starting anything
    try {
      ResourceBudget.validateSentinelBudget(annotations);
    } catch (ResourceBudget.ResourceBudgetExceededException e) {
      log.error("Resource budget validation failed: {}", e.getMessage());
      throw e;
    }

    // Store annotations for later use (ordering, getAll())
    context.getStore(NAMESPACE).put(ANNOTATIONS_KEY, annotations);

    log.info(
        "Starting {} Sentinel cluster(s) in parallel (budget validated: {} total containers expected)",
        annotations.length,
        calculateTotalContainers(annotations));

    // Start all clusters in parallel
    final Map<String, SentinelCluster> clusters = startClustersInParallel(annotations);

    // Store clusters in map (keyed by ID)
    // Wrap clusters in CloseableClusterMap to ensure cleanup
    context.getStore(NAMESPACE).put(CLUSTER_MAP_KEY, new CloseableClusterMap(clusters));

    // Expose first sentinel nodes as system property (backward compatibility)
    if (!clusters.isEmpty()) {
      final SentinelCluster firstCluster = clusters.values().iterator().next();
      final RedisConnectionInfo firstSentinel = firstCluster.getSentinels().get(0);
      final String sentinelNodes = firstSentinel.getHost() + ":" + firstSentinel.getPort();
      System.setProperty(SENTINEL_NODES_PROPERTY, sentinelNodes);
      log.info("Set system property {}={}", SENTINEL_NODES_PROPERTY, sentinelNodes);
    }

    log.info("Successfully started {} Sentinel cluster(s)", clusters.size());
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    CURRENT_CONTEXT.remove();
    System.clearProperty(SENTINEL_NODES_PROPERTY);
    // Clusters auto-stop via Store.CloseableResource (SentinelCluster.close())
  }

  /**
   * Public accessor for {@link com.macstab.chaos.redis.annotation.RedisManager}.
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

    // Single instance
    if (type.equals(SentinelCluster.class)) {
      return true;
    }

    // Collection of instances (List<SentinelCluster>)
    if (List.class.isAssignableFrom(type)) {
      final Type paramType = parameterContext.getParameter().getParameterizedType();
      if (paramType instanceof ParameterizedType) {
        final ParameterizedType pType = (ParameterizedType) paramType;
        final Type actualType = pType.getActualTypeArguments()[0];
        return actualType.equals(SentinelCluster.class);
      }
    }

    return false;
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException {

    final Class<?> type = parameterContext.getParameter().getType();

    // Collection<SentinelCluster>
    if (List.class.isAssignableFrom(type)) {
      return getAllClusters();
    }

    // Single SentinelCluster
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
   * Starts all clusters in parallel with failure isolation.
   *
   * @param annotations cluster annotations
   * @return map of started clusters (ID → cluster)
   * @throws ClusterStartupException if any cluster fails (after cleanup)
   */
  private Map<String, SentinelCluster> startClustersInParallel(final RedisSentinel[] annotations)
      throws ClusterStartupException {

    final ExecutorService executor = Executors.newFixedThreadPool(annotations.length);
    final List<Future<ClusterStartupResult>> futures = new ArrayList<>();

    // Submit all cluster startups
    for (final RedisSentinel annotation : annotations) {
      final Future<ClusterStartupResult> future =
          executor.submit(() -> startSingleCluster(annotation));
      futures.add(future);
    }

    // Wait for all and collect results
    final Map<String, SentinelCluster> successfulClusters = new LinkedHashMap<>();
    final List<ClusterStartupFailure> failures = new ArrayList<>();

    for (int i = 0; i < futures.size(); i++) {
      try {
        final ClusterStartupResult result =
            futures.get(i).get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result.isSuccess()) {
          successfulClusters.put(result.getClusterId(), result.getCluster());
          log.info("Cluster '{}' started successfully", result.getClusterId());
        } else {
          failures.add(
              new ClusterStartupFailure(
                  result.getClusterId(), result.getErrorMessage(), result.getError()));
          log.error(
              "Cluster '{}' failed to start: {}", result.getClusterId(), result.getErrorMessage());
        }

      } catch (final TimeoutException e) {
        final String clusterId = annotations[i].id();
        // Cancel the timed-out future to try to interrupt the thread
        futures.get(i).cancel(true);
        failures.add(
            new ClusterStartupFailure(
                clusterId, "Startup timeout after " + STARTUP_TIMEOUT_SECONDS + "s", e));
        log.error("Cluster '{}' startup timed out", clusterId);
      } catch (final ExecutionException | InterruptedException e) {
        final String clusterId = annotations[i].id();
        failures.add(
            new ClusterStartupFailure(
                clusterId, "Unexpected error during startup: " + e.getMessage(), e));
        log.error("Cluster '{}' encountered unexpected error", clusterId, e);
      }
    }

    // Use shutdownNow() to interrupt any blocked threads (e.g., hanging package installation)
    executor.shutdownNow();
    try {
      // Give threads a brief moment to clean up after interruption
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Some startup threads did not terminate within 5 seconds");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // FAILURE ISOLATION: Clean up successful clusters if ANY failed
    if (!failures.isEmpty()) {
      final List<String> cleanupActions = cleanupClusters(successfulClusters);
      throw new ClusterStartupException(failures, cleanupActions);
    }

    return successfulClusters;
  }

  /**
   * Starts a single cluster.
   *
   * @param annotation cluster configuration
   * @return startup result (success or failure)
   */
  private ClusterStartupResult startSingleCluster(final RedisSentinel annotation) {
    try {
      final var factoryCluster =
          RedisContainerFactory.createSentinelCluster(
              annotation.replicas(), annotation.sentinels(), annotation.enableNetworkChaos());

      final SentinelCluster cluster =
          new SentinelCluster(
              factoryCluster.network(),
              factoryCluster.master(),
              factoryCluster.replicas(),
              factoryCluster.sentinels(),
              annotation.masterName());

      cluster.start();

      // Auto-install network tools if network chaos is enabled
      if (annotation.enableNetworkChaos()) {
        installNetworkToolsForCluster(cluster, annotation.id());
      }

      // Auto-install packages if specified
      if (annotation.packages().length > 0) {
        installPackagesForCluster(cluster, annotation.id(), annotation.packages());
      }

      return ClusterStartupResult.success(annotation.id(), cluster);

    } catch (final Exception e) {
      log.error("Failed to start cluster '{}'", annotation.id(), e);
      return ClusterStartupResult.failure(annotation.id(), e.getMessage(), e);
    }
  }

  /**
   * Installs network tools (tc, iptables) in all cluster containers.
   *
   * @param cluster the Sentinel cluster
   * @param clusterId cluster ID for logging
   */
  private void installNetworkToolsForCluster(
      final SentinelCluster cluster, final String clusterId) {
    final List<GenericContainer<?>> allContainers = new java.util.ArrayList<>();
    allContainers.add(cluster.getMasterContainer());
    allContainers.addAll(cluster.getReplicaContainers());
    allContainers.addAll(cluster.getSentinelContainers());

    for (final GenericContainer<?> container : allContainers) {
      try {
        com.macstab.chaos.core.util.ContainerNetworkToolsInstaller.installIfNeeded(container);
      } catch (final Exception e) {
        log.warn(
            "Failed to install network tools in cluster '{}' container {}: {}",
            clusterId,
            container.getContainerId().substring(0, 12),
            e.getMessage());
      }
    }
  }

  /**
   * Installs packages in all cluster containers (master, replicas, sentinels).
   *
   * @param cluster the Sentinel cluster
   * @param clusterId cluster ID for logging
   * @param packages packages to install
   */
  private void installPackagesForCluster(
      final SentinelCluster cluster, final String clusterId, final String[] packages) {
    final List<GenericContainer<?>> allContainers = new java.util.ArrayList<>();
    allContainers.add(cluster.getMasterContainer());
    allContainers.addAll(cluster.getReplicaContainers());
    allContainers.addAll(cluster.getSentinelContainers());

    log.info(
        "Installing {} package(s) in cluster '{}' ({} containers): {}",
        packages.length,
        clusterId,
        allContainers.size(),
        java.util.Arrays.toString(packages));

    int successCount = 0;
    int failureCount = 0;

    for (final GenericContainer<?> container : allContainers) {
      try {
        com.macstab.chaos.core.util.PackageInstaller.install(
            container, java.util.Arrays.asList(packages), true);
        successCount++;
      } catch (final Exception e) {
        failureCount++;
        log.warn(
            "Failed to install packages in cluster '{}' container {}: {}",
            clusterId,
            container.getContainerId().substring(0, 12),
            e.getMessage());
      }
    }

    if (successCount > 0) {
      log.info(
          "✓ Packages installed in {}/{} containers of cluster '{}'",
          successCount,
          allContainers.size(),
          clusterId);
    }
    if (failureCount > 0) {
      log.warn(
          "✗ Package installation failed in {}/{} containers of cluster '{}'",
          failureCount,
          allContainers.size(),
          clusterId);
    }
  }

  /**
   * Cleans up successfully started clusters (failure isolation).
   *
   * @param clusters clusters to clean up
   * @return list of cleanup actions performed
   */
  private List<String> cleanupClusters(final Map<String, SentinelCluster> clusters) {
    final List<String> cleanupActions = new ArrayList<>();

    for (final Map.Entry<String, SentinelCluster> entry : clusters.entrySet()) {
      final String clusterId = entry.getKey();
      final SentinelCluster cluster = entry.getValue();

      try {
        final int containerCount =
            1 + cluster.getReplicaContainers().size() + cluster.getSentinelContainers().size();
        cluster.stop();
        final String action =
            String.format(
                "Stopped cluster '%s' (%d containers, 1 network)", clusterId, containerCount);
        cleanupActions.add(action);
        log.info(action);
      } catch (final Exception e) {
        log.warn("Failed to clean up cluster '{}': {}", clusterId, e.getMessage());
        cleanupActions.add(
            String.format("Failed to stop cluster '%s': %s", clusterId, e.getMessage()));
      }
    }

    return cleanupActions;
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

  /** Result holder for parallel cluster startup. */
  private static final class ClusterStartupResult {
    private final String clusterId;
    private final SentinelCluster cluster;
    private final String errorMessage;
    private final Exception error;

    private ClusterStartupResult(
        final String clusterId,
        final SentinelCluster cluster,
        final String errorMessage,
        final Exception error) {
      this.clusterId = clusterId;
      this.cluster = cluster;
      this.errorMessage = errorMessage;
      this.error = error;
    }

    static ClusterStartupResult success(final String clusterId, final SentinelCluster cluster) {
      return new ClusterStartupResult(clusterId, cluster, null, null);
    }

    static ClusterStartupResult failure(
        final String clusterId, final String errorMessage, final Exception error) {
      return new ClusterStartupResult(clusterId, null, errorMessage, error);
    }

    boolean isSuccess() {
      return error == null;
    }

    String getClusterId() {
      return clusterId;
    }

    SentinelCluster getCluster() {
      return cluster;
    }

    String getErrorMessage() {
      return errorMessage;
    }

    Exception getError() {
      return error;
    }
  }

  /**
   * Sentinel cluster holder (network + all containers).
   *
   * <p>Implements {@link ExtensionContext.Store.CloseableResource} for automatic cleanup.
   */
  public static final class SentinelCluster implements ExtensionContext.Store.CloseableResource {
    private final Network network;
    private final GenericContainer<?> master;
    private final List<GenericContainer<?>> replicas;
    private final List<GenericContainer<?>> sentinels;
    private final String masterName;

    /** Lazily initialized control facade (v2.0 feature). */
    private volatile ControlFacade controlFacade;

    public SentinelCluster(
        final Network network,
        final GenericContainer<?> master,
        final List<GenericContainer<?>> replicas,
        final List<GenericContainer<?>> sentinels,
        final String masterName) {
      this.network = Objects.requireNonNull(network, "network");
      this.master = Objects.requireNonNull(master, "master");
      this.replicas = Objects.requireNonNull(replicas, "replicas");
      this.sentinels = Objects.requireNonNull(sentinels, "sentinels");
      this.masterName = Objects.requireNonNull(masterName, "masterName");
    }

    public void start() {
      master.start();
      replicas.forEach(GenericContainer::start);
      sentinels.forEach(GenericContainer::start);
    }

    public void stop() {
      sentinels.forEach(GenericContainer::stop);
      replicas.forEach(GenericContainer::stop);
      master.stop();
      network.close();
    }

    public GenericContainer<?> getMasterContainer() {
      return master;
    }

    public List<GenericContainer<?>> getReplicaContainers() {
      return List.copyOf(replicas);
    }

    public List<GenericContainer<?>> getSentinelContainers() {
      return List.copyOf(sentinels);
    }

    public Network getNetwork() {
      return network;
    }

    public String getMasterName() {
      return masterName;
    }

    public String getMasterHost() {
      // Query current master dynamically (supports failover scenarios)
      final GenericContainer<?> currentMaster = getControl().getMaster();
      return currentMaster.getHost();
    }

    public int getMasterPort() {
      // Query current master dynamically (supports failover scenarios)
      final GenericContainer<?> currentMaster = getControl().getMaster();
      return currentMaster.getMappedPort(6379);
    }

    public RedisConnectionInfo getMaster() {
      return new RedisConnectionInfo(getMasterHost(), getMasterPort());
    }

    public List<RedisConnectionInfo> getReplicas() {
      return replicas.stream()
          .map(r -> new RedisConnectionInfo(r.getHost(), r.getMappedPort(6379)))
          .toList();
    }

    public List<RedisConnectionInfo> getSentinels() {
      return sentinels.stream()
          .map(s -> new RedisConnectionInfo(s.getHost(), s.getMappedPort(26379)))
          .toList();
    }

    public RedisURI getMasterURI() {
      return RedisURI.builder().withHost(getMasterHost()).withPort(getMasterPort()).build();
    }

    public List<RedisURI> getSentinelURIs() {
      return getSentinels().stream()
          .map(s -> RedisURI.builder().withHost(s.getHost()).withPort(s.getPort()).build())
          .toList();
    }

    // ==================== v2.0 Control Features ====================

    /**
     * Gets the control facade for connection inspection and lifecycle control.
     *
     * <p><strong>v2.0 Feature:</strong> Provides unified API for:
     *
     * <ul>
     *   <li>Connection inspection (identify container from Lettuce connection)
     *   <li>Container lifecycle control (restart, kill, pause, resume)
     *   <li>Failover simulation and testing
     *   <li>Role-based container access
     * </ul>
     *
     * <p><strong>Lazy Initialization:</strong> Created on first access, reused thereafter.
     *
     * @return control facade (never null)
     * @since 2.0
     */
    public ControlFacade getControl() {
      if (controlFacade == null) {
        synchronized (this) {
          if (controlFacade == null) {
            controlFacade = ControlFacade.create(getAllContainers(), buildContainerIndexMap());
          }
        }
      }
      return controlFacade;
    }

    /**
     * Inspects a Lettuce connection to determine which container it's using.
     *
     * <p><strong>Convenience Wrapper:</strong> Equivalent to {@code
     * getControl().inspect(connection)}.
     *
     * @param connection Lettuce connection to inspect (never null)
     * @return connection info with role, container, and health status (never null)
     * @throws NullPointerException if connection is null
     * @throws IllegalStateException if connection is closed or unhealthy
     * @since 2.0
     */
    public ConnectionInfo inspect(final StatefulRedisConnection<?, ?> connection) {
      return getControl().inspect(connection);
    }

    /**
     * Restarts a container by role.
     *
     * <p><strong>Convenience Wrapper:</strong> Equivalent to {@code
     * getControl().restart(getContainer(role))}.
     *
     * @param role the role of the container to restart (never null)
     * @throws NullPointerException if role is null
     * @throws IllegalStateException if no container has the role or restart fails
     * @since 2.0
     */
    public void restart(final ContainerRole role) {
      final GenericContainer<?> container = getControl().getContainer(role);
      getControl().restart(container);
    }

    /**
     * Triggers a failover by killing the current master.
     *
     * <p><strong>Convenience Wrapper:</strong> Equivalent to {@code
     * getControl().triggerFailover()}.
     *
     * @return failover duration (time until new master elected)
     * @throws IllegalStateException if failover doesn't complete within 30 seconds
     * @since 2.0
     */
    public java.time.Duration triggerFailover() {
      return getControl().triggerFailover();
    }

    /**
     * Gets a container by role.
     *
     * <p><strong>Convenience Wrapper:</strong> Equivalent to {@code
     * getControl().getContainer(role)}.
     *
     * @param role the role to find (never null)
     * @return container with matching role (never null)
     * @throws NullPointerException if role is null
     * @throws IllegalStateException if no container has the role
     * @since 2.0
     */
    public GenericContainer<?> getContainer(final ContainerRole role) {
      return getControl().getContainer(role);
    }

    // ==================== Internal Helpers ====================

    /**
     * Gets all containers (master + replicas + sentinels).
     *
     * @return all containers (never null)
     */
    private List<GenericContainer<?>> getAllContainers() {
      final List<GenericContainer<?>> all = new ArrayList<>();
      all.add(master);
      all.addAll(replicas);
      all.addAll(sentinels);
      return List.copyOf(all);
    }

    /**
     * Builds container-to-index mapping for ControlFacade.
     *
     * @return container index map (never null)
     */
    private Map<GenericContainer<?>, Integer> buildContainerIndexMap() {
      final Map<GenericContainer<?>, Integer> map = new LinkedHashMap<>();

      // Replicas: index 0-8
      for (int i = 0; i < replicas.size(); i++) {
        map.put(replicas.get(i), i);
      }

      // Sentinels: index 0-8
      for (int i = 0; i < sentinels.size(); i++) {
        map.put(sentinels.get(i), i);
      }

      return Map.copyOf(map);
    }

    @Override
    public void close() {
      stop();
    }
  }

  /**
   * Wrapper for Map<String, SentinelCluster> that implements CloseableResource to ensure cleanup.
   *
   * <p>JUnit 5's ExtensionContext.Store only calls close() on directly stored CloseableResource
   * objects, not on objects inside a Map. This wrapper ensures all SentinelCluster objects are
   * cleaned up.
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
