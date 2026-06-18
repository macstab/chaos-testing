/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.github.dockerjava.api.model.Capability;

/**
 * Require Docker capabilities for container (validation-only).
 *
 * <p>This annotation validates that the container has required capabilities. It does NOT
 * automatically add capabilities (Docker limitation: capabilities must be set at container creation
 * time).
 *
 * <p><strong>Behavior:</strong> Fails test with clear error message if capabilities are missing.
 *
 * <p><strong>Common capabilities:</strong>
 *
 * <ul>
 *   <li>{@link Capability#NET_ADMIN} - Required for network chaos (iptables, tc)
 *   <li>{@link Capability#SYS_ADMIN} - Required for advanced chaos (cgroups write)
 * </ul>
 *
 * <p><strong>Class-level usage (validates all containers):</strong>
 *
 * <pre>{@code
 * @RequireCapability(Capability.NET_ADMIN)
 * class MyTest {
 *     @RedisStandalone
 *     GenericContainer<?> redis;
 * }
 * }</pre>
 *
 * <p><strong>Field-level usage (validates specific container):</strong>
 *
 * <pre>{@code
 * class MyTest {
 *     @RedisStandalone
 *     @RequireCapability(Capability.NET_ADMIN)
 *     GenericContainer<?> redis;
 * }
 * }</pre>
 *
 * <p><strong>How to add capabilities:</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
 *     .withCreateContainerCmdModifier(cmd ->
 *         cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN));
 * }</pre>
 *
 * <p><strong>Error message example:</strong>
 *
 * <pre>
 * Container 'redis' requires NET_ADMIN capability for network chaos.
 * Add: .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN))
 * </pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see Capability
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface RequireCapability {

  /**
   * Required Docker capabilities.
   *
   * <p><strong>Common capabilities:</strong>
   *
   * <ul>
   *   <li>{@code Capability.NET_ADMIN} - Network administration (iptables, tc, route)
   *   <li>{@code Capability.SYS_ADMIN} - System administration (mount, cgroups, etc.)
   *   <li>{@code Capability.SYS_TIME} - Set system time
   *   <li>{@code Capability.SYS_PTRACE} - Process tracing (strace, gdb)
   * </ul>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @RequireCapability({Capability.NET_ADMIN, Capability.SYS_ADMIN})
   * }</pre>
   *
   * @return required capabilities
   */
  Capability[] value();

  /**
   * Target container ID or field name (for class-level multi-container scenarios).
   *
   * <p><strong>Empty string (default):</strong> Validate all containers
   *
   * <p><strong>Matching strategy:</strong>
   *
   * <ol>
   *   <li>Try to match container ID (e.g., {@code @RedisStandalone(id = "master")})
   *   <li>If no match, try to match field name
   *   <li>If no match, throw exception
   * </ol>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @RequireCapability(value = Capability.NET_ADMIN, target = "master")
   * class MyTest {
   *     @RedisStandalone(id = "master")
   *     GenericContainer<?> master;  // Validated
   *
   *     @RedisStandalone(id = "replica")
   *     GenericContainer<?> replica;  // NOT validated
   * }
   * }</pre>
   *
   * @return target container ID or field name (empty = all containers)
   */
  String target() default "";
}
