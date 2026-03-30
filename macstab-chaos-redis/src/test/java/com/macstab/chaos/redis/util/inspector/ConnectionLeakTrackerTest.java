/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;

import com.macstab.chaos.redis.util.inspector.ConnectionLeakTracker;
import com.macstab.chaos.redis.util.inspector.model.ClientConnectionInfo;

/** Comprehensive unit tests for {@link ConnectionLeakTracker}. */
@DisplayName("ConnectionLeakTracker")
@ExtendWith(MockitoExtension.class)
class ConnectionLeakTrackerTest {

  @Mock RedisCommands<String, String> redisCommands;

  private static String clientListLine(final long id, final String addr, final String cmd) {
    return String.format(
        "id=%d addr=%s fd=8 name= age=0 idle=0 flags=N db=0 sub=0 psub=0 ssub=0 multi=-1 watch=0 qbuf=26 qbuf-free=20448 argv-mem=10 multi-mem=0 tot-mem=22298 rbs=16384 rbp=16384 obl=0 oll=0 omem=0 events=r cmd=%s|client|list user=default library-name=lettuce library-ver=6.5.4 resp=2",
        id, addr, cmd);
  }

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null executor")
    void shouldThrowForNullExecutor() {
      // ARRANGE / ACT / ASSERT
      assertThatThrownBy(() -> new ConnectionLeakTracker(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("hasSnapshot()")
  class HasSnapshot {

    @Test
    @DisplayName("Should return false before snapshot")
    void shouldReturnFalseBeforeSnapshot() {
      // ARRANGE
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);

      // ACT
      final boolean hasSnapshot = tracker.hasSnapshot();

      // ASSERT
      assertThat(hasSnapshot).isFalse();
    }

    @Test
    @DisplayName("Should return true after snapshot")
    void shouldReturnTrueAfterSnapshot() {
      // ARRANGE
      when(redisCommands.clientList())
          .thenReturn(clientListLine(1, "127.0.0.1:12345", "get|test:key"));
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);

      // ACT
      tracker.snapshot();

      // ASSERT
      assertThat(tracker.hasSnapshot()).isTrue();
    }
  }

  @Nested
  @DisplayName("snapshot()")
  class Snapshot {

    @Test
    @DisplayName("Should allow calling snapshot twice and reset baseline")
    void shouldResetBaselineOnSecondSnapshot() {
      // ARRANGE
      when(redisCommands.clientList())
          .thenReturn(clientListLine(1, "127.0.0.1:12345", "get|key1"))
          .thenReturn(clientListLine(2, "127.0.0.1:12346", "get|key2"));
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);

      // ACT
      tracker.snapshot();
      tracker.snapshot();

      // ASSERT
      assertThat(tracker.hasSnapshot()).isTrue();
    }
  }

  @Nested
  @DisplayName("getNewConnections() — no snapshot")
  class GetNewConnectionsNoSnapshot {

    @Test
    @DisplayName("Should throw ISE when no snapshot taken")
    void shouldThrowWhenNoSnapshot() {
      // ARRANGE
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.getNewConnections())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("snapshot()");
    }
  }

  @Nested
  @DisplayName("getNewConnections() — no new connections")
  class GetNewConnectionsNone {

    @Test
    @DisplayName("Should return empty when no new connections")
    void shouldReturnEmptyWhenNoneNew() {
      // ARRANGE
      final String clientList = clientListLine(1, "127.0.0.1:12345", "get|test:key");
      when(redisCommands.clientList()).thenReturn(clientList);
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);
      tracker.snapshot();

      // ACT
      final List<ClientConnectionInfo> newConnections =
          tracker.getNewConnections();

      // ASSERT
      assertThat(newConnections).isEmpty();
    }
  }

  @Nested
  @DisplayName("getNewConnections() — detects new by id")
  class GetNewConnectionsDetectsNew {

    @Test
    @DisplayName("Should detect new connection by id")
    void shouldDetectNewConnection() {
      // ARRANGE
      final String snapshotList = clientListLine(1, "127.0.0.1:12345", "get|key1");
      final String currentList =
          clientListLine(1, "127.0.0.1:12345", "get|key1")
              + "\n"
              + clientListLine(2, "127.0.0.1:12346", "set|key2|val");
      when(redisCommands.clientList()).thenReturn(snapshotList).thenReturn(currentList);
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);
      tracker.snapshot();

      // ACT
      final List<ClientConnectionInfo> newConnections =
          tracker.getNewConnections();

      // ASSERT
      assertThat(newConnections).hasSize(1);
      assertThat(newConnections).hasSize(1);
      assertThat(newConnections.get(0).id()).isEqualTo(2L);
      assertThat(newConnections.get(0).addr()).isEqualTo("127.0.0.1:12346");
    }
  }

  @Nested
  @DisplayName("assertNoLeaks() — passes")
  class AssertNoLeaksPasses {

    @Test
    @DisplayName("Should pass when no new connections")
    void shouldPassWhenNoLeaks() {
      // ARRANGE
      final String clientList = clientListLine(1, "127.0.0.1:12345", "get|key1");
      when(redisCommands.clientList()).thenReturn(clientList);
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);
      tracker.snapshot();

      // ACT / ASSERT
      tracker.assertNoLeaks();
    }
  }

  @Nested
  @DisplayName("assertNoLeaks() — fails")
  class AssertNoLeaksFails {

    @Test
    @DisplayName("Should fail when new connection present")
    void shouldFailWhenLeakPresent() {
      // ARRANGE
      final String snapshotList = clientListLine(1, "127.0.0.1:12345", "get|key1");
      final String currentList =
          snapshotList + "\n" + clientListLine(2, "127.0.0.1:12346", "get|key2");
      when(redisCommands.clientList()).thenReturn(snapshotList).thenReturn(currentList);
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);
      tracker.snapshot();

      // ACT / ASSERT
      assertThatThrownBy(() -> tracker.assertNoLeaks())
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("1 new connection(s)");
    }
  }

  @Nested
  @DisplayName("CLIENT LIST parsing")
  class ClientListParsing {

    @Test
    @DisplayName("Should parse full line correctly")
    void shouldParseFullLineCorrectly() {
      // ARRANGE
      final String clientList =
          clientListLine(42, "192.168.1.100:54321", "hgetall|user:123|field");
      when(redisCommands.clientList()).thenReturn(clientList);
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);
      tracker.snapshot();
      when(redisCommands.clientList())
          .thenReturn(clientList + "\n" + clientListLine(43, "192.168.1.101:54322", "ping"));

      // ACT
      final List<ClientConnectionInfo> newConnections =
          tracker.getNewConnections();

      // ASSERT
      assertThat(newConnections).hasSize(1);
      final ClientConnectionInfo info = newConnections.get(0);
      assertThat(info.id()).isEqualTo(43L);
      assertThat(info.addr()).isEqualTo("192.168.1.101:54322");
      assertThat(info.lastCmd()).startsWith("ping");
    }
  }

  @Nested
  @DisplayName("CLIENT LIST parsing — empty response")
  class ClientListParsingEmpty {

    @Test
    @DisplayName("Should return empty map for empty response")
    void shouldReturnEmptyForEmptyResponse() {
      // ARRANGE
      when(redisCommands.clientList()).thenReturn("");
      final ConnectionLeakTracker tracker = ConnectionLeakTracker.forCommands(redisCommands);
      tracker.snapshot();

      // ACT
      final List<ClientConnectionInfo> newConnections =
          tracker.getNewConnections();

      // ASSERT
      assertThat(newConnections).isEmpty();
    }
  }
}
