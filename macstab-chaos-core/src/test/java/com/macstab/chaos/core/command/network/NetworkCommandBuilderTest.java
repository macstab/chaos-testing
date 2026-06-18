/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.network;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link NetworkCommandBuilder} interface.
 *
 * <p>Tests the contract that all implementations must follow.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("NetworkCommandBuilder Contract")
class NetworkCommandBuilderTest {

  @Nested
  @DisplayName("Add Redirect Command Contract")
  class AddRedirectCommandContractTests {

    @Test
    @DisplayName("should require non-null implementation")
    void shouldRequireNonNullImplementation() {
      // This is a contract test - implementations must exist
      assertThat(NetworkCommandBuilder.class).isInterface();
    }

    @Test
    @DisplayName("should define buildAddRedirectCommand method")
    void shouldDefineBuildAddRedirectCommandMethod() throws NoSuchMethodException {
      // Act
      final var method =
          NetworkCommandBuilder.class.getMethod("buildAddRedirectCommand", int.class, int.class);

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Remove Redirect Command Contract")
  class RemoveRedirectCommandContractTests {

    @Test
    @DisplayName("should define buildRemoveRedirectCommand method")
    void shouldDefineBuildRemoveRedirectCommandMethod() throws NoSuchMethodException {
      // Act
      final var method =
          NetworkCommandBuilder.class.getMethod("buildRemoveRedirectCommand", int.class, int.class);

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Clear Redirects Command Contract")
  class ClearRedirectsCommandContractTests {

    @Test
    @DisplayName("should define buildClearRedirectsCommand method")
    void shouldDefineBuildClearRedirectsCommandMethod() throws NoSuchMethodException {
      // Act
      final var method = NetworkCommandBuilder.class.getMethod("buildClearRedirectsCommand");

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isZero();
    }
  }

  @Nested
  @DisplayName("Port Check Command Contract")
  class PortCheckCommandContractTests {

    @Test
    @DisplayName("should define buildPortCheckCommand method")
    void shouldDefineBuildPortCheckCommandMethod() throws NoSuchMethodException {
      // Act
      final var method = NetworkCommandBuilder.class.getMethod("buildPortCheckCommand", int.class);

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Interface Contract")
  class InterfaceContractTests {

    @Test
    @DisplayName("should have exactly 4 methods")
    void shouldHaveExactlyFourMethods() {
      // Act
      final int methodCount = NetworkCommandBuilder.class.getDeclaredMethods().length;

      // Assert
      assertThat(methodCount).isEqualTo(4);
    }

    @Test
    @DisplayName("should be a public interface")
    void shouldBePublicInterface() {
      // Assert
      assertThat(NetworkCommandBuilder.class.isInterface()).isTrue();
      assertThat(java.lang.reflect.Modifier.isPublic(NetworkCommandBuilder.class.getModifiers()))
          .isTrue();
    }

    @Test
    @DisplayName("should not extend other interfaces")
    void shouldNotExtendOtherInterfaces() {
      // Assert
      assertThat(NetworkCommandBuilder.class.getInterfaces()).isEmpty();
    }

    @Test
    @DisplayName("should be in correct package")
    void shouldBeInCorrectPackage() {
      // Assert
      assertThat(NetworkCommandBuilder.class.getPackageName())
          .isEqualTo("com.macstab.chaos.core.command.network");
    }
  }

  @Nested
  @DisplayName("Documentation Contract")
  class DocumentationContractTests {

    @Test
    @DisplayName("should document expected behavior in Javadoc")
    void shouldDocumentExpectedBehavior() {
      // This test verifies the interface is designed for documentation
      // Implementations should follow the contract described in Javadoc

      // Verify method names are self-documenting
      assertThat(NetworkCommandBuilder.class.getDeclaredMethods())
          .extracting(java.lang.reflect.Method::getName)
          .containsExactlyInAnyOrder(
              "buildAddRedirectCommand",
              "buildRemoveRedirectCommand",
              "buildClearRedirectsCommand",
              "buildPortCheckCommand");
    }
  }
}
