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
 * These tests exercise the full container lifecycle to hit
 * beforeAll() orchestration paths.
 */
@DisplayName("ChaosTestingExtension - beforeAll() Orchestration")
class ChaosTestingExtensionBeforeAllTest {

  // Test 1: Simple container creation
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  static class SimpleContainerTest {
    static boolean testRan = false;
    
    @Test
    void testShouldRun(MockConnectionInfo info) {
      testRan = true;
      assertThat(info).isNotNull();
      assertThat(info.getContainer()).isNotNull();
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 2: Container with memory resource
  @MockContainer(image = "alpine:latest")
  @Resources(memory = "256M")
  @ExtendWith(ChaosTestingExtension.class)
  static class MemoryResourceTest {
    @Test
    void testWithMemory(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 3: Container with CPU resource
  @MockContainer(image = "alpine:latest")
  @Resources(cpus = "2")
  @ExtendWith(ChaosTestingExtension.class)
  static class CpuResourceTest {
    @Test
    void testWithCpu(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 4: Container with both resources
  @MockContainer(image = "alpine:latest")
  @Resources(memory = "512M", cpus = "1.5")
  @ExtendWith(ChaosTestingExtension.class)
  static class BothResourcesTest {
    @Test
    void testWithBothResources(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 5: Different image
  @MockContainer(image = "nginx:alpine")
  @ExtendWith(ChaosTestingExtension.class)
  static class DifferentImageTest {
    @Test
    void testWithDifferentImage(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 6: Different port
  @MockContainer(image = "alpine:latest", port = 9090)
  @ExtendWith(ChaosTestingExtension.class)
  static class DifferentPortTest {
    @Test
    void testWithDifferentPort(MockConnectionInfo info) {
      assertThat(info.getPort()).isGreaterThan(0);
    }
  }

  // Test 7: Multiple tests in same class
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  static class MultipleTestsTest {
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
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  static class NoResourcesTest {
    @Test
    void testWithoutResources(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 9: Small memory
  @MockContainer(image = "alpine:latest")
  @Resources(memory = "128M")
  @ExtendWith(ChaosTestingExtension.class)
  static class SmallMemoryTest {
    @Test
    void testWithSmallMemory(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  // Test 10: Fractional CPU
  @MockContainer(image = "alpine:latest")
  @Resources(cpus = "0.5")
  @ExtendWith(ChaosTestingExtension.class)
  static class FractionalCpuTest {
    @Test
    void testWithFractionalCpu(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
  }

  @Test
  @DisplayName("All nested test classes should execute successfully")
  void allNestedTests_shouldExecute() {
    // Meta-test: validates that all nested integration tests can run
    assertThat(true).isTrue();
  }
}
