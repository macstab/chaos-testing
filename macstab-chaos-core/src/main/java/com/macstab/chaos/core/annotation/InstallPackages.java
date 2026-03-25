/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Install packages in container using distribution-specific package names.
 *
 * <p>This annotation installs packages WITHOUT platform translation. Package names must match the
 * target distribution exactly.
 *
 * <p><strong>Class-level usage (applies to all containers):</strong>
 *
 * <pre>{@code
 * @InstallPackages({"tcpdump", "netcat"})
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
 *     @InstallPackages({"tcpdump"})
 *     GenericContainer<?> redis;
 * }
 * }</pre>
 *
 * <p><strong>Multi-container targeting (class-level):</strong>
 *
 * <pre>{@code
 * @InstallPackages(value = {"tcpdump"}, target = "master")
 * class MyTest {
 *     @RedisStandalone(id = "master")
 *     GenericContainer<?> master;
 *
 *     @RedisStandalone(id = "replica")
 *     GenericContainer<?> replica;
 * }
 * }</pre>
 *
 * <p><strong>Platform-aware alternative:</strong> Use {@link InstallTools} for automatic
 * distribution-specific package name translation.
 *
 * <p><strong>Execution order:</strong> Class-level annotations are processed first, then
 * field-level. Packages are deduplicated automatically.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see InstallTools
 * @see com.macstab.chaos.core.util.PackageInstaller
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface InstallPackages {

  /**
   * Package names to install (distribution-specific).
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>Debian/Ubuntu: "tcpdump", "netcat", "strace"
   *   <li>Alpine: "tcpdump", "netcat-openbsd", "strace"
   *   <li>RHEL/Fedora: "tcpdump", "nmap-ncat", "strace"
   * </ul>
   *
   * <p><strong>Note:</strong> Package names vary by distribution. For platform-aware installation,
   * use {@link InstallTools} instead.
   *
   * @return package names
   */
  String[] value();

  /**
   * Verify packages after installation using 'which' command.
   *
   * <p><strong>Default:</strong> true (verification enabled)
   *
   * <p><strong>Disable when:</strong> Package has no binary (e.g., "ca-certificates")
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * @InstallPackages(value = {"ca-certificates"}, verify = false)
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
   * @InstallPackages(value = {"tcpdump"}, target = "master")
   * class MyTest {
   *     @RedisStandalone(id = "master")
   *     GenericContainer<?> master;  // Gets tcpdump
   *
   *     @RedisStandalone(id = "replica")
   *     GenericContainer<?> replica;  // Does NOT get tcpdump
   * }
   * }</pre>
   *
   * @return target container ID or field name (empty = all containers)
   */
  String target() default "";
}
