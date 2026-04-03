/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Comprehensive unit tests for {@link PackageInstaller}.
 *
 * <p><strong>Coverage Goals:</strong>
 *
 * <ul>
 *   <li>100% line coverage
 *   <li>100% branch coverage
 *   <li>All edge cases (null, empty, errors)
 *   <li>All error conditions (stopped container, verification failure)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("PackageInstaller")
class PackageInstallerTest {

  // ==================== Deduplication Tests ====================

  @Nested
  @DisplayName("Deduplication")
  class DeduplicationTests {

    @Test
    @DisplayName("Should deduplicate packages while preserving order")
    void shouldDeduplicatePackages() {
      // Given: List with duplicates
      final Collection<String> packages = Arrays.asList("curl", "jq", "curl", "vim", "jq");

      // When: Deduplicate
      final List<String> result = PackageInstaller.deduplicatePackages(packages);

      // Then: Duplicates removed, order preserved
      assertThat(result).containsExactly("curl", "jq", "vim");
    }

    @Test
    @DisplayName("Should handle empty packages list")
    void shouldHandleEmptyPackages() {
      // Given: Empty list
      final Collection<String> packages = List.of();

      // When: Deduplicate
      final List<String> result = PackageInstaller.deduplicatePackages(packages);

      // Then: Still empty
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should handle single package")
    void shouldHandleSinglePackage() {
      // Given: Single package
      final Collection<String> packages = List.of("curl");

      // When: Deduplicate
      final List<String> result = PackageInstaller.deduplicatePackages(packages);

      // Then: Same package
      assertThat(result).containsExactly("curl");
    }

    @Test
    @DisplayName("Should handle packages with no duplicates")
    void shouldHandleNoDuplicates() {
      // Given: No duplicates
      final Collection<String> packages = Arrays.asList("curl", "jq", "vim");

      // When: Deduplicate
      final List<String> result = PackageInstaller.deduplicatePackages(packages);

      // Then: Same order, same packages
      assertThat(result).containsExactly("curl", "jq", "vim");
    }

    @Test
    @DisplayName("Should throw NullPointerException for null packages")
    void shouldThrowForNullPackages() {
      // When/Then: Null packages → NPE
      assertThatThrownBy(() -> PackageInstaller.deduplicatePackages(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("packages");
    }
  }

  // ==================== Validation Tests ====================

  @Nested
  @DisplayName("Validation")
  class ValidationTests {

    @Test
    @DisplayName("Should throw NullPointerException for null container")
    void shouldThrowForNullContainer() {
      // When/Then: Null container → NPE
      assertThatThrownBy(() -> PackageInstaller.install(null, List.of("curl"), true))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should throw NullPointerException for null packages collection")
    void shouldThrowForNullPackagesCollection() {
      // Given: Mock container
      final GenericContainer<?> container = mockRunningContainer();

      // When/Then: Null packages → NPE
      assertThatThrownBy(() -> PackageInstaller.install(container, (Collection<String>) null, true))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("packages");
    }

    @Test
    @DisplayName("Should throw IllegalStateException for stopped container")
    void shouldThrowForStoppedContainer() {
      // Given: Stopped container
      final GenericContainer<?> container = mockStoppedContainer();

      // When/Then: Stopped container → IllegalStateException
      assertThatThrownBy(() -> PackageInstaller.install(container, List.of("curl"), true))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Container must be started before installing packages");
    }

    @Test
    @DisplayName("Should return early for empty package list after deduplication")
    void shouldReturnEarlyForEmptyPackages() {
      // Given: Mock running container
      final GenericContainer<?> container = mockRunningContainer();

      // When: Install empty packages (no exception expected)
      // Then: Should complete successfully without any package manager operations
      assertThatCode(() -> PackageInstaller.install(container, List.of(), true))
          .doesNotThrowAnyException();
    }
  }

  // ==================== Installation & Verification Tests ====================

  /**
   * NOTE: Installation and verification tests have been moved to {@link
   * PackageInstallerIntegrationTest}.
   *
   * <p>These tests require real containers (not mocks) to validate actual package installation
   * behavior across multiple Linux distributions (Debian, Alpine, Fedora).
   *
   * <p>Mocking {@code GenericContainer.execInContainer()} with varargs is fragile and doesn't test
   * real behavior. Integration tests provide confidence that package detection and installation
   * work correctly in production scenarios.
   *
   * @see PackageInstallerIntegrationTest
   */

  // ==================== isInstalled Tests ====================

  @Nested
  @DisplayName("isInstalled")
  class IsInstalledTests {

    @Test
    @DisplayName("Should return true if all packages are installed")
    void shouldReturnTrueIfAllInstalled() throws Exception {
      // Given: Mock container with all packages installed
      final GenericContainer<?> container = mockContainerWithPackagesInstalled("curl", "jq");

      // When: Check if installed
      final boolean result = PackageInstaller.isInstalled(container, "curl", "jq");

      // Then: Returns true
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return false if any package is missing")
    void shouldReturnFalseIfAnyMissing() throws Exception {
      // Given: Mock container with only 'curl' installed (jq missing)
      final GenericContainer<?> container = mockContainerWithPackagesInstalled("curl");

      // When: Check if both installed
      final boolean result = PackageInstaller.isInstalled(container, "curl", "jq");

      // Then: Returns false
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should return true for empty packages (vacuous truth)")
    void shouldReturnTrueForEmptyPackages() {
      // Given: Mock container
      final GenericContainer<?> container = mockRunningContainer();

      // When: Check empty packages
      final boolean result = PackageInstaller.isInstalled(container);

      // Then: Returns true (vacuous truth)
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return false if 'which' command throws exception")
    void shouldReturnFalseOnException() throws Exception {
      // Given: Mock container where 'which' throws IOException
      final GenericContainer<?> container = mock(GenericContainer.class);
      when(container.execInContainer("which", "curl")).thenThrow(new IOException("Boom"));

      // When: Check if installed
      final boolean result = PackageInstaller.isInstalled(container, "curl");

      // Then: Returns false (exception caught, logged as warning)
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should throw NullPointerException for null container")
    void shouldThrowForNullContainer() {
      // When/Then: Null container → NPE
      assertThatThrownBy(() -> PackageInstaller.isInstalled(null, "curl"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should throw NullPointerException for null packages")
    void shouldThrowForNullPackages() {
      // Given: Mock container
      final GenericContainer<?> container = mockRunningContainer();

      // When/Then: Null packages → NPE
      assertThatThrownBy(() -> PackageInstaller.isInstalled(container, (String[]) null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("packages");
    }
  }

  // ==================== ensureInstalled Tests ====================

  @Nested
  @DisplayName("ensureInstalled")
  class EnsureInstalledTests {

    @Test
    @DisplayName("skips all tools when all labels already present")
    void skipsWhenAllLabelled() {
      // GIVEN — container with all tools already labelled
      final GenericContainer<?> container = mockRunningContainer();
      final Map<String, String> labels = new HashMap<>();
      labels.put(PackageInstaller.LABEL_PREFIX + "stress-ng", "true");
      labels.put(PackageInstaller.LABEL_PREFIX + "cpulimit", "true");
      when(container.getLabels()).thenReturn(labels);

      // WHEN / THEN — no exception, no install attempted
      assertThatCode(() -> PackageInstaller.ensureInstalled(container,
          ToolPackage.ofSame("stress-ng"),
          ToolPackage.ofSame("cpulimit")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("throws NullPointerException for null container")
    void nullContainer() {
      assertThatThrownBy(() -> PackageInstaller.ensureInstalled(
              null, ToolPackage.ofSame("stress-ng")))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("throws NullPointerException for null tools array")
    void nullTools() {
      final GenericContainer<?> container = mockRunningContainer();
      when(container.getLabels()).thenReturn(new HashMap<>());
      assertThatThrownBy(() -> PackageInstaller.ensureInstalled(
              container, (ToolPackage[]) null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("tools");
    }

    @Test
    @DisplayName("throws NullPointerException for null element in tools array")
    void nullToolElement() {
      final GenericContainer<?> container = mockRunningContainer();
      when(container.getLabels()).thenReturn(new HashMap<>());
      assertThatThrownBy(() -> PackageInstaller.ensureInstalled(
              container, (ToolPackage) null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("ToolPackage element");
    }

    @Test
    @DisplayName("throws IllegalStateException for stopped container")
    void stoppedContainer() {
      final GenericContainer<?> container = mockStoppedContainer();
      assertThatThrownBy(() -> PackageInstaller.ensureInstalled(
              container, ToolPackage.ofSame("stress-ng")))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== Mock Helpers ====================

  /**
   * Creates a mock container that is running.
   *
   * @return mock container
   */
  private static GenericContainer<?> mockRunningContainer() {
    final GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);
    when(container.getContainerId()).thenReturn("abc123def456");
    return container;
  }

  /**
   * Creates a mock container that is stopped.
   *
   * @return mock container
   */
  private static GenericContainer<?> mockStoppedContainer() {
    final GenericContainer<?> container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(false);
    when(container.getContainerId()).thenReturn("abc123def456");
    return container;
  }

  /**
   * Creates a mock container with specific packages installed.
   *
   * @param installedPackages packages that are installed
   * @return mock container
   */
  private static GenericContainer<?> mockContainerWithPackagesInstalled(
      final String... installedPackages) {
    final GenericContainer<?> container = mock(GenericContainer.class);
    try {
      final List<String> installed = Arrays.asList(installedPackages);

      // Mock 'which' command based on installed packages
      doAnswer(
              invocation -> {
                final String pkg = invocation.getArgument(1);
                final ExecResult result = mock(ExecResult.class);
                if (installed.contains(pkg)) {
                  when(result.getExitCode()).thenReturn(0); // Found
                  when(result.getStdout()).thenReturn("/usr/bin/" + pkg + "\n");
                } else {
                  when(result.getExitCode()).thenReturn(1); // Not found
                  when(result.getStdout()).thenReturn("");
                }
                return result;
              })
          .when(container)
          .execInContainer(eq("which"), anyString());

    } catch (Exception e) {
      throw new RuntimeException("Mock setup failed", e);
    }
    return container;
  }
}
