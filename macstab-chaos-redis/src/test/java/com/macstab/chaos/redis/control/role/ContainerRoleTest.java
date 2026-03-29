/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContainerRole} enum.
 *
 * <p>Validates role type checks, index access, and factory methods.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerRole Enum")
class ContainerRoleTest {

  @Nested
  @DisplayName("isMaster()")
  class IsMasterTests {

    @Test
    @DisplayName("MASTER.isMaster() should return true")
    void masterIsMaster() {
      assertThat(ContainerRole.MASTER.isMaster()).isTrue();
    }

    @Test
    @DisplayName("REPLICA_0.isMaster() should return false")
    void replicaIsNotMaster() {
      assertThat(ContainerRole.REPLICA_0.isMaster()).isFalse();
    }

    @Test
    @DisplayName("SENTINEL_0.isMaster() should return false")
    void sentinelIsNotMaster() {
      assertThat(ContainerRole.SENTINEL_0.isMaster()).isFalse();
    }

    @Test
    @DisplayName("UNKNOWN.isMaster() should return false")
    void unknownIsNotMaster() {
      assertThat(ContainerRole.UNKNOWN.isMaster()).isFalse();
    }
  }

  @Nested
  @DisplayName("isReplica()")
  class IsReplicaTests {

    @Test
    @DisplayName("MASTER.isReplica() should return false")
    void masterIsNotReplica() {
      assertThat(ContainerRole.MASTER.isReplica()).isFalse();
    }

    @Test
    @DisplayName("REPLICA_0.isReplica() should return true")
    void replicaIsReplica() {
      assertThat(ContainerRole.REPLICA_0.isReplica()).isTrue();
    }

    @Test
    @DisplayName("REPLICA_1.isReplica() should return true")
    void replica1IsReplica() {
      assertThat(ContainerRole.REPLICA_1.isReplica()).isTrue();
    }

    @Test
    @DisplayName("SENTINEL_0.isReplica() should return false")
    void sentinelIsNotReplica() {
      assertThat(ContainerRole.SENTINEL_0.isReplica()).isFalse();
    }
  }

  @Nested
  @DisplayName("isSentinel()")
  class IsSentinelTests {

    @Test
    @DisplayName("MASTER.isSentinel() should return false")
    void masterIsNotSentinel() {
      assertThat(ContainerRole.MASTER.isSentinel()).isFalse();
    }

    @Test
    @DisplayName("REPLICA_0.isSentinel() should return false")
    void replicaIsNotSentinel() {
      assertThat(ContainerRole.REPLICA_0.isSentinel()).isFalse();
    }

    @Test
    @DisplayName("SENTINEL_0.isSentinel() should return true")
    void sentinelIsSentinel() {
      assertThat(ContainerRole.SENTINEL_0.isSentinel()).isTrue();
    }

    @Test
    @DisplayName("SENTINEL_2.isSentinel() should return true")
    void sentinel2IsSentinel() {
      assertThat(ContainerRole.SENTINEL_2.isSentinel()).isTrue();
    }
  }

  @Nested
  @DisplayName("replicaIndex() / sentinelIndex()")
  class IndexTests {

    @Test
    @DisplayName("REPLICA_0.replicaIndex() should return 0")
    void replicaIndexZero() {
      assertThat(ContainerRole.REPLICA_0.replicaIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("REPLICA_8.replicaIndex() should return 8")
    void replicaIndexEight() {
      assertThat(ContainerRole.REPLICA_8.replicaIndex()).isEqualTo(8);
    }

    @Test
    @DisplayName("MASTER.replicaIndex() should throw ISE")
    void masterReplicaIndexThrows() {
      assertThatThrownBy(() -> ContainerRole.MASTER.replicaIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a replica");
    }

    @Test
    @DisplayName("SENTINEL_0.sentinelIndex() should return 0")
    void sentinelIndexZero() {
      assertThat(ContainerRole.SENTINEL_0.sentinelIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("MASTER.sentinelIndex() should throw ISE")
    void masterSentinelIndexThrows() {
      assertThatThrownBy(() -> ContainerRole.MASTER.sentinelIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a sentinel");
    }
  }

  @Nested
  @DisplayName("values()")
  class ValuesTests {

    @Test
    @DisplayName("values() should contain MASTER, REPLICA_0, SENTINEL_0, UNKNOWN")
    void valuesShouldContainAllExpected() {
      final ContainerRole[] values = ContainerRole.values();

      assertThat(values)
          .contains(
              ContainerRole.MASTER,
              ContainerRole.REPLICA_0,
              ContainerRole.REPLICA_1,
              ContainerRole.REPLICA_2,
              ContainerRole.SENTINEL_0,
              ContainerRole.SENTINEL_1,
              ContainerRole.SENTINEL_2,
              ContainerRole.UNKNOWN);
    }

    @Test
    @DisplayName("toString() should return non-blank for all values")
    void toStringShouldBeNonBlankForAll() {
      for (final ContainerRole role : ContainerRole.values()) {
        assertThat(role.toString()).as("ContainerRole.%s.toString()", role.name()).isNotBlank();
      }
    }
  }

  @Nested
  @DisplayName("replicaByIndex() / sentinelByIndex()")
  class FactoryMethodTests {

    @Test
    @DisplayName("replicaByIndex(0) should return REPLICA_0")
    void replicaByIndexZero() {
      assertThat(ContainerRole.replicaByIndex(0)).isEqualTo(ContainerRole.REPLICA_0);
    }

    @Test
    @DisplayName("sentinelByIndex(1) should return SENTINEL_1")
    void sentinelByIndexOne() {
      assertThat(ContainerRole.sentinelByIndex(1)).isEqualTo(ContainerRole.SENTINEL_1);
    }

    @Test
    @DisplayName("replicaByIndex(-1) should throw IAE")
    void replicaByIndexNegativeThrows() {
      assertThatThrownBy(() -> ContainerRole.replicaByIndex(-1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sentinelByIndex(9) should throw IAE")
    void sentinelByIndexOutOfRangeThrows() {
      assertThatThrownBy(() -> ContainerRole.sentinelByIndex(9))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
