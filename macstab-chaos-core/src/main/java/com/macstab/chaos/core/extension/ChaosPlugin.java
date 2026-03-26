/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.util.Set;

import org.testcontainers.containers.GenericContainer;

/**
 * SPI for chaos testing plugins.
 *
 * <p><strong>Purpose:</strong> Plugins provide container-specific configuration (image, ports, env
 * vars) without duplicating lifecycle management. The core {@link ChaosTestingExtension} handles
 * lifecycle (start/stop), resource constraints, and chaos orchestration. Plugins focus only on
 * "what makes this container unique."
 *
 * <p><strong>Plugin Discovery:</strong> Plugins are discovered via Java ServiceLoader. Register
 * your plugin in {@code META-INF/services/com.macstab.chaos.core.extension.ChaosPlugin}:
 *
 * <pre>
 * com.macstab.chaos.redis.plugin.RedisPlugin
 * com.macstab.chaos.postgres.plugin.PostgresPlugin
 * </pre>
 *
 * <p><strong>Example Plugin (Redis):</strong>
 *
 * <pre>{@code
 * public final class RedisPlugin implements ChaosPlugin<RedisStandalone> {
 *
 *   @Override
 *   public Class<RedisStandalone> annotationType() {
 *     return RedisStandalone.class;
 *   }
 *
 *   @Override
 *   public GenericContainer<?> createContainer(RedisStandalone annotation) {
 *     GenericContainer<?> container = new GenericContainer<>(
 *         DockerImageName.parse("redis:" + annotation.version()));
 *
 *     container.withExposedPorts(6379);
 *
 *     if (annotation.port() > 0) {
 *       container.setPortBindings(List.of(annotation.port() + ":6379"));
 *     }
 *
 *     return container;
 *   }
 *
 *   @Override
 *   public Object createConnectionInfo(GenericContainer<?> container, RedisStandalone annotation) {
 *     return new RedisConnectionInfo(container.getHost(), container.getMappedPort(6379));
 *   }
 *
 *   @Override
 *   public Set<Class<?>> supportedParameterTypes() {
 *     return Set.of(RedisConnectionInfo.class);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <table border="1">
 *   <caption>Plugin vs Extension responsibilities</caption>
 *   <tr>
 *     <th>Concern</th>
 *     <th>Plugin (Container-Specific)</th>
 *     <th>Extension (Universal)</th>
 *   </tr>
 *   <tr>
 *     <td>Container image</td>
 *     <td>✅ Plugin decides</td>
 *     <td>❌</td>
 *   </tr>
 *   <tr>
 *     <td>Ports, env vars</td>
 *     <td>✅ Plugin decides</td>
 *     <td>❌</td>
 *   </tr>
 *   <tr>
 *     <td>Connection info</td>
 *     <td>✅ Plugin creates</td>
 *     <td>❌</td>
 *   </tr>
 *   <tr>
 *     <td>Lifecycle (start/stop)</td>
 *     <td>❌</td>
 *     <td>✅ Extension handles</td>
 *   </tr>
 *   <tr>
 *     <td>Resource constraints</td>
 *     <td>❌</td>
 *     <td>✅ Extension applies</td>
 *   </tr>
 *   <tr>
 *     <td>Package installation</td>
 *     <td>❌</td>
 *     <td>✅ Extension handles</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Why SPI Pattern?</strong>
 *
 * <ul>
 *   <li><strong>Zero duplication:</strong> Lifecycle logic lives in 1 place ({@link
 *       ChaosTestingExtension})
 *   <li><strong>Open/Closed:</strong> Add new container = implement plugin (no core changes)
 *   <li><strong>ServiceLoader:</strong> Automatic discovery (no manual registration in code)
 *   <li><strong>Clean separation:</strong> Plugin = "what", Extension = "how"
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Plugin instances are shared across all test classes. Plugins
 * MUST be stateless (no mutable fields). Container-specific state belongs in the created {@link
 * GenericContainer} instance.
 *
 * <p><strong>Error Handling:</strong> Plugins should throw {@link IllegalArgumentException} for
 * invalid annotation values. The extension will catch and wrap these in a JUnit-friendly error
 * message.
 *
 * @param <A> Chaos testing annotation type (e.g., {@code RedisStandalone})
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see ChaosTestingExtension
 */
public interface ChaosPlugin<A extends Annotation> {

  /**
   * Returns the annotation type this plugin handles.
   *
   * <p><strong>Purpose:</strong> The extension uses this to match annotations on test classes to
   * the correct plugin.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @Override
   * public Class<RedisStandalone> annotationType() {
   *   return RedisStandalone.class;
   * }
   * }</pre>
   *
   * @return annotation class (not null)
   */
  Class<A> annotationType();

  /**
   * Creates a Testcontainers instance for this chaos scenario.
   *
   * <p><strong>Purpose:</strong> Plugin configures the container (image, ports, env vars,
   * command). The extension will handle lifecycle (start/stop) and resource constraints.
   *
   * <p><strong>What to configure:</strong>
   *
   * <ul>
   *   <li>Docker image (required): {@code new GenericContainer<>("redis:7.4")}
   *   <li>Exposed ports (required): {@code .withExposedPorts(6379)}
   *   <li>Port bindings (optional): {@code .setPortBindings(List.of("6379:6379"))}
   *   <li>Environment vars (optional): {@code .withEnv("POSTGRES_PASSWORD", "test")}
   *   <li>Command args (optional): {@code .withCommand("redis-server", "--maxmemory", "100mb")}
   *   <li>Network chaos (optional): {@code .withCreateContainerCmdModifier(cmd ->
   *       cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN))}
   * </ul>
   *
   * <p><strong>What NOT to configure:</strong>
   *
   * <ul>
   *   <li>❌ Memory limits (extension applies via {@code @Resources})
   *   <li>❌ CPU limits (extension applies via {@code @Resources})
   *   <li>❌ Disk size (extension applies via {@code @Resources})
   *   <li>❌ {@code .start()} (extension calls this)
   *   <li>❌ {@code .stop()} (extension calls this)
   * </ul>
   *
   * @param annotation chaos annotation from test class
   * @return configured container (not started)
   * @throws IllegalArgumentException if annotation values are invalid
   */
  GenericContainer<?> createContainer(A annotation);

  /**
   * Extracts connection information from a started container.
   *
   * <p><strong>Purpose:</strong> Creates a connection info object (e.g., {@code
   * RedisConnectionInfo}) that will be injected into test method parameters.
   *
   * <p><strong>Example (Redis):</strong>
   *
   * <pre>{@code
   * @Override
   * public Object createConnectionInfo(GenericContainer<?> container, RedisStandalone annotation) {
   *   return new RedisConnectionInfo(
   *       container.getHost(),
   *       container.getMappedPort(6379));
   * }
   * }</pre>
   *
   * <p><strong>Example (Postgres):</strong>
   *
   * <pre>{@code
   * @Override
   * public Object createConnectionInfo(GenericContainer<?> container, PostgresStandalone annotation) {
   *   return new PostgresConnectionInfo(
   *       container.getHost(),
   *       container.getMappedPort(5432),
   *       annotation.database(),
   *       annotation.username(),
   *       annotation.password());
   * }
   * }</pre>
   *
   * @param container started container (not null)
   * @param annotation chaos annotation from test class
   * @return connection info object (injected into test parameters)
   */
  Object createConnectionInfo(GenericContainer<?> container, A annotation);

  /**
   * Returns the set of parameter types this plugin can inject.
   *
   * <p><strong>Purpose:</strong> The extension uses this to know which test method parameters can
   * be satisfied by this plugin's connection info.
   *
   * <p><strong>Example (Redis):</strong>
   *
   * <pre>{@code
   * @Override
   * public Set<Class<?>> supportedParameterTypes() {
   *   return Set.of(RedisConnectionInfo.class);
   * }
   * }</pre>
   *
   * <p><strong>Example (Postgres):</strong>
   *
   * <pre>{@code
   * @Override
   * public Set<Class<?>> supportedParameterTypes() {
   *   return Set.of(PostgresConnectionInfo.class, DataSource.class);
   * }
   * }</pre>
   *
   * @return supported parameter types (empty = no injection)
   */
  Set<Class<?>> supportedParameterTypes();
}
