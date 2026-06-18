/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.process;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ProcessCommandBuilder} interface.
 *
 * <p>Tests the contract that all implementations must follow.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ProcessCommandBuilder Contract")
class ProcessCommandBuilderTest {

  @Nested
  @DisplayName("Find Process Command Contract")
  class FindProcessCommandContractTests {

    @Test
    @DisplayName("should define buildFindProcessCommand method")
    void shouldDefineBuildFindProcessCommandMethod() throws NoSuchMethodException {
      // Act
      final var method =
          ProcessCommandBuilder.class.getMethod("buildFindProcessCommand", String.class);

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isEqualTo(1);
      assertThat(method.getParameterTypes()[0]).isEqualTo(String.class);
    }
  }

  @Nested
  @DisplayName("Kill Process Command Contract")
  class KillProcessCommandContractTests {

    @Test
    @DisplayName("should define buildKillProcessCommand method")
    void shouldDefineBuildKillProcessCommandMethod() throws NoSuchMethodException {
      // Act
      final var method =
          ProcessCommandBuilder.class.getMethod("buildKillProcessCommand", int.class);

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isEqualTo(1);
      assertThat(method.getParameterTypes()[0]).isEqualTo(int.class);
    }
  }

  @Nested
  @DisplayName("Check Process Command Contract")
  class CheckProcessCommandContractTests {

    @Test
    @DisplayName("should define buildCheckProcessCommand method")
    void shouldDefineBuildCheckProcessCommandMethod() throws NoSuchMethodException {
      // Act
      final var method =
          ProcessCommandBuilder.class.getMethod("buildCheckProcessCommand", int.class);

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isEqualTo(1);
      assertThat(method.getParameterTypes()[0]).isEqualTo(int.class);
    }
  }

  @Nested
  @DisplayName("Kill All Processes Command Contract")
  class KillAllProcessesCommandContractTests {

    @Test
    @DisplayName("should define buildKillAllProcessesCommand method")
    void shouldDefineBuildKillAllProcessesCommandMethod() throws NoSuchMethodException {
      // Act
      final var method =
          ProcessCommandBuilder.class.getMethod("buildKillAllProcessesCommand", String.class);

      // Assert
      assertThat(method).isNotNull();
      assertThat(method.getReturnType()).isEqualTo(String.class);
      assertThat(method.getParameterCount()).isEqualTo(1);
      assertThat(method.getParameterTypes()[0]).isEqualTo(String.class);
    }
  }

  @Nested
  @DisplayName("Interface Contract")
  class InterfaceContractTests {

    @Test
    @DisplayName("should have exactly 4 methods")
    void shouldHaveExactlyFourMethods() {
      // Act
      final int methodCount = ProcessCommandBuilder.class.getDeclaredMethods().length;

      // Assert
      assertThat(methodCount).isEqualTo(4);
    }

    @Test
    @DisplayName("should be a public interface")
    void shouldBePublicInterface() {
      // Assert
      assertThat(ProcessCommandBuilder.class.isInterface()).isTrue();
      assertThat(java.lang.reflect.Modifier.isPublic(ProcessCommandBuilder.class.getModifiers()))
          .isTrue();
    }

    @Test
    @DisplayName("should not extend other interfaces")
    void shouldNotExtendOtherInterfaces() {
      // Assert
      assertThat(ProcessCommandBuilder.class.getInterfaces()).isEmpty();
    }

    @Test
    @DisplayName("should be in correct package")
    void shouldBeInCorrectPackage() {
      // Assert
      assertThat(ProcessCommandBuilder.class.getPackageName())
          .isEqualTo("com.macstab.chaos.core.command.process");
    }
  }

  @Nested
  @DisplayName("Implementation Requirements")
  class ImplementationRequirementsTests {

    @Test
    @DisplayName("should have PsCommandBuilder implementation")
    void shouldHavePsCommandBuilderImplementation() {
      // Act
      final var implementationClass = PsCommandBuilder.class;

      // Assert
      assertThat(ProcessCommandBuilder.class.isAssignableFrom(implementationClass)).isTrue();
    }

    @Test
    @DisplayName("should have ProcFsCommandBuilder implementation")
    void shouldHaveProcFsCommandBuilderImplementation() throws ClassNotFoundException {
      // Act
      final var implementationClass =
          Class.forName("com.macstab.chaos.core.command.process.ProcFsCommandBuilder");

      // Assert
      assertThat(ProcessCommandBuilder.class.isAssignableFrom(implementationClass)).isTrue();
    }
  }

  @Nested
  @DisplayName("Documentation Contract")
  class DocumentationContractTests {

    @Test
    @DisplayName("should have self-documenting method names")
    void shouldHaveSelfDocumentingMethodNames() {
      // Verify method names clearly describe their purpose
      assertThat(ProcessCommandBuilder.class.getDeclaredMethods())
          .extracting(java.lang.reflect.Method::getName)
          .containsExactlyInAnyOrder(
              "buildFindProcessCommand",
              "buildKillProcessCommand",
              "buildCheckProcessCommand",
              "buildKillAllProcessesCommand");
    }

    @Test
    @DisplayName("should use consistent naming pattern")
    void shouldUseConsistentNamingPattern() {
      // All methods should start with "build" and end with "Command"
      assertThat(ProcessCommandBuilder.class.getDeclaredMethods())
          .allMatch(m -> m.getName().startsWith("build"))
          .allMatch(m -> m.getName().endsWith("Command"));
    }
  }
}
