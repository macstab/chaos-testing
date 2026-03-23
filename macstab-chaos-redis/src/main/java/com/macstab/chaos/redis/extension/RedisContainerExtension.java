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
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.annotation.RedisStandalones;
import com.macstab.chaos.redis.exception.ClusterStartupException;
import com.macstab.chaos.redis.exception.ClusterStartupException.ClusterStartupFailure;
import com.macstab.chaos.redis.util.ResourceBudget;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * JUnit 5 extension managing standalone Redis instances for {@link RedisStandalone} tests.
 *
 * <p><strong>Multi-Instance Support (v2.0):</strong>
 *
 * <ul>
 *   <li>✅ 1-5 standalone instances per test class
 *   <li>✅ Parallel instance startup
 *   <li>✅ Failure isolation (cleanup on error)
 *   <li>✅ Flexible parameter injection (single or {@code List<RedisConnectionInfo>})
 *   <li>✅ Resource budget validation
 *   <li>✅ Works on all platforms (Linux, macOS, Windows)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0 (enhanced in 2.0)
 */
@Slf4j
public final class RedisContainerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(RedisContainerExtension.class);

  private static final String CONTAINER_MAP_KEY = "redis-containers-map";
  private static final String ANNOTATIONS_KEY = "redis-annotations";
  private static final String STORES_MAP_KEY = "redis-stores-map";

  /** ThreadLocal to hold current test context for INSTANCE.get() calls. */
  private static final ThreadLocal<ExtensionContext> CURRENT_CONTEXT = new ThreadLocal<>();

  /** Maximum wait time for all instances to start (parallel). */
  private static final long STARTUP_TIMEOUT_SECONDS = 60;

  public RedisContainerExtension() {
    // Stateless - no initialization needed
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    CURRENT_CONTEXT.set(context);

    // Register automatic ThreadLocal cleanup
    context.getStore(NAMESPACE).put("threadlocal-cleanup", new ThreadLocalCleanup());

    // Extract annotations (handle both single and container)
    final RedisStandalone[] annotations = extractAnnotations(context);

    if (annotations.length == 0) {
      log.warn("No @RedisStandalone annotations found, skipping container startup");
      return;
    }

    // Validate resource budget BEFORE starting anything
    try {
      ResourceBudget.validateStandaloneBudget(annotations);
    } catch (ResourceBudget.ResourceBudgetExceededException e) {
      log.error("Resource budget validation failed: {}", e.getMessage());
      throw e;
    }

    // Store annotations for later use (ordering, getAll())
    context.getStore(NAMESPACE).put(ANNOTATIONS_KEY, annotations);

    log.info(
        "Starting {} standalone Redis instance(s) in parallel (budget validated)",
        annotations.length);

    // Start all instances in parallel
    final StartupResults results = startInstancesInParallel(annotations);

    // Store instances in map (keyed by ID)
    context.getStore(NAMESPACE).put(CONTAINER_MAP_KEY, results.instances());
    // Wrap stores in CloseableStoreMap to ensure cleanup
    context.getStore(NAMESPACE).put(STORES_MAP_KEY, new CloseableStoreMap(results.stores()));

    log.info("Successfully started {} standalone Redis instance(s)", results.instances().size());
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    CURRENT_CONTEXT.remove();
    // Containers auto-stop via Store.CloseableResource
  }

  /**
   * Public accessor for {@link com.macstab.chaos.redis.annotation.RedisManager}.
   *
   * @param id instance ID from {@code @RedisStandalone(id = "...")}
   * @return connection info
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   */
  public static RedisConnectionInfo getContainer(final String id) {
    final ExtensionContext context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException(
          "RedisStandalone.INSTANCE.get() called outside @RedisStandalone test context");
    }

    final Map<String, RedisConnectionInfo> instances = getInstancesMap(context);

    // Smart default resolution: if only one instance, return it regardless of ID
    if ("default".equals(id) && instances.size() == 1) {
      return instances.values().iterator().next();
    }

    final RedisConnectionInfo instance = instances.get(id);
    if (instance == null) {
      if ("default".equals(id) && instances.size() > 1) {
        throw new IllegalArgumentException(
            "Multiple Redis instances exist ("
                + instances.keySet()
                + ") but no instance with id=\"default\". "
                + "Use List<RedisConnectionInfo> parameter or add @RedisStandalone(id=\"default\").");
      }
      throw new IllegalArgumentException(
          "No Redis instance found with id='"
              + id
              + "'. Available: "
              + instances.keySet()
              + ". "
              + "Did you add @RedisStandalone(id=\""
              + id
              + "\") to your test class?");
    }

    return instance;
  }

  /**
   * Gets the actual container instance (for network chaos operations).
   *
   * <p><strong>Use Case:</strong> When you need direct access to the GenericContainer for chaos
   * operations.
   *
   * @param id instance ID from {@code @RedisStandalone(id = "...")}
   * @return container instance
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   * @since 2.0
   */
  public static GenericContainer<?> getContainerInstance(final String id) {
    final ExtensionContext context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException(
          "getContainerInstance() called outside @RedisStandalone test context");
    }

    final Map<String, Store> storesMap = getStoresMap(context);

    // Smart default resolution: if only one instance, return it regardless of ID
    if ("default".equals(id) && storesMap.size() == 1) {
      return storesMap.values().iterator().next().getContainer();
    }

    final Store store = storesMap.get(id);
    if (store == null) {
      throw new IllegalArgumentException(
          "No Redis instance found with id='"
              + id
              + "'. Available: "
              + storesMap.keySet()
              + ". "
              + "Did you add @RedisStandalone(id=\""
              + id
              + "\") to your test class?");
    }

    return store.getContainer();
  }

  /**
   * Gets all instances in annotation declaration order.
   *
   * @return list of all instances in declaration order
   * @throws IllegalStateException if called outside test context
   */
  public static List<RedisConnectionInfo> getAllContainers() {
    final ExtensionContext context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException(
          "RedisStandalone.INSTANCE.getAll() called outside @RedisStandalone test context");
    }

    final RedisStandalone[] annotations =
        context.getStore(NAMESPACE).get(ANNOTATIONS_KEY, RedisStandalone[].class);
    if (annotations == null || annotations.length == 0) {
      return Collections.emptyList();
    }

    final Map<String, RedisConnectionInfo> instances = getInstancesMap(context);

    // Return in annotation declaration order
    final List<RedisConnectionInfo> orderedInstances = new ArrayList<>(annotations.length);
    for (final RedisStandalone annotation : annotations) {
      final RedisConnectionInfo instance = instances.get(annotation.id());
      if (instance != null) {
        orderedInstances.add(instance);
      }
    }

    return Collections.unmodifiableList(orderedInstances);
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {

    final Class<?> type = parameterContext.getParameter().getType();

    // Single instance
    if (type.equals(RedisConnectionInfo.class)) {
      return true;
    }

    // Collection of instances (List<RedisConnectionInfo>)
    if (List.class.isAssignableFrom(type)) {
      final Type paramType = parameterContext.getParameter().getParameterizedType();
      if (paramType instanceof ParameterizedType) {
        final ParameterizedType pType = (ParameterizedType) paramType;
        final Type actualType = pType.getActualTypeArguments()[0];
        return actualType.equals(RedisConnectionInfo.class);
      }
    }

    return false;
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException {

    final Class<?> type = parameterContext.getParameter().getType();

    // List<RedisConnectionInfo>
    if (List.class.isAssignableFrom(type)) {
      return getAllContainers();
    }

    // Single RedisConnectionInfo
    return getContainer("default");
  }

  // ==================== Private Helper Methods ====================

  private RedisStandalone[] extractAnnotations(final ExtensionContext context) {
    final Class<?> testClass = context.getRequiredTestClass();

    // Check for container annotation (multiple instances)
    final RedisStandalones container = testClass.getAnnotation(RedisStandalones.class);
    if (container != null) {
      return container.value();
    }

    // Fallback: single annotation
    final RedisStandalone single = testClass.getAnnotation(RedisStandalone.class);
    if (single != null) {
      return new RedisStandalone[] {single};
    }

    return new RedisStandalone[0];
  }

  private StartupResults startInstancesInParallel(final RedisStandalone[] annotations)
      throws ClusterStartupException {

    final ExecutorService executor = Executors.newFixedThreadPool(annotations.length);
    final List<Future<InstanceStartupResult>> futures = new ArrayList<>();

    // Submit all instance startups
    for (final RedisStandalone annotation : annotations) {
      final Future<InstanceStartupResult> future =
          executor.submit(() -> startSingleInstance(annotation));
      futures.add(future);
    }

    // Wait for all and collect results
    final Map<String, RedisConnectionInfo> successfulInstances = new LinkedHashMap<>();
    final Map<String, Store> successfulStores = new LinkedHashMap<>();
    final List<ClusterStartupFailure> failures = new ArrayList<>();
    final List<Store> containersToCleanup = new ArrayList<>();

    for (int i = 0; i < futures.size(); i++) {
      try {
        final InstanceStartupResult result =
            futures.get(i).get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result.isSuccess()) {
          successfulInstances.put(result.getInstanceId(), result.getConnectionInfo());
          successfulStores.put(result.getInstanceId(), result.getStore());
          containersToCleanup.add(result.getStore());
          log.info("Instance '{}' started successfully", result.getInstanceId());
        } else {
          failures.add(
              new ClusterStartupFailure(
                  result.getInstanceId(), result.getErrorMessage(), result.getError()));
          log.error(
              "Instance '{}' failed to start: {}",
              result.getInstanceId(),
              result.getErrorMessage());
        }

      } catch (final TimeoutException e) {
        final String instanceId = annotations[i].id();
        // Cancel the timed-out future to try to interrupt the thread
        futures.get(i).cancel(true);
        failures.add(
            new ClusterStartupFailure(
                instanceId, "Startup timeout after " + STARTUP_TIMEOUT_SECONDS + "s", e));
        log.error("Instance '{}' startup timed out", instanceId);
      } catch (final ExecutionException | InterruptedException e) {
        final String instanceId = annotations[i].id();
        failures.add(
            new ClusterStartupFailure(
                instanceId, "Unexpected error during startup: " + e.getMessage(), e));
        log.error("Instance '{}' encountered unexpected error", instanceId, e);
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

    // FAILURE ISOLATION: Clean up successful instances if ANY failed
    if (!failures.isEmpty()) {
      final List<String> cleanupActions = cleanupInstances(containersToCleanup);
      throw new ClusterStartupException(failures, cleanupActions);
    }

    return new StartupResults(successfulInstances, successfulStores);
  }

  private InstanceStartupResult startSingleInstance(final RedisStandalone annotation) {
    try {
      final var container = createContainer(annotation);
      container.start();

      // Auto-install network tools if network chaos is enabled
      if (annotation.enableNetworkChaos()) {
        try {
          if (!com.macstab.chaos.core.util.PackageInstaller.isInstalled(container, "tc")) {
            com.macstab.chaos.core.util.PackageInstaller.install(container, "iproute2", "iptables");
          }
        } catch (final Exception e) {
          log.warn(
              "Network chaos enabled but failed to install tools for '{}': {}",
              annotation.id(),
              e.getMessage());
        }
      }

      // Auto-install packages if specified
      if (annotation.packages().length > 0) {
        try {
          log.info(
              "Installing {} package(s) in instance '{}': {}",
              annotation.packages().length,
              annotation.id(),
              java.util.Arrays.toString(annotation.packages()));
          com.macstab.chaos.core.util.PackageInstaller.install(
              container, java.util.Arrays.asList(annotation.packages()), true);
          log.info("✓ Packages installed successfully in instance '{}'", annotation.id());
        } catch (final Exception e) {
          log.warn(
              "Failed to install packages in instance '{}': {}", annotation.id(), e.getMessage());
        }
      }

      final var connectionInfo =
          new RedisConnectionInfo(container.getHost(), container.getMappedPort(6379));

      final Store store = new Store(container, connectionInfo);

      return InstanceStartupResult.success(annotation.id(), connectionInfo, store);

    } catch (final Exception e) {
      log.error("Failed to start instance '{}'", annotation.id(), e);
      return InstanceStartupResult.failure(annotation.id(), e.getMessage(), e);
    }
  }

  private GenericContainer<?> createContainer(final RedisStandalone annotation) {
    final var imageName = DockerImageName.parse("redis:" + annotation.version());
    final var container = new GenericContainer<>(imageName).withExposedPorts(6379);

    // Build redis-server command
    final var command = new ArrayList<String>();
    command.add("redis-server");

    // Add custom args
    if (annotation.args().length > 0) {
      command.addAll(List.of(annotation.args()));
    }

    container.withCommand(command.toArray(new String[0]));

    // Fixed port (if specified)
    if (annotation.port() > 0) {
      container.setPortBindings(List.of(annotation.port() + ":6379"));
    }

    // Network chaos support (container-scoped only)
    if (annotation.enableNetworkChaos()) {
      container.withCreateContainerCmdModifier(
          cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));

      // Verify chaos dependencies are on classpath
      verifyChaosDependencies();
    }

    return container;
  }

  private List<String> cleanupInstances(final List<Store> stores) {
    final List<String> cleanupActions = new ArrayList<>();

    for (final Store store : stores) {
      try {
        store.close();
        final String action = "Stopped Redis container: " + store.getConnectionInfo();
        cleanupActions.add(action);
        log.info(action);
      } catch (final Exception e) {
        log.warn("Failed to clean up container: {}", e.getMessage());
        cleanupActions.add("Failed to stop container: " + e.getMessage());
      }
    }

    return cleanupActions;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, RedisConnectionInfo> getInstancesMap(final ExtensionContext context) {
    return context.getStore(NAMESPACE).get(CONTAINER_MAP_KEY, Map.class);
  }

  private static Map<String, Store> getStoresMap(final ExtensionContext context) {
    final CloseableStoreMap wrapper =
        context.getStore(NAMESPACE).get(STORES_MAP_KEY, CloseableStoreMap.class);
    return wrapper != null ? wrapper.getStores() : java.util.Collections.emptyMap();
  }

  /** Holder for startup results. */
  private record StartupResults(
      Map<String, RedisConnectionInfo> instances, Map<String, Store> stores) {}

  // ==================== Inner Classes ====================

  private static final class InstanceStartupResult {
    private final String instanceId;
    private final RedisConnectionInfo connectionInfo;
    private final Store store;
    private final String errorMessage;
    private final Exception error;

    private InstanceStartupResult(
        final String instanceId,
        final RedisConnectionInfo connectionInfo,
        final Store store,
        final String errorMessage,
        final Exception error) {
      this.instanceId = instanceId;
      this.connectionInfo = connectionInfo;
      this.store = store;
      this.errorMessage = errorMessage;
      this.error = error;
    }

    static InstanceStartupResult success(
        final String instanceId, final RedisConnectionInfo connectionInfo, final Store store) {
      return new InstanceStartupResult(instanceId, connectionInfo, store, null, null);
    }

    static InstanceStartupResult failure(
        final String instanceId, final String errorMessage, final Exception error) {
      return new InstanceStartupResult(instanceId, null, null, errorMessage, error);
    }

    boolean isSuccess() {
      return error == null;
    }

    String getInstanceId() {
      return instanceId;
    }

    RedisConnectionInfo getConnectionInfo() {
      return connectionInfo;
    }

    Store getStore() {
      return store;
    }

    String getErrorMessage() {
      return errorMessage;
    }

    Exception getError() {
      return error;
    }
  }

  /** Container + connection info holder. */
  @Getter
  public static final class Store implements ExtensionContext.Store.CloseableResource {
    private final GenericContainer<?> container;
    private final RedisConnectionInfo connectionInfo;

    public Store(final GenericContainer<?> container, final RedisConnectionInfo connectionInfo) {
      this.container = Objects.requireNonNull(container, "container");
      this.connectionInfo = Objects.requireNonNull(connectionInfo, "connectionInfo");
    }

    @Override
    public void close() {
      container.stop();
    }
  }

  /**
   * Wrapper for Map<String, Store> that implements CloseableResource to ensure cleanup.
   *
   * <p>JUnit 5's ExtensionContext.Store only calls close() on directly stored CloseableResource
   * objects, not on objects inside a Map. This wrapper ensures all Store objects are cleaned up.
   */
  private static final class CloseableStoreMap implements ExtensionContext.Store.CloseableResource {
    private final Map<String, Store> stores;

    CloseableStoreMap(final Map<String, Store> stores) {
      this.stores = Objects.requireNonNull(stores, "stores");
    }

    @Override
    public void close() {
      log.info("Cleaning up {} standalone Redis container(s)", stores.size());
      stores.values().forEach(Store::close);
    }

    Map<String, Store> getStores() {
      return stores;
    }
  }

  /** Redis connection details (host + port). */
  @Getter
  public static final class RedisConnectionInfo {
    private final String host;
    private final int port;

    public RedisConnectionInfo(final String host, final int port) {
      this.host = Objects.requireNonNull(host, "host");
      this.port = port;
    }

    /**
     * Gets the Redis host address.
     *
     * @return host address (never null)
     */
    public String getHost() {
      return host;
    }

    /**
     * Gets the Redis port number.
     *
     * @return port number (positive integer)
     */
    public int getPort() {
      return port;
    }

    @Override
    public String toString() {
      return host + ":" + port;
    }
  }

  /** ThreadLocal cleanup wrapper. */
  private static final class ThreadLocalCleanup
      implements ExtensionContext.Store.CloseableResource {

    @Override
    public void close() {
      CURRENT_CONTEXT.remove();
    }
  }

  /**
   * Verify cache chaos (Toxiproxy) dependency when enableNetworkChaos = true.
   *
   * <p>enableNetworkChaos requires Toxiproxy to proxy Redis traffic (client → Toxiproxy → Redis).
   *
   * @throws IllegalStateException if cache chaos module (Toxiproxy) is missing
   */
  private static void verifyChaosDependencies() {
    // Check for cache module (contains Toxiproxy logic)
    if (!isClassPresent("com.macstab.chaos.cache.ToxiproxyCacheChaos")) {
      throw new IllegalStateException(
          "enableNetworkChaos=true requires macstab-chaos-cache (Toxiproxy) on classpath.\n"
              + "This proxies Redis traffic: client → Toxiproxy → Redis\n\n"
              + "Add to your build.gradle.kts:\n"
              + "    testImplementation(\"com.macstab:macstab-chaos-cache:${version}\")");
    }
  }

  /**
   * Check if class is present on classpath.
   *
   * @param className fully qualified class name
   * @return true if class exists, false otherwise
   */
  private static boolean isClassPresent(final String className) {
    try {
      Class.forName(className, false, RedisContainerExtension.class.getClassLoader());
      return true;
    } catch (final ClassNotFoundException e) {
      return false;
    }
  }
}
