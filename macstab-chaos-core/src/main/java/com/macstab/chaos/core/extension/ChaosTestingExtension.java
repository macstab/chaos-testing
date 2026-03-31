/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.annotation.ChaosTest;
import com.macstab.chaos.core.annotation.Resources;
import com.macstab.chaos.core.exception.PluginRegistrationException;
import com.macstab.chaos.core.util.ResourceParser;

import lombok.extern.slf4j.Slf4j;

/**
 * Universal chaos testing extension with plugin-based container support.
 *
 * <p><strong>Purpose:</strong> Single extension that handles lifecycle (beforeAll/afterAll),
 * resource constraints ({@code @Resources}), and plugin orchestration for ALL container types.
 * Eliminates 10× extension duplication (Redis, Postgres, Mongo, etc.).
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * ChaosTestingExtension (1 extension, universal)
 *     ↓ discovers
 * ChaosPlugin (N plugins, container-specific)
 *     ↓ creates
 * GenericContainer (Docker containers)
 * </pre>
 *
 * <p><strong>Auto-Enable via Meta-Annotation:</strong> This extension is enabled via {@link
 * ChaosTest} meta-annotation. Container annotations ({@code @RedisStandalone}, etc.) extend
 * {@code @ChaosTest}, which implicitly registers this extension. Users never write
 * {@code @ExtendWith} manually.
 *
 * <p><strong>Plugin Discovery:</strong> Plugins are discovered via Java ServiceLoader at static
 * initialization time. Register plugins in {@code
 * META-INF/services/com.macstab.chaos.core.extension.ChaosPlugin}.
 *
 * <p><strong>Resource Constraints:</strong> Applies {@code @Resources} annotation (memory, CPU,
 * disk) to all containers. Platform-aware: disk constraints Linux-only, warns on macOS/Windows.
 *
 * <p><strong>Example Usage (User Never Sees This Extension):</strong>
 *
 * <pre>{@code
 * @RedisStandalone              // Implicitly enables ChaosTestingExtension (via @ChaosTest)
 * @Resources(memory="512M")      // Applied by this extension
 * class RedisTest {
 *
 *   @Test
 *   void test(RedisConnectionInfo info) {
 *     // Container started by this extension
 *     // Resources applied by this extension
 *     // Connection info injected by this extension
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 * @see ChaosTest
 * @see ChaosPlugin
 * @see Resources
 */
@Slf4j
public final class ChaosTestingExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosTestingExtension.class);

  private static final String CONTAINERS_KEY = "chaos-containers";

  private static final Map<Class<? extends Annotation>, ChaosPlugin<?>> PLUGINS = new HashMap<>();

  // ThreadLocal storage for programmatic access (by annotation type)
  private static final ThreadLocal<Map<Class<? extends Annotation>, Map<String, Object>>>
      CONNECTION_INFO_BY_ANNOTATION = ThreadLocal.withInitial(HashMap::new);

  // ThreadLocal storage for unified access (by base interface type)
  private static final ThreadLocal<Map<Class<?>, Map<String, Object>>>
      CONNECTION_INFO_BY_BASE_TYPE = ThreadLocal.withInitial(HashMap::new);

  static {
    discoverPlugins();
  }

  @SuppressWarnings("rawtypes")
  private static void discoverPlugins() {
    log.info("Discovering container plugins via ServiceLoader...");

    final ServiceLoader<ChaosPlugin> loader = ServiceLoader.load(ChaosPlugin.class);

    for (final ChaosPlugin plugin : loader) {
      try {
        final Class<? extends Annotation> annotationType = plugin.annotationType();

        if (PLUGINS.containsKey(annotationType)) {
          final ChaosPlugin<?> existing = PLUGINS.get(annotationType);
          throw new PluginRegistrationException(
              String.format(
                  "Duplicate plugin registration for @%s: %s and %s",
                  annotationType.getSimpleName(),
                  existing.getClass().getName(),
                  plugin.getClass().getName()));
        }

        PLUGINS.put(annotationType, plugin);
        log.debug(
            "Registered plugin: {} for @{}",
            plugin.getClass().getSimpleName(),
            annotationType.getSimpleName());

      } catch (final Exception e) {
        log.error("Failed to register plugin: {}", plugin.getClass().getName(), e);
        throw new PluginRegistrationException(
            "Plugin registration failed for " + plugin.getClass().getName(), e);
      }
    }

    if (PLUGINS.isEmpty()) {
      log.warn(
          "No container plugins discovered. Did you add META-INF/services entry for ChaosPlugin?");
    } else {
      log.info(
          "Discovered {} container plugin(s): {}",
          PLUGINS.size(),
          PLUGINS.values().stream().map(p -> p.getClass().getSimpleName()).toList());
    }
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    log.debug(
        "ChaosTestingExtension.beforeAll() for test class: {}",
        context.getRequiredTestClass().getSimpleName());

    final List<ContainerInstance> containers = new ArrayList<>();

    final Class<?> testClass = context.getRequiredTestClass();
    final Resources resourcesAnnotation = testClass.getAnnotation(Resources.class);

    // Extract all container annotations (including repeatable ones)
    final List<Annotation> containerAnnotations = extractContainerAnnotations(testClass);

    for (final Annotation annotation : containerAnnotations) {
      final ChaosPlugin<?> plugin = PLUGINS.get(annotation.annotationType());

      if (plugin != null) {
        log.info(
            "Creating container for @{} in test class {}",
            annotation.annotationType().getSimpleName(),
            testClass.getSimpleName());

        final ContainerInstance instance =
            createContainerInstance(plugin, annotation, resourcesAnnotation);
        containers.add(instance);

        log.info(
            "Starting container: {} (image: {})",
            annotation.annotationType().getSimpleName(),
            instance.container.getDockerImageName());

        instance.container.start();

        log.info(
            "Container started: {} -> {}{}",
            annotation.annotationType().getSimpleName(),
            instance.container.getHost(),
            instance.container.getExposedPorts().isEmpty()
                ? ""
                : ":" + instance.container.getFirstMappedPort());
      }
    }

    if (containers.isEmpty()) {
      log.debug("No container annotations found on test class: {}", testClass.getSimpleName());
    }

    context.getStore(NAMESPACE).put(CONTAINERS_KEY, containers);
    log.debug(
        "beforeAll: stored {} containers in context {}", containers.size(), context.getUniqueId());
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    log.debug(
        "ChaosTestingExtension.afterAll() for test class: {}",
        context.getRequiredTestClass().getSimpleName());

    try {
      @SuppressWarnings("unchecked")
      final List<ContainerInstance> containers =
          (List<ContainerInstance>) context.getStore(NAMESPACE).get(CONTAINERS_KEY);

      if (containers != null) {
        for (final ContainerInstance instance : containers) {
          try {
            log.info(
                "Stopping container: {}", instance.annotation.annotationType().getSimpleName());
            instance.container.stop();
          } catch (final Exception e) {
            log.warn(
                "Failed to stop container: {}",
                instance.annotation.annotationType().getSimpleName(),
                e);
          }
        }
      }
    } finally {
      // CRITICAL: Always clear ThreadLocal to prevent memory leaks
      CONNECTION_INFO_BY_ANNOTATION.remove();
      CONNECTION_INFO_BY_BASE_TYPE.remove();
      log.debug("Cleared ThreadLocal connection info registry");
    }
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException {

    final Parameter parameter = parameterContext.getParameter();
    final Class<?> parameterType = parameter.getType();

    // Support List<T> parameters
    if (List.class.isAssignableFrom(parameterType)) {
      final Type genericType = parameter.getParameterizedType();
      if (genericType instanceof ParameterizedType) {
        final ParameterizedType pType = (ParameterizedType) genericType;
        final Type[] typeArgs = pType.getActualTypeArguments();
        if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
          final Class<?> elementType = (Class<?>) typeArgs[0];
          for (final ChaosPlugin<?> plugin : PLUGINS.values()) {
            if (plugin.supportedParameterTypes().contains(elementType)) {
              return true;
            }
          }
        }
      }
      return false;
    }

    // Support single instance parameters
    for (final ChaosPlugin<?> plugin : PLUGINS.values()) {
      if (plugin.supportedParameterTypes().contains(parameterType)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException {

    final Parameter parameter = parameterContext.getParameter();
    final Class<?> parameterType = parameter.getType();

    // Get containers from ExtensionContext.store (populated in beforeAll)
    @SuppressWarnings("unchecked")
    final List<ContainerInstance> containers =
        (List<ContainerInstance>) extensionContext.getStore(NAMESPACE).get(CONTAINERS_KEY);

    log.debug(
        "resolveParameter: parameterType={}, containers={}, context={}",
        parameterType.getSimpleName(),
        containers == null ? "null" : containers.size(),
        extensionContext.getUniqueId());

    if (containers == null || containers.isEmpty()) {
      throw new ParameterResolutionException(
          "No containers found. Did you add container annotation to test class?");
    }

    // Resolve List<T> parameters
    if (List.class.isAssignableFrom(parameterType)) {
      final Type genericType = parameter.getParameterizedType();
      if (genericType instanceof ParameterizedType) {
        final ParameterizedType pType = (ParameterizedType) genericType;
        final Type[] typeArgs = pType.getActualTypeArguments();
        if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
          final Class<?> elementType = (Class<?>) typeArgs[0];
          final List<Object> matchingInstances = new ArrayList<>();

          for (final ContainerInstance instance : containers) {
            if (elementType.isAssignableFrom(instance.connectionInfo.getClass())) {
              matchingInstances.add(instance.connectionInfo);
            }
          }

          if (matchingInstances.isEmpty()) {
            throw new ParameterResolutionException(
                String.format(
                    "No container connection info found for List<%s>",
                    elementType.getSimpleName()));
          }

          return matchingInstances;
        }
      }
    }

    // Resolve single instance parameters
    Object matchedInstance = null;
    int matchCount = 0;

    for (final ContainerInstance instance : containers) {
      if (parameterType.isAssignableFrom(instance.connectionInfo.getClass())) {
        matchedInstance = instance.connectionInfo;
        matchCount++;
      }
    }

    if (matchCount == 0) {
      throw new ParameterResolutionException(
          String.format(
              "No container connection info found for parameter type: %s",
              parameterType.getSimpleName()));
    }

    if (matchCount > 1) {
      throw new ParameterResolutionException(
          String.format(
              "Multiple containers found for parameter type %s (found: %d). Use List<%s> for multiple instances or specify ID programmatically via INSTANCE.get(\"id\")",
              parameterType.getSimpleName(), matchCount, parameterType.getSimpleName()));
    }

    return matchedInstance;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ContainerInstance createContainerInstance(
      final ChaosPlugin plugin, final Annotation annotation, final Resources resourcesAnnotation) {

    try {
      final GenericContainer<?> container = plugin.createContainer(annotation);

      if (container == null) {
        throw new ExtensionConfigurationException(
            String.format(
                "Plugin %s returned null container for @%s",
                plugin.getClass().getSimpleName(), annotation.annotationType().getSimpleName()));
      }

      applyResourceConstraints(container, annotation, resourcesAnnotation);

      container.start();

      final Object connectionInfo = plugin.createConnectionInfo(container, annotation);

      if (connectionInfo == null) {
        throw new ExtensionConfigurationException(
            String.format(
                "Plugin %s returned null connection info for @%s",
                plugin.getClass().getSimpleName(), annotation.annotationType().getSimpleName()));
      }

      // Store connection info for programmatic access
      storeConnectionInfo(annotation, connectionInfo);

      return new ContainerInstance(container, annotation, connectionInfo);

    } catch (final IllegalArgumentException e) {
      throw new ExtensionConfigurationException(
          String.format(
              "Invalid configuration in @%s: %s",
              annotation.annotationType().getSimpleName(), e.getMessage()),
          e);
    } catch (final Exception e) {
      throw new ExtensionConfigurationException(
          String.format(
              "Failed to create container for @%s: %s",
              annotation.annotationType().getSimpleName(), e.getMessage()),
          e);
    }
  }

  private void applyResourceConstraints(
      final GenericContainer<?> container,
      final Annotation annotation,
      final Resources resourcesAnnotation) {

    if (resourcesAnnotation == null) {
      log.debug(
          "No @Resources annotation found for @{}", annotation.annotationType().getSimpleName());
      return;
    }

    final String memory = resourcesAnnotation.memory();
    final String cpus = resourcesAnnotation.cpus();
    final String diskSize = resourcesAnnotation.diskSize();

    if (!memory.isBlank()) {
      try {
        final long memoryBytes = ResourceParser.parseMemoryBytes(memory);
        container.withCreateContainerCmdModifier(
            cmd -> cmd.getHostConfig().withMemory(memoryBytes));
        log.info(
            "Applied memory limit: {} ({} bytes) to @{}",
            memory,
            memoryBytes,
            annotation.annotationType().getSimpleName());
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid memory format in @%s (memory=\"%s\"): %s",
                annotation.annotationType().getSimpleName(), memory, e.getMessage()),
            e);
      }
    }

    if (!cpus.isBlank()) {
      try {
        final long nanoCpus = ResourceParser.parseCpuNanoCpus(cpus);
        container.withCreateContainerCmdModifier(
            cmd -> cmd.getHostConfig().withCpuQuota(nanoCpus / 1000L).withCpuPeriod(100000L));
        log.info(
            "Applied CPU limit: {} ({} nano-CPUs) to @{}",
            cpus,
            nanoCpus,
            annotation.annotationType().getSimpleName());
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid CPU format in @%s (cpus=\"%s\"): %s",
                annotation.annotationType().getSimpleName(), cpus, e.getMessage()),
            e);
      }
    }

    if (!diskSize.isBlank()) {
      try {
        final String sizeOption = ResourceParser.parseDiskSizeOption(diskSize);

        final String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("linux")) {
          container.withCreateContainerCmdModifier(
              cmd -> cmd.getHostConfig().withStorageOpt(Map.of("size", diskSize)));
          log.info(
              "Applied disk size limit: {} to @{}",
              diskSize,
              annotation.annotationType().getSimpleName());
        } else {
          log.warn(
              "Disk size constraint '{}' ignored (requires Linux + overlay2 driver, detected: {})",
              diskSize,
              osName);
        }
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid disk size format in @%s (diskSize=\"%s\"): %s",
                annotation.annotationType().getSimpleName(), diskSize, e.getMessage()),
            e);
      }
    }
  }

  /**
   * Stores connection info for programmatic access.
   *
   * @param annotation container annotation
   * @param connectionInfo connection info object
   */
  private void storeConnectionInfo(final Annotation annotation, final Object connectionInfo) {
    final Class<? extends Annotation> annotationType = annotation.annotationType();
    final String id = extractId(annotation);

    // Store by annotation type
    CONNECTION_INFO_BY_ANNOTATION
        .get()
        .computeIfAbsent(annotationType, k -> new LinkedHashMap<>())
        .put(id, connectionInfo);

    // Store by base interface types
    for (final Class<?> baseType : getBaseTypes(connectionInfo.getClass())) {
      CONNECTION_INFO_BY_BASE_TYPE
          .get()
          .computeIfAbsent(baseType, k -> new LinkedHashMap<>())
          .put(id, connectionInfo);
    }
  }

  /**
   * Extracts container id from annotation.
   *
   * @param annotation container annotation
   * @return container id (default: "default")
   */
  /**
   * Extracts all container annotations from test class (handles repeatable annotations).
   *
   * @param testClass test class
   * @return list of container annotations
   */
  private List<Annotation> extractContainerAnnotations(final Class<?> testClass) {
    final List<Annotation> result = new ArrayList<>();

    for (final Annotation annotation : testClass.getAnnotations()) {
      final Class<? extends Annotation> annotationType = annotation.annotationType();

      // Direct plugin annotation
      if (PLUGINS.containsKey(annotationType)) {
        result.add(annotation);
        continue;
      }

      // Check if this is a container for repeatable annotations
      try {
        final java.lang.reflect.Method valueMethod = annotationType.getMethod("value");
        final Class<?> returnType = valueMethod.getReturnType();

        if (returnType.isArray()) {
          final Class<?> componentType = returnType.getComponentType();
          if (Annotation.class.isAssignableFrom(componentType)
              && PLUGINS.containsKey(componentType)) {
            // This is a container annotation (e.g., @RedisStandalones)
            final Annotation[] repeated = (Annotation[]) valueMethod.invoke(annotation);
            for (final Annotation repeatedAnnotation : repeated) {
              result.add(repeatedAnnotation);
            }
          }
        }
      } catch (final Exception e) {
        // Not a container annotation, skip
      }
    }

    return result;
  }

  private String extractId(final Annotation annotation) {
    try {
      final java.lang.reflect.Method idMethod = annotation.annotationType().getMethod("id");
      return (String) idMethod.invoke(annotation);
    } catch (final Exception e) {
      return "default";
    }
  }

  /**
   * Gets all base interfaces and classes (excluding Object and Annotation).
   *
   * @param clazz connection info class
   * @return set of base types
   */
  private static List<Class<?>> getBaseTypes(final Class<?> clazz) {
    final List<Class<?>> baseTypes = new ArrayList<>();

    for (final Class<?> iface : clazz.getInterfaces()) {
      if (!iface.equals(Annotation.class)) {
        baseTypes.add(iface);
        baseTypes.addAll(getBaseTypes(iface));
      }
    }

    final Class<?> superclass = clazz.getSuperclass();
    if (superclass != null && !superclass.equals(Object.class)) {
      baseTypes.add(superclass);
      baseTypes.addAll(getBaseTypes(superclass));
    }

    return baseTypes;
  }

  /**
   * Registers connection info from an external extension (e.g., SentinelContainerExtension).
   *
   * <p>Use this when a dedicated JUnit 5 extension manages its own container lifecycle but needs
   * its connection info accessible via {@link ChaosContainers} / {@code INSTANCE.get()}.
   *
   * @param annotationType annotation class (e.g., {@code RedisSentinel.class})
   * @param id container id
   * @param connectionInfo connection info object to register
   */
  public static void registerExternalConnectionInfo(
      final Class<? extends Annotation> annotationType,
      final String id,
      final Object connectionInfo) {

    CONNECTION_INFO_BY_ANNOTATION
        .get()
        .computeIfAbsent(annotationType, k -> new LinkedHashMap<>())
        .put(id, connectionInfo);

    for (final Class<?> baseType : getBaseTypes(connectionInfo.getClass())) {
      CONNECTION_INFO_BY_BASE_TYPE
          .get()
          .computeIfAbsent(baseType, k -> new LinkedHashMap<>())
          .put(id, connectionInfo);
    }
  }

  /**
   * Gets connection info by annotation type and id (for programmatic access).
   *
   * @param annotationType annotation class
   * @param id container id
   * @return connection info object
   * @throws IllegalStateException if no extension active
   * @throws java.util.NoSuchElementException if container not found
   */
  public static Object getConnectionInfo(
      final Class<? extends Annotation> annotationType, final String id) {

    final Map<String, Object> byId = CONNECTION_INFO_BY_ANNOTATION.get().get(annotationType);

    if (byId == null) {
      throw new java.util.NoSuchElementException(
          String.format("No containers found for @%s", annotationType.getSimpleName()));
    }

    final Object connectionInfo = byId.get(id);

    if (connectionInfo == null) {
      throw new java.util.NoSuchElementException(
          String.format(
              "No container found for @%s(id=\"%s\")", annotationType.getSimpleName(), id));
    }

    return connectionInfo;
  }

  /**
   * Gets all connection info objects for annotation type.
   *
   * @param annotationType annotation class
   * @return list of connection info objects (empty if none)
   */
  public static List<Object> getAllConnectionInfo(
      final Class<? extends Annotation> annotationType) {

    final Map<String, Object> byId = CONNECTION_INFO_BY_ANNOTATION.get().get(annotationType);

    if (byId == null) {
      return List.of();
    }

    return new ArrayList<>(byId.values());
  }

  /**
   * Gets all connection info objects implementing base type (unified access).
   *
   * @param baseType base interface class
   * @return list of connection info objects (empty if none)
   */
  public static List<Object> getAllConnectionInfoByBaseType(final Class<?> baseType) {
    final Map<String, Object> byId = CONNECTION_INFO_BY_BASE_TYPE.get().get(baseType);

    if (byId == null) {
      return List.of();
    }

    return new ArrayList<>(byId.values());
  }

  /**
   * Gets connection info by base type and id (unified access).
   *
   * @param baseType base interface class
   * @param id container id
   * @return connection info object
   * @throws java.util.NoSuchElementException if container not found
   */
  public static Object getConnectionInfoByBaseType(final Class<?> baseType, final String id) {
    final Map<String, Object> byId = CONNECTION_INFO_BY_BASE_TYPE.get().get(baseType);

    if (byId == null) {
      throw new java.util.NoSuchElementException(
          String.format("No containers found implementing %s", baseType.getSimpleName()));
    }

    final Object connectionInfo = byId.get(id);

    if (connectionInfo == null) {
      throw new java.util.NoSuchElementException(
          String.format(
              "No container found implementing %s with id=\"%s\"", baseType.getSimpleName(), id));
    }

    return connectionInfo;
  }

  private static final class ContainerInstance {
    final GenericContainer<?> container;
    final Annotation annotation;
    final Object connectionInfo;

    ContainerInstance(
        final GenericContainer<?> container,
        final Annotation annotation,
        final Object connectionInfo) {
      this.container = container;
      this.annotation = annotation;
      this.connectionInfo = connectionInfo;
    }
  }
}
