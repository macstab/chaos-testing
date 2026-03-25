/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.internal.ContainerResolver;
import com.macstab.chaos.core.extension.internal.ValidationUtils;
import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles {@link com.macstab.chaos.core.annotation.InstallPackages} and {@link
 * com.macstab.chaos.core.annotation.InstallTools} annotations.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class PackageInstallationHandler {

  private PackageInstallationHandler() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Process package/tool installations for container.
   *
   * @param field container field
   * @param testInstance test instance
   * @param annotations list of annotations for this container
   */
  public static void process(
      final Field field, final Object testInstance, final List<Annotation> annotations) {

    Objects.requireNonNull(field, "field must not be null");
    Objects.requireNonNull(testInstance, "testInstance must not be null");
    Objects.requireNonNull(annotations, "annotations must not be null");

    final GenericContainer<?> container = ContainerResolver.getContainer(field, testInstance);

    if (!container.isRunning()) {
      log.warn("Container '{}' not running, skipping package installation", field.getName());
      return;
    }

    // Merge packages and tools
    final List<String> packages = AnnotationMerger.mergePackages(annotations);
    final List<Tool> tools = AnnotationMerger.mergeTools(annotations);
    final boolean verify = AnnotationMerger.requiresVerification(annotations);

    if (packages.isEmpty() && tools.isEmpty()) {
      return;
    }

    // Validate
    for (final String pkg : packages) {
      ValidationUtils.validatePackageName(pkg);
    }

    // Detect platform
    final Platform platform = PlatformDetector.detect(container);

    // Translate tools to packages
    final List<String> translatedPackages = new ArrayList<>(packages);
    for (final Tool tool : tools) {
      final String packageName = platform.getPackageName(tool);
      translatedPackages.add(packageName);
    }

    // Install
    log.info(
        "Installing {} package(s) on container '{}': {}",
        translatedPackages.size(),
        field.getName(),
        translatedPackages);

    try {
      PackageInstaller.install(container, translatedPackages, verify);

      log.info(
          "Successfully installed {} package(s) on container '{}'",
          translatedPackages.size(),
          field.getName());

    } catch (final Exception e) {
      throw new IllegalStateException(
          String.format(
              "Failed to install packages on container '%s': %s", field.getName(), e.getMessage()),
          e);
    }
  }
}
