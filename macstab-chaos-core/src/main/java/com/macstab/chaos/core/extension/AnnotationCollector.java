/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.annotation.ConfigureContainer;
import com.macstab.chaos.core.annotation.InstallPackages;
import com.macstab.chaos.core.annotation.InstallTools;
import com.macstab.chaos.core.annotation.RequireCapability;
import com.macstab.chaos.core.extension.internal.ContainerResolver;
import com.macstab.chaos.core.extension.internal.TargetMatcher;

/**
 * Collects core annotations from test class.
 *
 * <p>Scans CLASS-level and FIELD-level annotations and groups them by container.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class AnnotationCollector {

  private AnnotationCollector() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Collect all core annotations grouped by container.
   *
   * <p>Returns a map of container field → list of annotations (CLASS + FIELD level).
   *
   * @param testClass test class
   * @param testInstance test instance
   * @return map of field → annotations
   */
  public static Map<Field, List<Annotation>> collect(
      final Class<?> testClass, final Object testInstance) {

    Objects.requireNonNull(testClass, "testClass must not be null");
    Objects.requireNonNull(testInstance, "testInstance must not be null");

    final Map<Field, List<Annotation>> result = new HashMap<>();
    final List<Field> containerFields = ContainerResolver.findContainerFields(testClass);

    if (containerFields.isEmpty()) {
      return result;
    }

    // Collect CLASS-level annotations
    final List<Annotation> classAnnotations = collectClassAnnotations(testClass);

    // For each container field
    for (final Field field : containerFields) {
      final GenericContainer<?> container = ContainerResolver.getContainer(field, testInstance);
      final List<Annotation> annotations = new ArrayList<>();

      // Add matching CLASS-level annotations
      for (final Annotation annotation : classAnnotations) {
        if (matchesTarget(annotation, field, container)) {
          annotations.add(annotation);
        }
      }

      // Add FIELD-level annotations
      annotations.addAll(collectFieldAnnotations(field));

      if (!annotations.isEmpty()) {
        result.put(field, annotations);
      }
    }

    return result;
  }

  /**
   * Collect CLASS-level core annotations.
   *
   * @param testClass test class
   * @return list of annotations
   */
  private static List<Annotation> collectClassAnnotations(final Class<?> testClass) {
    final List<Annotation> annotations = new ArrayList<>();

    for (final Annotation annotation : testClass.getAnnotations()) {
      if (isCoreAnnotation(annotation)) {
        annotations.add(annotation);
      }
    }

    return annotations;
  }

  /**
   * Collect FIELD-level core annotations.
   *
   * @param field field
   * @return list of annotations
   */
  private static List<Annotation> collectFieldAnnotations(final Field field) {
    final List<Annotation> annotations = new ArrayList<>();

    for (final Annotation annotation : field.getAnnotations()) {
      if (isCoreAnnotation(annotation)) {
        annotations.add(annotation);
      }
    }

    return annotations;
  }

  /**
   * Check if annotation is a core annotation.
   *
   * @param annotation annotation
   * @return true if core annotation
   */
  private static boolean isCoreAnnotation(final Annotation annotation) {
    return annotation instanceof InstallPackages
        || annotation instanceof InstallTools
        || annotation instanceof RequireCapability
        || annotation instanceof ConfigureContainer;
  }

  /**
   * Check if annotation target matches container.
   *
   * @param annotation annotation
   * @param field field
   * @param container container
   * @return true if matches
   */
  private static boolean matchesTarget(
      final Annotation annotation, final Field field, final GenericContainer<?> container) {

    final String target = extractTarget(annotation);
    return TargetMatcher.matches(target, field, container);
  }

  /**
   * Extract target attribute from annotation.
   *
   * @param annotation annotation
   * @return target string (empty if not found)
   */
  private static String extractTarget(final Annotation annotation) {
    try {
      final var method = annotation.annotationType().getMethod("target");
      final var target = (String) method.invoke(annotation);
      return target != null ? target : "";

    } catch (final Exception e) {
      // No target() method or invocation failed
      return "";
    }
  }
}
