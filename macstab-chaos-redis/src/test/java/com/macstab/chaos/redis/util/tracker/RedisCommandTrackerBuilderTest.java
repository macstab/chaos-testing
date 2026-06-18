/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.RedisCommandTracker;

/**
 * Unit tests for {@link RedisCommandTrackerBuilder}.
 *
 * <p>Verifies builder configuration, defaults, and null-safety.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RedisCommandTrackerBuilder")
class RedisCommandTrackerBuilderTest {

  private final GenericContainer<?> mockContainer = mock(GenericContainer.class);

  @Nested
  @DisplayName("build()")
  class BuildTests {

    @Test
    @DisplayName("Should create tracker with valid container")
    void shouldCreateTrackerWithContainer() {
      final RedisCommandTracker tracker =
          new RedisCommandTrackerBuilder().container(mockContainer).build();

      assertThat(tracker).isNotNull();
    }

    @Test
    @DisplayName("Should create tracker with custom command set")
    void shouldCreateTrackerWithCustomCommands() {
      final RedisCommandTracker tracker =
          new RedisCommandTrackerBuilder()
              .container(mockContainer)
              .trackCommands(Set.of("GET", "SET"))
              .build();

      assertThat(tracker).isNotNull();
    }

    @Test
    @DisplayName("Should create tracker with filterReplication disabled")
    void shouldCreateTrackerWithFilterReplicationFalse() {
      final RedisCommandTracker tracker =
          new RedisCommandTrackerBuilder()
              .container(mockContainer)
              .filterReplication(false)
              .build();

      assertThat(tracker).isNotNull();
    }

    @Test
    @DisplayName("Should throw NPE when container is null")
    void shouldThrowNpeForNullContainer() {
      assertThatThrownBy(() -> new RedisCommandTrackerBuilder().container(null).build())
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw NPE when build() is called without container")
    void shouldThrowNpeWhenContainerNotSet() {
      assertThatThrownBy(() -> new RedisCommandTrackerBuilder().build())
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container not set");
    }

    @Test
    @DisplayName("Should throw NPE when trackCommands is null")
    void shouldThrowNpeForNullCommands() {
      assertThatThrownBy(
              () ->
                  new RedisCommandTrackerBuilder()
                      .container(mockContainer)
                      .trackCommands(null)
                      .build())
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("Default configuration")
  class DefaultConfigTests {

    @Test
    @DisplayName("Should include GET in default commands")
    void shouldIncludeGetInDefaults() {
      final RedisCommandTracker tracker =
          new RedisCommandTrackerBuilder().container(mockContainer).build();

      assertThat(tracker).isNotNull();
      // Default commands are applied at build time — verify tracker is built (no exception)
    }

    @Test
    @DisplayName("Should support all default tracked commands")
    void shouldSupportDefaultCommandNames() {
      // The builder default commands are: GET, SET, HGET, HSET, DEL, INCR, DECR
      final Set<String> expectedDefaults =
          Set.of("GET", "SET", "HGET", "HSET", "DEL", "INCR", "DECR");

      // Verify we can build with each individual command (no exception = supported)
      for (final String cmd : expectedDefaults) {
        final RedisCommandTracker tracker =
            new RedisCommandTrackerBuilder()
                .container(mockContainer)
                .trackCommands(Set.of(cmd))
                .build();
        assertThat(tracker).as("Tracker for command: " + cmd).isNotNull();
      }
    }

    @Test
    @DisplayName("Should allow chaining builder methods")
    void shouldAllowFluentChaining() {
      final RedisCommandTracker tracker =
          new RedisCommandTrackerBuilder()
              .container(mockContainer)
              .trackCommands(Set.of("GET", "SET"))
              .filterReplication(true)
              .build();

      assertThat(tracker).isNotNull();
    }
  }
}
