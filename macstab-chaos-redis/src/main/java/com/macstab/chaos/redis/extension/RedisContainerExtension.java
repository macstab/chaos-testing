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
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.annotation.RedisStandalones;
import com.macstab.chaos.redis.api.StandaloneRedis;
import com.macstab.chaos.redis.extension.internal.DependencyVerifier;
import com.macstab.chaos.redis.extension.internal.StandaloneStartupOrchestrator;
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

  public RedisContainerExtension() {
    // Stateless - no initialization needed
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    CURRENT_CONTEXT.set(context);
    context.getStore(NAMESPACE).put("threadlocal-cleanup", new ThreadLocalCleanup());

    final RedisStandalone[] annotations = extractAnnotations(context);
    if (annotations.length == 0) {
      log.warn("No @RedisStandalone annotations found, skipping container startup");
      return;
    }

    try {
      ResourceBudget.validateStandaloneBudget(annotations);
    } catch (final ResourceBudget.ResourceBudgetExceededException e) {
      log.error("Resource budget validation failed: {}", e.getMessage());
      throw e;
    }

    context.getStore(NAMESPACE).put(ANNOTATIONS_KEY, annotations);
    log.info(
        "Starting {} standalone Redis instance(s) in parallel (budget validated)",
        annotations.length);

    if (mustVerifyChaosDependencies(annotations)) {
      DependencyVerifier.requireCacheModule();
    }

    final StandaloneStartupOrchestrator.StartupOutcome outcome =
        new StandaloneStartupOrchestrator().start(annotations);

    context.getStore(NAMESPACE).put(CONTAINER_MAP_KEY, outcome.instances());
    context.getStore(NAMESPACE).put(STORES_MAP_KEY, new CloseableStoreMap(outcome.stores()));

    // Register each instance into ChaosTestingExtension so RedisStandalone.INSTANCE.get(id)
    // and StandaloneRedis parameter injection via ChaosTestingExtension work correctly.
    outcome
        .instances()
        .forEach(
            (id, info) -> {
              final StandaloneRedis redis = new StandaloneRedis(info.getHost(), info.getPort());
              ChaosTestingExtension.registerExternalConnectionInfo(
                  RedisStandalone.class, id, redis);
            });

    log.info("Successfully started {} standalone Redis instance(s)", outcome.instances().size());
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    CURRENT_CONTEXT.remove();
    // Containers auto-stop via Store.CloseableResource
  }

  /**
   * Public accessor for {@code RedisStandalone.INSTANCE} ({@link
   * com.macstab.chaos.core.api.ContainerManager}).
   *
   * @param id instance ID from {@code @RedisStandalone(id = "...")}
   * @return connection info
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   */
  public static RedisConnectionInfo getContainer(final String id) {
    final ExtensionContext context = requireContext();
    final Map<String, RedisConnectionInfo> instances = getInstancesMap(context);

    if ("default".equals(id) && instances.size() == 1) {
      return instances.values().iterator().next();
    }

    final RedisConnectionInfo instance = instances.get(id);
    if (instance == null) {
      if ("default".equals(id) && instances.size() > 1) {
        throw new IllegalArgumentException(
            "Multiple Redis instances exist ("
                + instances.keySet()
                + ") but no instance with id=\"default\".");
      }
      throw new IllegalArgumentException(
          "No Redis instance found with id='" + id + "'. Available: " + instances.keySet());
    }
    return instance;
  }

  /**
   * Gets the actual container instance (for network chaos operations).
   *
   * @param id instance ID from {@code @RedisStandalone(id = "...")}
   * @return container instance
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   * @since 2.0
   */
  public static GenericContainer<?> getContainerInstance(final String id) {
    final Map<String, Store> storesMap = getStoresMap(requireContext());

    if ("default".equals(id) && storesMap.size() == 1) {
      return storesMap.values().iterator().next().getContainer();
    }

    final Store store = storesMap.get(id);
    if (store == null) {
      throw new IllegalArgumentException(
          "No Redis instance found with id='" + id + "'. Available: " + storesMap.keySet());
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
    final ExtensionContext context = requireContext();
    final RedisStandalone[] annotations =
        context.getStore(NAMESPACE).get(ANNOTATIONS_KEY, RedisStandalone[].class);
    if (annotations == null || annotations.length == 0) {
      return Collections.emptyList();
    }

    final Map<String, RedisConnectionInfo> instances = getInstancesMap(context);
    final List<RedisConnectionInfo> ordered = new ArrayList<>(annotations.length);
    for (final RedisStandalone annotation : annotations) {
      final RedisConnectionInfo instance = instances.get(annotation.id());
      if (instance != null) {
        ordered.add(instance);
      }
    }
    return Collections.unmodifiableList(ordered);
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> type = parameterContext.getParameter().getType();
    if (type.equals(RedisConnectionInfo.class) || type.equals(StandaloneRedis.class)) {
      return true;
    }
    if (List.class.isAssignableFrom(type)) {
      final Type paramType = parameterContext.getParameter().getParameterizedType();
      if (paramType instanceof final ParameterizedType pType) {
        final Type actualType = pType.getActualTypeArguments()[0];
        return actualType.equals(RedisConnectionInfo.class)
            || actualType.equals(StandaloneRedis.class);
      }
    }
    return false;
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException {
    final Class<?> type = parameterContext.getParameter().getType();
    if (type.equals(StandaloneRedis.class)) {
      return toStandaloneRedis(getContainer("default"));
    }
    if (List.class.isAssignableFrom(type)) {
      final Type paramType = parameterContext.getParameter().getParameterizedType();
      if (paramType instanceof final ParameterizedType pType
          && pType.getActualTypeArguments()[0].equals(StandaloneRedis.class)) {
        return getAllContainers().stream().map(this::toStandaloneRedis).toList();
      }
      return getAllContainers();
    }
    return getContainer("default");
  }

  private StandaloneRedis toStandaloneRedis(final RedisConnectionInfo info) {
    return new StandaloneRedis(info.getHost(), info.getPort());
  }

  // ==================== Private Helpers ====================

  private RedisStandalone[] extractAnnotations(final ExtensionContext context) {
    final Class<?> testClass = context.getRequiredTestClass();
    final RedisStandalones container = testClass.getAnnotation(RedisStandalones.class);
    if (container != null) {
      return container.value();
    }
    final RedisStandalone single = testClass.getAnnotation(RedisStandalone.class);
    return single != null ? new RedisStandalone[] {single} : new RedisStandalone[0];
  }

  private boolean mustVerifyChaosDependencies(final RedisStandalone[] annotations) {
    for (final RedisStandalone a : annotations) {
      if (a.enableNetworkChaos()) {
        return true;
      }
    }
    return false;
  }

  private static ExtensionContext requireContext() {
    final ExtensionContext context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException("Called outside @RedisStandalone test context");
    }
    return context;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, RedisConnectionInfo> getInstancesMap(final ExtensionContext context) {
    return context.getStore(NAMESPACE).get(CONTAINER_MAP_KEY, Map.class);
  }

  private static Map<String, Store> getStoresMap(final ExtensionContext context) {
    final CloseableStoreMap wrapper =
        context.getStore(NAMESPACE).get(STORES_MAP_KEY, CloseableStoreMap.class);
    return wrapper != null ? wrapper.getStores() : Collections.emptyMap();
  }

  // ==================== Inner Classes ====================

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
   * Wrapper for {@code Map<String, Store>} implementing CloseableResource.
   *
   * <p>JUnit 5's Store only calls close() on directly stored CloseableResource objects.
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
}
