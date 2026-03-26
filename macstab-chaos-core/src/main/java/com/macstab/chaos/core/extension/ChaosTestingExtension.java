/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 * ChaosTest} meta-annotation. Container annotations ({@code @RedisStandalone}, etc.) extend {@code
 * @ChaosTest}, which implicitly registers this extension. Users never write {@code @ExtendWith}
 * manually.
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
 * @since 2.0
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

  private static final Map<Class<? extends Annotation>, ChaosPlugin<?>> PLUGINS =
      new HashMap<>();

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
        log.debug("Registered plugin: {} for @{}", plugin.getClass().getSimpleName(),
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
      log.info("Discovered {} container plugin(s): {}", PLUGINS.size(),
          PLUGINS.values().stream()
              .map(p -> p.getClass().getSimpleName())
              .toList());
    }
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    log.debug("ChaosTestingExtension.beforeAll() for test class: {}",
        context.getRequiredTestClass().getSimpleName());

    final List<ContainerInstance> containers = new ArrayList<>();

    final Class<?> testClass = context.getRequiredTestClass();
    final Resources resourcesAnnotation = testClass.getAnnotation(Resources.class);

    for (final Annotation annotation : testClass.getAnnotations()) {
      final ChaosPlugin<?> plugin = PLUGINS.get(annotation.annotationType());

      if (plugin != null) {
        log.info("Creating container for @{} in test class {}",
            annotation.annotationType().getSimpleName(),
            testClass.getSimpleName());

        final ContainerInstance instance = createContainerInstance(plugin, annotation,
            resourcesAnnotation);
        containers.add(instance);

        log.info("Starting container: {} (image: {})",
            annotation.annotationType().getSimpleName(),
            instance.container.getDockerImageName());

        instance.container.start();

        log.info("Container started: {} -> {}:{}",
            annotation.annotationType().getSimpleName(),
            instance.container.getHost(),
            instance.container.getFirstMappedPort());
      }
    }

    if (containers.isEmpty()) {
      log.debug("No container annotations found on test class: {}",
          testClass.getSimpleName());
    }

    context.getStore(NAMESPACE).put(CONTAINERS_KEY, containers);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    log.debug("ChaosTestingExtension.afterAll() for test class: {}",
        context.getRequiredTestClass().getSimpleName());

    @SuppressWarnings("unchecked")
    final List<ContainerInstance> containers =
        (List<ContainerInstance>) context.getStore(NAMESPACE).get(CONTAINERS_KEY);

    if (containers != null) {
      for (final ContainerInstance instance : containers) {
        try {
          log.info("Stopping container: {}", instance.annotation.annotationType().getSimpleName());
          instance.container.stop();
        } catch (final Exception e) {
          log.warn("Failed to stop container: {}", instance.annotation.annotationType().getSimpleName(), e);
        }
      }
    }
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException {

    final Parameter parameter = parameterContext.getParameter();
    final Class<?> parameterType = parameter.getType();

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

    final Class<?> parameterType = parameterContext.getParameter().getType();

    @SuppressWarnings("unchecked")
    final List<ContainerInstance> containers =
        (List<ContainerInstance>) extensionContext.getStore(NAMESPACE).get(CONTAINERS_KEY);

    if (containers == null || containers.isEmpty()) {
      throw new ParameterResolutionException(
          "No containers found. Did you add container annotation to test class?");
    }

    for (final ContainerInstance instance : containers) {
      if (instance.connectionInfo.getClass().equals(parameterType)) {
        return instance.connectionInfo;
      }
    }

    throw new ParameterResolutionException(
        String.format(
            "No container connection info found for parameter type: %s",
            parameterType.getName()));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ContainerInstance createContainerInstance(
      final ChaosPlugin plugin,
      final Annotation annotation,
      final Resources resourcesAnnotation) {

    try {
      final GenericContainer<?> container = plugin.createContainer(annotation);

      if (container == null) {
        throw new ExtensionConfigurationException(
            String.format(
                "Plugin %s returned null container for @%s",
                plugin.getClass().getSimpleName(),
                annotation.annotationType().getSimpleName()));
      }

      applyResourceConstraints(container, annotation, resourcesAnnotation);

      container.start();

      final Object connectionInfo = plugin.createConnectionInfo(container, annotation);

      if (connectionInfo == null) {
        throw new ExtensionConfigurationException(
            String.format(
                "Plugin %s returned null connection info for @%s",
                plugin.getClass().getSimpleName(),
                annotation.annotationType().getSimpleName()));
      }

      return new ContainerInstance(container, annotation, connectionInfo);

    } catch (final IllegalArgumentException e) {
      throw new ExtensionConfigurationException(
          String.format(
              "Invalid configuration in @%s: %s",
              annotation.annotationType().getSimpleName(),
              e.getMessage()),
          e);
    } catch (final Exception e) {
      throw new ExtensionConfigurationException(
          String.format(
              "Failed to create container for @%s: %s",
              annotation.annotationType().getSimpleName(),
              e.getMessage()),
          e);
    }
  }

  private void applyResourceConstraints(
      final GenericContainer<?> container,
      final Annotation annotation,
      final Resources resourcesAnnotation) {

    if (resourcesAnnotation == null) {
      log.debug("No @Resources annotation found for @{}", annotation.annotationType().getSimpleName());
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
        log.info("Applied memory limit: {} ({} bytes) to @{}",
            memory, memoryBytes, annotation.annotationType().getSimpleName());
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid memory format in @%s (memory=\"%s\"): %s",
                annotation.annotationType().getSimpleName(),
                memory,
                e.getMessage()),
            e);
      }
    }

    if (!cpus.isBlank()) {
      try {
        final long nanoCpus = ResourceParser.parseCpuNanoCpus(cpus);
        container.withCreateContainerCmdModifier(
            cmd -> cmd.getHostConfig().withCpuQuota(nanoCpus / 1000L).withCpuPeriod(100000L));
        log.info("Applied CPU limit: {} ({} nano-CPUs) to @{}",
            cpus, nanoCpus, annotation.annotationType().getSimpleName());
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid CPU format in @%s (cpus=\"%s\"): %s",
                annotation.annotationType().getSimpleName(),
                cpus,
                e.getMessage()),
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
          log.info("Applied disk size limit: {} to @{}",
              diskSize, annotation.annotationType().getSimpleName());
        } else {
          log.warn(
              "Disk size constraint '{}' ignored (requires Linux + overlay2 driver, detected: {})",
              diskSize, osName);
        }
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid disk size format in @%s (diskSize=\"%s\"): %s",
                annotation.annotationType().getSimpleName(),
                diskSize,
                e.getMessage()),
            e);
      }
    }
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
