/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.internal.ContainerResolver;
import com.macstab.chaos.core.extension.internal.ValidationUtils;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.ToolPackage;

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

    final List<String> packages = AnnotationMerger.mergePackages(annotations);
    final List<Tool> tools = AnnotationMerger.mergeTools(annotations);

    if (packages.isEmpty() && tools.isEmpty()) {
      return;
    }

    for (final String pkg : packages) {
      ValidationUtils.validatePackageName(pkg);
    }

    try {
      // @InstallTools — platform-resolved, label-guarded, single install per container lifetime
      if (!tools.isEmpty()) {
        PackageInstaller.ensureInstalled(container, tools.toArray(new Tool[0]));
      }

      // @InstallPackages — raw package names, escape-hatch via ToolPackage, label-guarded
      if (!packages.isEmpty()) {
        final ToolPackage[] toolPackages = packages.stream()
            .map(pkg -> ToolPackage.ofSame(pkg))
            .toArray(ToolPackage[]::new);
        PackageInstaller.ensureInstalled(container, toolPackages);
      }

      log.info("Ensured installation on container '{}': tools={}, packages={}",
          field.getName(), tools, packages);

    } catch (final Exception e) {
      throw new IllegalStateException(
          String.format("Failed to install on container '%s': %s",
              field.getName(), e.getMessage()), e);
    }
  }
}
