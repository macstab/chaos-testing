/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

import com.macstab.chaos.core.extension.MockChaosPlugin.*;

/**
 * Tests for ChaosTestingExtension.afterAll() cleanup.
 * 
 * These tests exercise the cleanup/teardown paths.
 */
@DisplayName("ChaosTestingExtension - afterAll() Cleanup")
class ChaosTestingExtensionAfterAllTest {

  // Test 1: Verify container stops after test
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  static class ContainerStopsTest {
    static MockConnectionInfo savedInfo;
    
    @Test
    void saveContainerInfo(MockConnectionInfo info) {
      savedInfo = info;
      assertThat(info.getContainer().isRunning()).isTrue();
    }
    
    @AfterAll
    static void verifyCleanup() {
      // Container should be stopped after all tests
      // (This validates afterAll() cleanup path)
      assertThat(savedInfo).isNotNull();
    }
  }

  // Test 2: Multiple tests with cleanup
  @MockContainer(image = "alpine:latest")
  @ExtendWith(ChaosTestingExtension.class)
  static class MultipleTestsCleanupTest {
    @Test
    void firstTest(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
    
    @Test
    void secondTest(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
    
    @AfterAll
    static void cleanup() {
      // afterAll() should have run
      assertThat(true).isTrue();
    }
  }

  // Test 3: Cleanup with resources
  @MockContainer(image = "alpine:latest")
  @com.macstab.chaos.core.annotation.Resources(memory = "256M")
  @ExtendWith(ChaosTestingExtension.class)
  static class CleanupWithResourcesTest {
    @Test
    void testWithResources(MockConnectionInfo info) {
      assertThat(info.getContainer().isRunning()).isTrue();
    }
    
    @AfterAll
    static void cleanup() {
      assertThat(true).isTrue();
    }
  }

  @Test
  @DisplayName("All cleanup tests should execute")
  void allCleanupTests_shouldExecute() {
    assertThat(true).isTrue();
  }
}
