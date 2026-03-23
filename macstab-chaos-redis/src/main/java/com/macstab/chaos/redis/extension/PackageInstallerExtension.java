/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.redis.annotation.InstallPackages;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PackageInstallerExtension implements BeforeEachCallback {
  /**
   * Callback executed before each test method.
   *
   * <p><strong>Process:</strong>
   *
   * <ol>
   *   <li>Get test instance
   *   <li>Scan all fields for {@code @InstallPackages} annotation
   *   <li>For each annotated field:
   *       <ul>
   *         <li>Validate field type (must be GenericContainer)
   *         <li>Make field accessible if private
   *         <li>Get container instance
   *         <li>Validate container is running
   *         <li>Install packages using {@link PackageInstaller}
   *       </ul>
   * </ol>
   *
   * @param context JUnit extension context
   * @throws IllegalStateException if field validation fails or container not running
   * @throws com.macstab.chaos.core.exception.PackageInstallationException if package installation
   *     fails
   */
  @Override
  public void beforeEach(final ExtensionContext context) {
    Objects.requireNonNull(context, "context");

    // Get test instance
    final Object testInstance =
        context
            .getTestInstance()
            .orElseThrow(
                () -> new IllegalStateException("No test instance available in extension context"));

    // Scan for @InstallPackages annotations
    final Class<?> testClass = testInstance.getClass();
    final List<Field> annotatedFields = findAnnotatedFields(testClass);

    if (annotatedFields.isEmpty()) {
      log.debug("No @InstallPackages annotations found in {}", testClass.getSimpleName());
      return;
    }

    log.info(
        "Found {} @InstallPackages annotation(s) in {}",
        annotatedFields.size(),
        testClass.getSimpleName());

    // Process each annotated field
    for (final Field field : annotatedFields) {
      processField(testInstance, field);
    }
  }

  /**
   * Finds all fields annotated with {@code @InstallPackages}.
   *
   * <p><strong>Scope:</strong> Scans declared fields (including private) but not inherited fields.
   *
   * @param testClass test class to scan
   * @return list of annotated fields (empty if none found)
   */
  private List<Field> findAnnotatedFields(final Class<?> testClass) {
    return Arrays.stream(testClass.getDeclaredFields())
        .filter(field -> field.isAnnotationPresent(InstallPackages.class))
        .toList();
  }

  /**
   * Processes a single field annotated with {@code @InstallPackages}.
   *
   * <p><strong>Steps:</strong>
   *
   * <ol>
   *   <li>Get annotation
   *   <li>Validate field type
   *   <li>Get container instance
   *   <li>Validate container is running
   *   <li>Install packages
   * </ol>
   *
   * @param testInstance test instance containing the field
   * @param field annotated field
   * @throws IllegalStateException if validation fails
   * @throws com.macstab.chaos.core.exception.PackageInstallationException if installation fails
   */
  private void processField(final Object testInstance, final Field field) {
    // Get annotation
    final InstallPackages annotation = field.getAnnotation(InstallPackages.class);
    final String[] packages = annotation.value();
    final boolean verify = annotation.verify();

    log.debug(
        "Processing field '{}' with packages: {} (verify={})",
        field.getName(),
        Arrays.toString(packages),
        verify);

    // Validate field type
    validateFieldType(field);

    // Get container instance
    final GenericContainer<?> container = getContainerInstance(testInstance, field);

    // Validate container is running
    validateContainerRunning(container, field);

    // Install packages
    log.info(
        "Installing {} package(s) in container from field '{}': {}",
        packages.length,
        field.getName(),
        Arrays.toString(packages));

    PackageInstaller.install(container, Arrays.asList(packages), verify);

    log.info("✓ Packages installed successfully in field '{}'", field.getName());
  }

  /**
   * Validates that field type is GenericContainer or subclass.
   *
   * @param field field to validate
   * @throws IllegalStateException if field is not a GenericContainer
   */
  private void validateFieldType(final Field field) {
    if (!GenericContainer.class.isAssignableFrom(field.getType())) {
      throw new IllegalStateException(
          String.format(
              "@InstallPackages can only be used on GenericContainer fields. "
                  + "Field '%s' has type '%s'. "
                  + "Either change the field type to GenericContainer<?> or remove @InstallPackages.",
              field.getName(), field.getType().getSimpleName()));
    }
  }

  /**
   * Gets container instance from field, making it accessible if needed.
   *
   * @param testInstance test instance containing the field
   * @param field container field
   * @return container instance
   * @throws IllegalStateException if field access fails or value is null
   */
  private GenericContainer<?> getContainerInstance(final Object testInstance, final Field field) {
    try {
      // Make field accessible if private
      field.setAccessible(true);

      // Get container instance
      final Object value = field.get(testInstance);

      if (value == null) {
        throw new IllegalStateException(
            String.format(
                "Field '%s' annotated with @InstallPackages is null. "
                    + "Ensure the field is initialized before the test runs. "
                    + "If using @Container, the container should be initialized inline: "
                    + "@Container GenericContainer<?> container = new GenericContainer<>(...);",
                field.getName()));
      }

      return (GenericContainer<?>) value;

    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          String.format(
              "Failed to access field '%s' annotated with @InstallPackages. "
                  + "This should not happen as we call setAccessible(true). "
                  + "Please report this as a bug.",
              field.getName()),
          e);
    }
  }

  /**
   * Validates that container is running.
   *
   * @param container container to validate
   * @param field field containing the container
   * @throws IllegalStateException if container is not running
   */
  private void validateContainerRunning(final GenericContainer<?> container, final Field field) {
    if (!container.isRunning()) {
      throw new IllegalStateException(
          String.format(
              "Container in field '%s' is not running. "
                  + "Package installation requires a running container. "
                  + "Ensure the field is annotated with @Container to enable automatic lifecycle management: "
                  + "@Container @InstallPackages({...}) GenericContainer<?> %s = ...;",
              field.getName(), field.getName()));
    }
  }
}
