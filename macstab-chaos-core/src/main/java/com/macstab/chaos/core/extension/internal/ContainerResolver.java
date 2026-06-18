/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

/**
 * Resolves GenericContainer fields in test class.
 *
 * <p><strong>INTERNAL USE ONLY</strong> - Not part of public API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class ContainerResolver {

  private ContainerResolver() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Find all GenericContainer fields in test class.
   *
   * <p>Scans the test class hierarchy for fields of type {@link GenericContainer}.
   *
   * @param testClass test class
   * @return list of container fields (empty if none found)
   * @throws NullPointerException if testClass is null
   */
  public static List<Field> findContainerFields(final Class<?> testClass) {
    Objects.requireNonNull(testClass, "testClass must not be null");

    final List<Field> containerFields = new ArrayList<>();
    Class<?> currentClass = testClass;

    while (currentClass != null && currentClass != Object.class) {
      for (final Field field : currentClass.getDeclaredFields()) {
        if (isContainerField(field)) {
          containerFields.add(field);
        }
      }
      currentClass = currentClass.getSuperclass();
    }

    return Collections.unmodifiableList(containerFields);
  }

  /**
   * Get container instance from field.
   *
   * @param field field
   * @param testInstance test instance
   * @return container instance
   * @throws IllegalStateException if field access fails
   */
  public static GenericContainer<?> getContainer(final Field field, final Object testInstance) {
    Objects.requireNonNull(field, "field must not be null");
    Objects.requireNonNull(testInstance, "testInstance must not be null");

    field.setAccessible(true);

    try {
      final Object value = field.get(testInstance);

      if (value == null) {
        throw new IllegalStateException(
            String.format("Container field '%s' is null (not initialized)", field.getName()));
      }

      return (GenericContainer<?>) value;

    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          String.format("Cannot access container field '%s'", field.getName()), e);
    }
  }

  /**
   * Check if field is a GenericContainer.
   *
   * @param field field
   * @return true if field is GenericContainer type
   */
  private static boolean isContainerField(final Field field) {
    return GenericContainer.class.isAssignableFrom(field.getType());
  }
}
