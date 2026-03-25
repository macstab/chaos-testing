/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.annotation.ConfigureContainer;
import com.macstab.chaos.core.annotation.InstallPackages;
import com.macstab.chaos.core.annotation.InstallTools;
import com.macstab.chaos.core.annotation.RequireCapability;
import com.macstab.chaos.core.platform.Tool;

/**
 * Unit tests for {@link AnnotationCollector}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("AnnotationCollector")
class AnnotationCollectorTest {

  @Nested
  @DisplayName("collect")
  class Collect {

    @Test
    @DisplayName("should collect CLASS-level annotations for all containers")
    void shouldCollectClassLevelAnnotationsForAllContainers() {
      final var testInstance = new TestClassWithClassAnnotations();

      final Map<Field, List<Annotation>> result =
          AnnotationCollector.collect(testInstance.getClass(), testInstance);

      assertThat(result).hasSize(2);

      // Both containers should get the CLASS-level annotation
      for (final var annotations : result.values()) {
        assertThat(annotations).hasSize(1);
        assertThat(annotations.get(0)).isInstanceOf(InstallPackages.class);
      }
    }

    @Test
    @DisplayName("should collect FIELD-level annotations")
    void shouldCollectFieldLevelAnnotations() {
      final var testInstance = new TestClassWithFieldAnnotations();

      final Map<Field, List<Annotation>> result =
          AnnotationCollector.collect(testInstance.getClass(), testInstance);

      assertThat(result).hasSize(2);

      // redis field should have InstallPackages
      final var redisAnnotations =
          result.entrySet().stream()
              .filter(e -> e.getKey().getName().equals("redis"))
              .findFirst()
              .map(Map.Entry::getValue)
              .orElseThrow();

      assertThat(redisAnnotations).hasSize(1);
      assertThat(redisAnnotations.get(0)).isInstanceOf(InstallPackages.class);

      // postgres field should have InstallTools
      final var postgresAnnotations =
          result.entrySet().stream()
              .filter(e -> e.getKey().getName().equals("postgres"))
              .findFirst()
              .map(Map.Entry::getValue)
              .orElseThrow();

      assertThat(postgresAnnotations).hasSize(1);
      assertThat(postgresAnnotations.get(0)).isInstanceOf(InstallTools.class);
    }

    @Test
    @DisplayName("should merge CLASS and FIELD annotations")
    void shouldMergeClassAndFieldAnnotations() {
      final var testInstance = new TestClassWithMixedAnnotations();

      final Map<Field, List<Annotation>> result =
          AnnotationCollector.collect(testInstance.getClass(), testInstance);

      assertThat(result).hasSize(1);

      final var annotations = result.values().iterator().next();
      assertThat(annotations).hasSize(2);
      assertThat(annotations)
          .extracting(a -> a.annotationType().getSimpleName())
          .containsExactlyInAnyOrder("InstallPackages", "InstallTools");
    }

    @Test
    @DisplayName("should filter CLASS annotations by target")
    void shouldFilterClassAnnotationsByTarget() {
      final var testInstance = new TestClassWithTargetedClassAnnotation();

      final Map<Field, List<Annotation>> result =
          AnnotationCollector.collect(testInstance.getClass(), testInstance);

      assertThat(result).hasSize(1);

      // Only master should get the annotation (target="master")
      final var masterAnnotations =
          result.entrySet().stream()
              .filter(e -> e.getKey().getName().equals("master"))
              .findFirst()
              .map(Map.Entry::getValue)
              .orElseThrow();

      assertThat(masterAnnotations).hasSize(1);
    }

    @Test
    @DisplayName("should collect all annotation types")
    void shouldCollectAllAnnotationTypes() {
      final var testInstance = new TestClassWithAllAnnotationTypes();

      final Map<Field, List<Annotation>> result =
          AnnotationCollector.collect(testInstance.getClass(), testInstance);

      assertThat(result).hasSize(1);

      final var annotations = result.values().iterator().next();
      assertThat(annotations).hasSize(4);
      assertThat(annotations)
          .extracting(a -> a.annotationType().getSimpleName())
          .containsExactlyInAnyOrder(
              "InstallPackages", "InstallTools", "RequireCapability", "ConfigureContainer");
    }

    @Test
    @DisplayName("should return empty map if no annotations")
    void shouldReturnEmptyMapIfNoAnnotations() {
      final var testInstance = new TestClassWithoutAnnotations();

      final Map<Field, List<Annotation>> result =
          AnnotationCollector.collect(testInstance.getClass(), testInstance);

      assertThat(result).isEmpty();
    }
  }

  // ==================== Test Classes ====================

  @InstallPackages("curl")
  @SuppressWarnings("unused")
  static class TestClassWithClassAnnotations {
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
    GenericContainer<?> postgres = new GenericContainer<>("postgres:15");
  }

  @SuppressWarnings("unused")
  static class TestClassWithFieldAnnotations {
    @InstallPackages("curl")
    GenericContainer<?> redis = new GenericContainer<>("redis:7");

    @InstallTools(Tool.IPTABLES)
    GenericContainer<?> postgres = new GenericContainer<>("postgres:15");
  }

  @InstallPackages("curl")
  @SuppressWarnings("unused")
  static class TestClassWithMixedAnnotations {
    @InstallTools(Tool.IPTABLES)
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
  }

  @InstallPackages(value = "curl", target = "master")
  @SuppressWarnings("unused")
  static class TestClassWithTargetedClassAnnotation {
    GenericContainer<?> master = new GenericContainer<>("redis:7").withLabel("id", "master");
    GenericContainer<?> replica = new GenericContainer<>("redis:7").withLabel("id", "replica");
  }

  @SuppressWarnings("unused")
  static class TestClassWithAllAnnotationTypes {
    @InstallPackages("curl")
    @InstallTools(Tool.IPTABLES)
    @RequireCapability(Capability.NET_ADMIN)
    @ConfigureContainer(memory = "512M", cpus = 2)
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
  }

  @SuppressWarnings("unused")
  static class TestClassWithoutAnnotations {
    GenericContainer<?> redis = new GenericContainer<>("redis:7");
  }
}
