/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.chaos.redis.extension.RedisContainerExtension.Store;

/** Unit tests for the sealed {@link StartupResult} hierarchy. */
@DisplayName("StartupResult Sealed Interface")
class StartupResultTest {

  @Nested
  @DisplayName("Success record")
  class SuccessTests {

    @Test
    @DisplayName("Should store all fields")
    void shouldStoreFields() {
      // ARRANGE
      final RedisConnectionInfo info = new RedisConnectionInfo("localhost", 6379);
      final Store store = mock(Store.class);

      // ACT
      final StartupResult.Success result = new StartupResult.Success("my-id", info, store);

      // ASSERT
      assertThat(result.instanceId()).isEqualTo("my-id");
      assertThat(result.connectionInfo()).isSameAs(info);
      assertThat(result.store()).isSameAs(store);
    }

    @Test
    @DisplayName("Should implement StartupResult")
    void shouldImplementStartupResult() {
      final StartupResult result =
          new StartupResult.Success("id", new RedisConnectionInfo("h", 1), mock(Store.class));
      assertThat(result).isInstanceOf(StartupResult.Success.class);
    }
  }

  @Nested
  @DisplayName("Failure record")
  class FailureTests {

    @Test
    @DisplayName("Should store all fields")
    void shouldStoreFields() {
      final Exception cause = new RuntimeException("container failed");
      final StartupResult.Failure result =
          new StartupResult.Failure("my-id", "Container failed to start", cause);

      assertThat(result.instanceId()).isEqualTo("my-id");
      assertThat(result.errorMessage()).isEqualTo("Container failed to start");
      assertThat(result.error()).isSameAs(cause);
    }

    @Test
    @DisplayName("Should allow null error")
    void shouldAllowNullError() {
      final StartupResult.Failure result = new StartupResult.Failure("id", "msg", null);
      assertThat(result.error()).isNull();
    }
  }

  @Nested
  @DisplayName("Pattern matching")
  class PatternMatching {

    @Test
    @DisplayName("Should support exhaustive switch on sealed interface")
    void shouldSupportPatternMatching() {
      // ARRANGE
      final StartupResult success =
          new StartupResult.Success("s", new RedisConnectionInfo("h", 1), mock(Store.class));
      final StartupResult failure = new StartupResult.Failure("f", "err", null);

      // ACT & ASSERT
      assertThat(classify(success)).isEqualTo("success");
      assertThat(classify(failure)).isEqualTo("failure");
    }

    private String classify(final StartupResult result) {
      return switch (result) {
        case StartupResult.Success s -> "success";
        case StartupResult.Failure f -> "failure";
      };
    }
  }
}
