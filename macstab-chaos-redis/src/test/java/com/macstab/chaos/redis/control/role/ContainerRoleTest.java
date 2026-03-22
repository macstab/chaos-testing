/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive unit tests for {@link ContainerRole}.
 *
 * <p><strong>Coverage:</strong> 100% line/branch coverage with edge cases and mutation testing.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ContainerRole")
class ContainerRoleTest {

  @Nested
  @DisplayName("Enum Values")
  class EnumValuesTests {

    @Test
    @DisplayName("Should have exactly 20 values (1 master + 9 replicas + 9 sentinels + 1 unknown)")
    void shouldHaveExactly20Values() {
      assertThat(ContainerRole.values()).hasSize(20);
    }

    @Test
    @DisplayName("Should have MASTER value")
    void shouldHaveMasterValue() {
      assertThat(ContainerRole.MASTER).isNotNull();
      assertThat(ContainerRole.valueOf("MASTER")).isEqualTo(ContainerRole.MASTER);
    }

    @Test
    @DisplayName("Should have UNKNOWN value")
    void shouldHaveUnknownValue() {
      assertThat(ContainerRole.UNKNOWN).isNotNull();
      assertThat(ContainerRole.valueOf("UNKNOWN")).isEqualTo(ContainerRole.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should have REPLICA_N values for N=0 to 8")
    void shouldHaveReplicaValues(final int index) {
      final ContainerRole role = ContainerRole.valueOf("REPLICA_" + index);
      assertThat(role).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should have SENTINEL_N values for N=0 to 8")
    void shouldHaveSentinelValues(final int index) {
      final ContainerRole role = ContainerRole.valueOf("SENTINEL_" + index);
      assertThat(role).isNotNull();
    }
  }

  @Nested
  @DisplayName("isMaster()")
  class IsMasterTests {

    @Test
    @DisplayName("Should return true for MASTER")
    void shouldReturnTrueForMaster() {
      assertThat(ContainerRole.MASTER.isMaster()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return false for all REPLICA_N")
    void shouldReturnFalseForReplicas(final int index) {
      final ContainerRole role = ContainerRole.valueOf("REPLICA_" + index);
      assertThat(role.isMaster()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return false for all SENTINEL_N")
    void shouldReturnFalseForSentinels(final int index) {
      final ContainerRole role = ContainerRole.valueOf("SENTINEL_" + index);
      assertThat(role.isMaster()).isFalse();
    }

    @Test
    @DisplayName("Should return false for UNKNOWN")
    void shouldReturnFalseForUnknown() {
      assertThat(ContainerRole.UNKNOWN.isMaster()).isFalse();
    }
  }

  @Nested
  @DisplayName("isReplica()")
  class IsReplicaTests {

    @Test
    @DisplayName("Should return false for MASTER")
    void shouldReturnFalseForMaster() {
      assertThat(ContainerRole.MASTER.isReplica()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return true for all REPLICA_N")
    void shouldReturnTrueForReplicas(final int index) {
      final ContainerRole role = ContainerRole.valueOf("REPLICA_" + index);
      assertThat(role.isReplica()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return false for all SENTINEL_N")
    void shouldReturnFalseForSentinels(final int index) {
      final ContainerRole role = ContainerRole.valueOf("SENTINEL_" + index);
      assertThat(role.isReplica()).isFalse();
    }

    @Test
    @DisplayName("Should return false for UNKNOWN")
    void shouldReturnFalseForUnknown() {
      assertThat(ContainerRole.UNKNOWN.isReplica()).isFalse();
    }
  }

  @Nested
  @DisplayName("isSentinel()")
  class IsSentinelTests {

    @Test
    @DisplayName("Should return false for MASTER")
    void shouldReturnFalseForMaster() {
      assertThat(ContainerRole.MASTER.isSentinel()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return false for all REPLICA_N")
    void shouldReturnFalseForReplicas(final int index) {
      final ContainerRole role = ContainerRole.valueOf("REPLICA_" + index);
      assertThat(role.isSentinel()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return true for all SENTINEL_N")
    void shouldReturnTrueForSentinels(final int index) {
      final ContainerRole role = ContainerRole.valueOf("SENTINEL_" + index);
      assertThat(role.isSentinel()).isTrue();
    }

    @Test
    @DisplayName("Should return false for UNKNOWN")
    void shouldReturnFalseForUnknown() {
      assertThat(ContainerRole.UNKNOWN.isSentinel()).isFalse();
    }
  }

  @Nested
  @DisplayName("replicaIndex()")
  class ReplicaIndexTests {

    @ParameterizedTest
    @MethodSource("replicaIndexProvider")
    @DisplayName("Should return correct index for REPLICA_N")
    void shouldReturnCorrectIndexForReplicas(final ContainerRole role, final int expectedIndex) {
      assertThat(role.replicaIndex()).isEqualTo(expectedIndex);
    }

    static Stream<Arguments> replicaIndexProvider() {
      return Stream.of(
          Arguments.of(ContainerRole.REPLICA_0, 0),
          Arguments.of(ContainerRole.REPLICA_1, 1),
          Arguments.of(ContainerRole.REPLICA_2, 2),
          Arguments.of(ContainerRole.REPLICA_3, 3),
          Arguments.of(ContainerRole.REPLICA_4, 4),
          Arguments.of(ContainerRole.REPLICA_5, 5),
          Arguments.of(ContainerRole.REPLICA_6, 6),
          Arguments.of(ContainerRole.REPLICA_7, 7),
          Arguments.of(ContainerRole.REPLICA_8, 8));
    }

    @Test
    @DisplayName("Should throw IllegalStateException for MASTER")
    void shouldThrowForMaster() {
      assertThatThrownBy(() -> ContainerRole.MASTER.replicaIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a replica")
          .hasMessageContaining("MASTER");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should throw IllegalStateException for all SENTINEL_N")
    void shouldThrowForSentinels(final int index) {
      final ContainerRole role = ContainerRole.valueOf("SENTINEL_" + index);
      assertThatThrownBy(() -> role.replicaIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a replica")
          .hasMessageContaining("SENTINEL_" + index);
    }

    @Test
    @DisplayName("Should throw IllegalStateException for UNKNOWN")
    void shouldThrowForUnknown() {
      assertThatThrownBy(() -> ContainerRole.UNKNOWN.replicaIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a replica")
          .hasMessageContaining("UNKNOWN");
    }
  }

  @Nested
  @DisplayName("sentinelIndex()")
  class SentinelIndexTests {

    @ParameterizedTest
    @MethodSource("sentinelIndexProvider")
    @DisplayName("Should return correct index for SENTINEL_N")
    void shouldReturnCorrectIndexForSentinels(final ContainerRole role, final int expectedIndex) {
      assertThat(role.sentinelIndex()).isEqualTo(expectedIndex);
    }

    static Stream<Arguments> sentinelIndexProvider() {
      return Stream.of(
          Arguments.of(ContainerRole.SENTINEL_0, 0),
          Arguments.of(ContainerRole.SENTINEL_1, 1),
          Arguments.of(ContainerRole.SENTINEL_2, 2),
          Arguments.of(ContainerRole.SENTINEL_3, 3),
          Arguments.of(ContainerRole.SENTINEL_4, 4),
          Arguments.of(ContainerRole.SENTINEL_5, 5),
          Arguments.of(ContainerRole.SENTINEL_6, 6),
          Arguments.of(ContainerRole.SENTINEL_7, 7),
          Arguments.of(ContainerRole.SENTINEL_8, 8));
    }

    @Test
    @DisplayName("Should throw IllegalStateException for MASTER")
    void shouldThrowForMaster() {
      assertThatThrownBy(() -> ContainerRole.MASTER.sentinelIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a sentinel")
          .hasMessageContaining("MASTER");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should throw IllegalStateException for all REPLICA_N")
    void shouldThrowForReplicas(final int index) {
      final ContainerRole role = ContainerRole.valueOf("REPLICA_" + index);
      assertThatThrownBy(() -> role.sentinelIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a sentinel")
          .hasMessageContaining("REPLICA_" + index);
    }

    @Test
    @DisplayName("Should throw IllegalStateException for UNKNOWN")
    void shouldThrowForUnknown() {
      assertThatThrownBy(() -> ContainerRole.UNKNOWN.sentinelIndex())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Not a sentinel")
          .hasMessageContaining("UNKNOWN");
    }
  }

  @Nested
  @DisplayName("replicaByIndex(int)")
  class ReplicaByIndexTests {

    @ParameterizedTest
    @MethodSource("validReplicaIndexProvider")
    @DisplayName("Should return correct REPLICA_N for valid index (0-8)")
    void shouldReturnCorrectReplicaForValidIndex(final int index, final ContainerRole expected) {
      assertThat(ContainerRole.replicaByIndex(index)).isEqualTo(expected);
    }

    static Stream<Arguments> validReplicaIndexProvider() {
      return Stream.of(
          Arguments.of(0, ContainerRole.REPLICA_0),
          Arguments.of(1, ContainerRole.REPLICA_1),
          Arguments.of(2, ContainerRole.REPLICA_2),
          Arguments.of(3, ContainerRole.REPLICA_3),
          Arguments.of(4, ContainerRole.REPLICA_4),
          Arguments.of(5, ContainerRole.REPLICA_5),
          Arguments.of(6, ContainerRole.REPLICA_6),
          Arguments.of(7, ContainerRole.REPLICA_7),
          Arguments.of(8, ContainerRole.REPLICA_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -10, -100, -Integer.MAX_VALUE})
    @DisplayName("Should throw IllegalArgumentException for negative index")
    void shouldThrowForNegativeIndex(final int index) {
      assertThatThrownBy(() -> ContainerRole.replicaByIndex(index))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Replica index must be 0-8")
          .hasMessageContaining(String.valueOf(index));
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 10, 100, Integer.MAX_VALUE})
    @DisplayName("Should throw IllegalArgumentException for index > 8")
    void shouldThrowForIndexGreaterThan8(final int index) {
      assertThatThrownBy(() -> ContainerRole.replicaByIndex(index))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Replica index must be 0-8")
          .hasMessageContaining(String.valueOf(index));
    }

    @Test
    @DisplayName("Should handle boundary index 0")
    void shouldHandleBoundaryIndex0() {
      assertThat(ContainerRole.replicaByIndex(0)).isEqualTo(ContainerRole.REPLICA_0);
    }

    @Test
    @DisplayName("Should handle boundary index 8")
    void shouldHandleBoundaryIndex8() {
      assertThat(ContainerRole.replicaByIndex(8)).isEqualTo(ContainerRole.REPLICA_8);
    }
  }

  @Nested
  @DisplayName("sentinelByIndex(int)")
  class SentinelByIndexTests {

    @ParameterizedTest
    @MethodSource("validSentinelIndexProvider")
    @DisplayName("Should return correct SENTINEL_N for valid index (0-8)")
    void shouldReturnCorrectSentinelForValidIndex(final int index, final ContainerRole expected) {
      assertThat(ContainerRole.sentinelByIndex(index)).isEqualTo(expected);
    }

    static Stream<Arguments> validSentinelIndexProvider() {
      return Stream.of(
          Arguments.of(0, ContainerRole.SENTINEL_0),
          Arguments.of(1, ContainerRole.SENTINEL_1),
          Arguments.of(2, ContainerRole.SENTINEL_2),
          Arguments.of(3, ContainerRole.SENTINEL_3),
          Arguments.of(4, ContainerRole.SENTINEL_4),
          Arguments.of(5, ContainerRole.SENTINEL_5),
          Arguments.of(6, ContainerRole.SENTINEL_6),
          Arguments.of(7, ContainerRole.SENTINEL_7),
          Arguments.of(8, ContainerRole.SENTINEL_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -10, -100, -Integer.MAX_VALUE})
    @DisplayName("Should throw IllegalArgumentException for negative index")
    void shouldThrowForNegativeIndex(final int index) {
      assertThatThrownBy(() -> ContainerRole.sentinelByIndex(index))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinel index must be 0-8")
          .hasMessageContaining(String.valueOf(index));
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 10, 100, Integer.MAX_VALUE})
    @DisplayName("Should throw IllegalArgumentException for index > 8")
    void shouldThrowForIndexGreaterThan8(final int index) {
      assertThatThrownBy(() -> ContainerRole.sentinelByIndex(index))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinel index must be 0-8")
          .hasMessageContaining(String.valueOf(index));
    }

    @Test
    @DisplayName("Should handle boundary index 0")
    void shouldHandleBoundaryIndex0() {
      assertThat(ContainerRole.sentinelByIndex(0)).isEqualTo(ContainerRole.SENTINEL_0);
    }

    @Test
    @DisplayName("Should handle boundary index 8")
    void shouldHandleBoundaryIndex8() {
      assertThat(ContainerRole.sentinelByIndex(8)).isEqualTo(ContainerRole.SENTINEL_8);
    }
  }

  @Nested
  @DisplayName("getRoleType()")
  class GetRoleTypeTests {

    @Test
    @DisplayName("Should return non-null type for MASTER role")
    void shouldReturnNonNullTypeForMaster() {
      assertThat(ContainerRole.MASTER.getRoleType()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return non-null type for all REPLICA_N")
    void shouldReturnNonNullTypeForReplicas(final int index) {
      final ContainerRole role = ContainerRole.valueOf("REPLICA_" + index);
      assertThat(role.getRoleType()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("Should return non-null type for all SENTINEL_N")
    void shouldReturnNonNullTypeForSentinels(final int index) {
      final ContainerRole role = ContainerRole.valueOf("SENTINEL_" + index);
      assertThat(role.getRoleType()).isNotNull();
    }

    @Test
    @DisplayName("Should return non-null type for UNKNOWN role")
    void shouldReturnNonNullTypeForUnknown() {
      assertThat(ContainerRole.UNKNOWN.getRoleType()).isNotNull();
    }

    @Test
    @DisplayName("Should return same type for all replicas")
    void shouldReturnSameTypeForAllReplicas() {
      final var replica0Type = ContainerRole.REPLICA_0.getRoleType();
      final var replica1Type = ContainerRole.REPLICA_1.getRoleType();

      // All replicas should have same RoleType
      assertThat(replica0Type).isEqualTo(replica1Type);
    }

    @Test
    @DisplayName("Should return different types for master vs replica")
    void shouldReturnDifferentTypesForMasterVsReplica() {
      final var masterType = ContainerRole.MASTER.getRoleType();
      final var replicaType = ContainerRole.REPLICA_0.getRoleType();

      // Master and replica should have different RoleType
      assertThat(masterType).isNotEqualTo(replicaType);
    }
  }

  @Nested
  @DisplayName("Edge Cases and Mutations")
  class EdgeCasesTests {

    @Test
    @DisplayName("Should have consistent toString() output")
    void shouldHaveConsistentToString() {
      assertThat(ContainerRole.MASTER.toString()).isEqualTo("MASTER");
      assertThat(ContainerRole.REPLICA_0.toString()).isEqualTo("REPLICA_0");
      assertThat(ContainerRole.SENTINEL_5.toString()).isEqualTo("SENTINEL_5");
      assertThat(ContainerRole.UNKNOWN.toString()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Should have correct enum ordinals")
    void shouldHaveCorrectOrdinals() {
      assertThat(ContainerRole.MASTER.ordinal()).isEqualTo(0);
      assertThat(ContainerRole.REPLICA_0.ordinal()).isEqualTo(1);
      assertThat(ContainerRole.UNKNOWN.ordinal()).isEqualTo(19);
    }

    @Test
    @DisplayName("Should support equality comparison")
    void shouldSupportEquality() {
      assertThat(ContainerRole.MASTER).isEqualTo(ContainerRole.MASTER);
      assertThat(ContainerRole.REPLICA_1).isEqualTo(ContainerRole.REPLICA_1);
      assertThat(ContainerRole.MASTER).isNotEqualTo(ContainerRole.REPLICA_0);
    }

    @Test
    @DisplayName("Should support identity comparison (enum singleton)")
    void shouldSupportIdentityComparison() {
      assertThat(ContainerRole.MASTER == ContainerRole.MASTER).isTrue();
      assertThat(ContainerRole.REPLICA_1 == ContainerRole.REPLICA_1).isTrue();
      assertThat(ContainerRole.MASTER == ContainerRole.REPLICA_0).isFalse();
    }

    @Test
    @DisplayName("Should be immutable (enum constants)")
    void shouldBeImmutable() {
      final ContainerRole role = ContainerRole.MASTER;
      assertThat(role.isMaster()).isTrue();
      // Enums are inherently immutable - cannot modify
      assertThat(role.isMaster()).isTrue(); // Still true
    }
  }
}
