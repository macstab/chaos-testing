/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

import com.macstab.chaos.core.annotation.Resources;
import com.macstab.chaos.core.extension.MockChaosPlugin.*;

/**
 * Tests for ChaosTestingExtension.beforeAll() orchestration.
 *
 * <p>These tests exercise the full container lifecycle to hit beforeAll() orchestration paths.
 */
@DisplayName("ChaosTestingExtension - beforeAll() Orchestration")
class ChaosTestingExtensionBeforeAllTest {

  // Test 1: Simple container creation
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Simple Container Creation")
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  class SimpleContainerTest {
    @Test
    void testShouldRun(MockConnectionInfo info) {
      assertThat(info).isNotNull();
      assertThat(info.getContainer()).isNotNull();
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 2: Container with memory resource
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Container With Memory Resource")
  @MockContainer(image = "alpine:latest")
  @Resources(memory = "256M")
  @ExtendWith(ChaosTestingExtension.class)
  class MemoryResourceTest {
    @Test
    void testWithMemory(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 3: Container with CPU resource
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Container With CPU Resource")
  @MockContainer(image = "alpine:latest")
  @Resources(cpus = "2")
  @ExtendWith(ChaosTestingExtension.class)
  class CpuResourceTest {
    @Test
    void testWithCpu(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 4: Container with both resources
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Container With Both Resources")
  @MockContainer(image = "alpine:latest")
  @Resources(memory = "512M", cpus = "1.5")
  @ExtendWith(ChaosTestingExtension.class)
  class BothResourcesTest {
    @Test
    void testWithBothResources(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 5: Different image
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Different Image")
  @MockContainer(image = "nginx:alpine")
  @ExtendWith(ChaosTestingExtension.class)
  class DifferentImageTest {
    @Test
    void testWithDifferentImage(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 6: Different port
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Different Port")
  @MockContainer(image = "alpine:latest", port = 9090)
  @ExtendWith(ChaosTestingExtension.class)
  class DifferentPortTest {
    @Test
    void testWithDifferentPort(MockConnectionInfo info) {
      assertThat(info.getPort()).isGreaterThan(0);
    }
  }

  // Test 7: Multiple tests in same class
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Multiple Tests Same Container")
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  class MultipleTestsTest {
    @Test
    void firstTest(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }

    @Test
    void secondTest(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }

    @Test
    void thirdTest(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 8: No resources annotation
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("No Resources Annotation")
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  class NoResourcesTest {
    @Test
    void testWithoutResources(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 9: Small memory
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Small Memory Resource")
  @MockContainer(image = "alpine:latest")
  @Resources(memory = "128M")
  @ExtendWith(ChaosTestingExtension.class)
  class SmallMemoryTest {
    @Test
    void testWithSmallMemory(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 10: Fractional CPU
  @Nested
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @DisplayName("Fractional CPU Resource")
  @MockContainer(image = "alpine:latest")
  @Resources(cpus = "0.5")
  @ExtendWith(ChaosTestingExtension.class)
  class FractionalCpuTest {
    @Test
    void testWithFractionalCpu(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }
}
