/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension.internal;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Optional;

import org.testcontainers.containers.GenericContainer;

/**
 * Matches target attribute to container.
 *
 * <p><strong>INTERNAL USE ONLY</strong> - Not part of public API.
 *
 * <p><strong>Matching strategy:</strong>
 *
 * <ol>
 *   <li>Try to match container ID (from annotations like {@code @RedisStandalone(id = "master")})
 *   <li>If no match, try to match field name
 *   <li>If no match, return empty
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TargetMatcher {

  private TargetMatcher() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Check if target matches container.
   *
   * <p><strong>Empty target:</strong> Always matches (applies to all containers)
   *
   * <p><strong>Non-empty target:</strong> Matches if:
   *
   * <ul>
   *   <li>Container ID equals target (case-sensitive)
   *   <li>Field name equals target (case-sensitive)
   * </ul>
   *
   * @param target target string (empty = match all)
   * @param field container field
   * @param container container instance
   * @return true if matches
   */
  public static boolean matches(
      final String target, final Field field, final GenericContainer<?> container) {

    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(field, "field must not be null");
    Objects.requireNonNull(container, "container must not be null");

    // Empty target = match all
    if (target.isEmpty()) {
      return true;
    }

    // Try container ID first
    final Optional<String> containerId = extractContainerId(field);
    if (containerId.isPresent() && containerId.get().equals(target)) {
      return true;
    }

    // Try field name
    return field.getName().equals(target);
  }

  /**
   * Extract container ID from field annotations.
   *
   * <p>Looks for annotations with {@code id()} method (e.g., {@code @RedisStandalone(id =
   * "master")}).
   *
   * @param field field
   * @return container ID (empty if not found)
   */
  private static Optional<String> extractContainerId(final Field field) {
    for (final var annotation : field.getAnnotations()) {
      try {
        final var idMethod = annotation.annotationType().getMethod("id");
        final var id = (String) idMethod.invoke(annotation);

        if (id != null && !id.isEmpty()) {
          return Optional.of(id);
        }

      } catch (final NoSuchMethodException e) {
        // Annotation doesn't have id() method, skip
      } catch (final Exception e) {
        // Other errors (SecurityException, IllegalAccessException, etc.), skip
      }
    }

    return Optional.empty();
  }
}
