/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.platform.Tool;

/**
 * Install platform-aware tools in container with automatic package name translation.
 *
 * <p>This annotation uses {@link com.macstab.chaos.core.platform.Platform#getPackageName(Tool)} to
 * translate Tool enums to distribution-specific package names automatically.
 *
 * <p><strong>Platform-aware translation example:</strong>
 *
 * <ul>
 *   <li>Debian: {@code Tool.PROCPS} → "procps"
 *   <li>RHEL: {@code Tool.PROCPS} → "procps-ng"
 *   <li>Alpine: {@code Tool.PROCPS} → "procps"
 * </ul>
 *
 * <p><strong>Class-level usage (applies to all containers):</strong>
 *
 * <pre>{@code
 * @InstallTools({Tool.CURL, Tool.IPTABLES, Tool.PROCPS})
 * class MyTest {
 *     @RedisStandalone
 *     GenericContainer<?> redis;
 * }
 * }</pre>
 *
 * <p><strong>Field-level usage (applies to specific container):</strong>
 *
 * <pre>{@code
 * class MyTest {
 *     @RedisStandalone
 *     @InstallTools({Tool.STRESS_NG})
 *     GenericContainer<?> redis;
 * }
 * }</pre>
 *
 * <p><strong>Multi-container targeting (class-level):</strong>
 *
 * <pre>{@code
 * @InstallTools(value = {Tool.CURL}, target = "master")
 * class MyTest {
 *     @RedisStandalone(id = "master")
 *     GenericContainer<?> master;
 *
 *     @RedisStandalone(id = "replica")
 *     GenericContainer<?> replica;
 * }
 * }</pre>
 *
 * <p><strong>Raw package names:</strong> Use {@link InstallPackages} when you need packages not
 * defined in {@link Tool} enum.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see InstallPackages
 * @see Tool
 * @see com.macstab.chaos.core.platform.Platform#getPackageName(Tool)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface InstallTools {

  /**
   * Tools to install (platform-aware translation).
   *
   * <p><strong>Available tools:</strong>
   *
   * <ul>
   *   <li>{@link Tool#CURL} - HTTP client
   *   <li>{@link Tool#IPTABLES} - Linux firewall
   *   <li>{@link Tool#CA_CERTIFICATES} - CA certificates bundle
   *   <li>{@link Tool#PROCPS} - Process utilities (ps, top, etc.)
   *   <li>{@link Tool#IPROUTE} - IP routing utilities (ip, tc, etc.)
   *   <li>{@link Tool#PYTHON} - Python interpreter
   *   <li>{@link Tool#STRESS_NG} - Stress testing utility
   * </ul>
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @InstallTools({Tool.CURL, Tool.IPTABLES, Tool.CA_CERTIFICATES})
   * }</pre>
   *
   * @return tools to install
   */
  Tool[] value();

  /**
   * Verify tools after installation using 'which' command.
   *
   * <p><strong>Default:</strong> true (verification enabled)
   *
   * <p><strong>Disable when:</strong> Tool has no binary (e.g., {@code Tool.CA_CERTIFICATES})
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @InstallTools(value = {Tool.CA_CERTIFICATES}, verify = false)
   * }</pre>
   *
   * @return true = verify (default), false = skip verification
   */
  boolean verify() default true;

  /**
   * Target container ID or field name (for class-level multi-container scenarios).
   *
   * <p><strong>Empty string (default):</strong> Apply to all containers
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
   * @InstallTools(value = {Tool.CURL}, target = "master")
   * class MyTest {
   *     @RedisStandalone(id = "master")
   *     GenericContainer<?> master;  // Gets curl
   *
   *     @RedisStandalone(id = "replica")
   *     GenericContainer<?> replica;  // Does NOT get curl
   * }
   * }</pre>
   *
   * @return target container ID or field name (empty = all containers)
   */
  String target() default "";
}
