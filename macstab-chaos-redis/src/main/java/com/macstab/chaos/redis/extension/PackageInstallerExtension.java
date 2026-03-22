/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.redis.annotation.InstallPackages;

/**
 * JUnit 5 extension that processes {@link InstallPackages} annotations on container fields.
 *
 * <p><strong>Purpose:</strong> Automatically installs packages in containers annotated with
 * {@code @InstallPackages}, enabling annotation-driven package management for ANY Testcontainer.
 *
 * <p><strong>Lifecycle Integration:</strong> This extension runs AFTER Testcontainers'
 * {@code @Container} lifecycle has started containers, ensuring packages are installed in running
 * containers.
 *
 * <p><strong>How It Works:</strong>
 *
 * <ol>
 *   <li>JUnit 5 starts test class
 *   <li>Testcontainers extension starts containers annotated with {@code @Container}
 *   <li><strong>This extension runs</strong> (BeforeEachCallback)
 *   <li>Scans for fields annotated with {@code @InstallPackages}
 *   <li>Validates field is a {@link GenericContainer} and is running
 *   <li>Calls {@link PackageInstaller#install} to install packages
 *   <li>Test method executes with packages pre-installed
 * </ol>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * @ExtendWith(PackageInstallerExtension.class)  // Enable package installation
 * class MyTest {
 *
 *     @Container
 *     @InstallPackages({"curl", "jq"})
 *     GenericContainer<?> postgres = new GenericContainer<>("postgres:16");
 *
 *     @Test
 *     void testWithInstalledPackages() {
 *         // ✅ curl and jq are already installed!
 *         var result = postgres.execInContainer("curl", "--version");
 *         assertThat(result.getExitCode()).isZero();
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Automatic Registration:</strong> In future versions, this extension can be
 * auto-registered via {@code META-INF/services/org.junit.jupiter.api.extension.Extension} or
 * {@code @AutoConfigureTestDatabase} to avoid manual {@code @ExtendWith}.
 *
 * <p><strong>Error Handling:</strong>
 *
 * <ul>
 *   <li>Field not a GenericContainer → {@link IllegalStateException}
 *   <li>Container not running → {@link IllegalStateException}
 *   <li>Package installation fails → {@link
 *       com.macstab.chaos.core.exception.PackageInstallationException}
 *   <li>Field access denied → {@link IllegalStateException}
 * </ul>
 *
 * <p><strong>Performance Considerations:</strong>
 *
 * <ul>
 *   <li>Runs once per test method (BeforeEachCallback)
 *   <li>Skips installation if container already has packages (idempotent)
 *   <li>Installation time: 2-6 seconds depending on distribution
 *   <li>Docker layer caching speeds up subsequent runs
 * </ul>
 *
 * <p><strong>Logging:</strong> Uses SLF4J for comprehensive logging:
 *
 * <ul>
 *   <li>INFO: Container found, packages to install, installation success
 *   <li>DEBUG: Field scanning, package deduplication
 *   <li>WARN: Validation issues, non-fatal errors
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see InstallPackages
 * @see PackageInstaller
 */
public class PackageInstallerExtension implements BeforeEachCallback {

  private static final Logger LOGGER = LoggerFactory.getLogger(PackageInstallerExtension.class);

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
      LOGGER.debug("No @InstallPackages annotations found in {}", testClass.getSimpleName());
      return;
    }

    LOGGER.info(
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

    LOGGER.debug(
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
    LOGGER.info(
        "Installing {} package(s) in container from field '{}': {}",
        packages.length,
        field.getName(),
        Arrays.toString(packages));

    PackageInstaller.install(container, Arrays.asList(packages), verify);

    LOGGER.info("✓ Packages installed successfully in field '{}'", field.getName());
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
