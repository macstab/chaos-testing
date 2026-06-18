/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import lombok.extern.slf4j.Slf4j;

/**
 * JUnit 5 extension for core chaos infrastructure annotations.
 *
 * <p>Processes {@link com.macstab.chaos.core.annotation.InstallPackages}, {@link
 * com.macstab.chaos.core.annotation.InstallTools}, {@link
 * com.macstab.chaos.core.annotation.RequireCapability}, and {@link
 * com.macstab.chaos.core.annotation.ConfigureContainer} annotations.
 *
 * <p><strong>Execution order:</strong>
 *
 * <ol>
 *   <li>Collect CLASS-level and FIELD-level annotations
 *   <li>Group by container
 *   <li>Merge and deduplicate packages/tools
 *   <li>Validate capabilities (fail-fast if missing)
 *   <li>Validate resource limits (fail-fast if insufficient)
 *   <li>Install packages/tools
 * </ol>
 *
 * <p><strong>Lifecycle:</strong> Runs in {@code @BeforeEach} phase (after containers are started).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CoreExtension implements BeforeEachCallback {

  /** Creates a core extension instance (used by JUnit 5 SPI). */
  public CoreExtension() {}

  private static final String PROCESSED_KEY = "core.annotations.processed";

  @Override
  public void beforeEach(final ExtensionContext context) {
    // Skip if already processed (avoid duplicate processing)
    if (isAlreadyProcessed(context)) {
      return;
    }

    final Class<?> testClass = context.getRequiredTestClass();
    final Object testInstance = context.getRequiredTestInstance();

    log.debug("Processing core annotations for test class: {}", testClass.getSimpleName());

    try {
      // Collect all annotations grouped by container
      final Map<Field, List<Annotation>> annotationsByContainer =
          AnnotationCollector.collect(testClass, testInstance);

      if (annotationsByContainer.isEmpty()) {
        log.debug("No core annotations found for test class: {}", testClass.getSimpleName());
        markAsProcessed(context);
        return;
      }

      log.info(
          "Found core annotations for {} container(s) in test class: {}",
          annotationsByContainer.size(),
          testClass.getSimpleName());

      // Process each container
      for (final Map.Entry<Field, List<Annotation>> entry : annotationsByContainer.entrySet()) {
        final Field field = entry.getKey();
        final List<Annotation> annotations = entry.getValue();

        log.debug(
            "Processing {} annotation(s) for container '{}'", annotations.size(), field.getName());

        // Order matters: validate first, then install
        processCapabilities(field, testInstance, annotations);
        processConfiguration(field, testInstance, annotations);
        processPackageInstallation(field, testInstance, annotations);
      }

      log.info(
          "Successfully processed core annotations for test class: {}", testClass.getSimpleName());

    } catch (final Exception e) {
      log.error(
          "Failed to process core annotations for test class: {}", testClass.getSimpleName(), e);
      throw new IllegalStateException(
          String.format(
              "Core annotation processing failed for test class '%s': %s",
              testClass.getSimpleName(), e.getMessage()),
          e);
    } finally {
      markAsProcessed(context);
    }
  }

  /**
   * Process capability validation.
   *
   * @param field field
   * @param testInstance test instance
   * @param annotations annotations
   */
  private void processCapabilities(
      final Field field, final Object testInstance, final List<Annotation> annotations) {

    try {
      CapabilityHandler.process(field, testInstance, annotations);
    } catch (final Exception e) {
      throw new IllegalStateException(
          String.format(
              "Capability validation failed for container '%s': %s",
              field.getName(), e.getMessage()),
          e);
    }
  }

  /**
   * Process configuration validation.
   *
   * @param field field
   * @param testInstance test instance
   * @param annotations annotations
   */
  private void processConfiguration(
      final Field field, final Object testInstance, final List<Annotation> annotations) {

    try {
      ContainerConfigHandler.process(field, testInstance, annotations);
    } catch (final Exception e) {
      throw new IllegalStateException(
          String.format(
              "Configuration validation failed for container '%s': %s",
              field.getName(), e.getMessage()),
          e);
    }
  }

  /**
   * Process package/tool installation.
   *
   * @param field field
   * @param testInstance test instance
   * @param annotations annotations
   */
  private void processPackageInstallation(
      final Field field, final Object testInstance, final List<Annotation> annotations) {

    try {
      PackageInstallationHandler.process(field, testInstance, annotations);
    } catch (final Exception e) {
      throw new IllegalStateException(
          String.format(
              "Package installation failed for container '%s': %s",
              field.getName(), e.getMessage()),
          e);
    }
  }

  /**
   * Check if annotations already processed for this test.
   *
   * @param context extension context
   * @return true if already processed
   */
  private boolean isAlreadyProcessed(final ExtensionContext context) {
    final Boolean processed =
        context
            .getStore(ExtensionContext.Namespace.create(CoreExtension.class))
            .get(PROCESSED_KEY, Boolean.class);

    return processed != null && processed;
  }

  /**
   * Mark annotations as processed for this test.
   *
   * @param context extension context
   */
  private void markAsProcessed(final ExtensionContext context) {
    context
        .getStore(ExtensionContext.Namespace.create(CoreExtension.class))
        .put(PROCESSED_KEY, true);
  }
}
