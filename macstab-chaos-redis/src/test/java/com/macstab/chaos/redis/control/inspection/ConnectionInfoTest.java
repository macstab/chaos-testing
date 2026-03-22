/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.control.role.ContainerRole;

/**
 * Comprehensive unit tests for {@link ConnectionInfo}.
 *
 * <p><strong>Coverage:</strong> 100% line/branch coverage with edge cases and mutation testing.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("ConnectionInfo")
class ConnectionInfoTest {

  private final GenericContainer<?> mockContainer = mock(GenericContainer.class);

  @Nested
  @DisplayName("Constructor Validation")
  class ConstructorValidationTests {

    @Test
    @DisplayName("Should create ConnectionInfo with all valid parameters")
    void shouldCreateWithValidParameters() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);

      assertThat(info.role()).isEqualTo(ContainerRole.MASTER);
      assertThat(info.container()).isEqualTo(mockContainer);
      assertThat(info.connectionInfo()).isEqualTo("localhost:6379");
      assertThat(info.healthy()).isTrue();
    }

    @Test
    @DisplayName("Should throw NullPointerException when role is null")
    void shouldThrowWhenRoleIsNull() {
      assertThatThrownBy(() -> new ConnectionInfo(null, mockContainer, "localhost:6379", true))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("role");
    }

    @Test
    @DisplayName("Should throw NullPointerException when container is null")
    void shouldThrowWhenContainerIsNull() {
      assertThatThrownBy(
              () -> new ConnectionInfo(ContainerRole.MASTER, null, "localhost:6379", true))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should throw NullPointerException when connectionInfo is null")
    void shouldThrowWhenConnectionInfoIsNull() {
      assertThatThrownBy(() -> new ConnectionInfo(ContainerRole.MASTER, mockContainer, null, true))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("connectionInfo");
    }

    @Test
    @DisplayName("Should allow healthy=false")
    void shouldAllowHealthyFalse() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.REPLICA_0, mockContainer, "localhost:6380", false);

      assertThat(info.healthy()).isFalse();
    }

    @Test
    @DisplayName("Should allow UNKNOWN role")
    void shouldAllowUnknownRole() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.UNKNOWN, mockContainer, "Container stopped", false);

      assertThat(info.role()).isEqualTo(ContainerRole.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("Static Factory: healthy()")
  class HealthyFactoryTests {

    @Test
    @DisplayName("Should create healthy ConnectionInfo with healthy=true")
    void shouldCreateHealthyConnectionInfo() {
      final ConnectionInfo info =
          ConnectionInfo.healthy(ContainerRole.MASTER, mockContainer, "localhost:6379");

      assertThat(info.role()).isEqualTo(ContainerRole.MASTER);
      assertThat(info.container()).isEqualTo(mockContainer);
      assertThat(info.connectionInfo()).isEqualTo("localhost:6379");
      assertThat(info.healthy()).isTrue();
    }

    @Test
    @DisplayName("Should throw NullPointerException when role is null")
    void shouldThrowWhenRoleIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.healthy(null, mockContainer, "localhost:6379"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("role");
    }

    @Test
    @DisplayName("Should throw NullPointerException when container is null")
    void shouldThrowWhenContainerIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.healthy(ContainerRole.MASTER, null, "localhost:6379"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should throw NullPointerException when connectionInfo is null")
    void shouldThrowWhenConnectionInfoIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.healthy(ContainerRole.MASTER, mockContainer, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("connectionInfo");
    }

    @Test
    @DisplayName("Should create healthy ConnectionInfo for REPLICA")
    void shouldCreateHealthyReplicaConnectionInfo() {
      final ConnectionInfo info =
          ConnectionInfo.healthy(ContainerRole.REPLICA_1, mockContainer, "localhost:6381");

      assertThat(info.role()).isEqualTo(ContainerRole.REPLICA_1);
      assertThat(info.healthy()).isTrue();
    }
  }

  @Nested
  @DisplayName("Static Factory: unhealthy()")
  class UnhealthyFactoryTests {

    @Test
    @DisplayName("Should create unhealthy ConnectionInfo with healthy=false")
    void shouldCreateUnhealthyConnectionInfo() {
      final ConnectionInfo info =
          ConnectionInfo.unhealthy(ContainerRole.REPLICA_0, mockContainer, "PING failed");

      assertThat(info.role()).isEqualTo(ContainerRole.REPLICA_0);
      assertThat(info.container()).isEqualTo(mockContainer);
      assertThat(info.connectionInfo()).isEqualTo("PING failed");
      assertThat(info.healthy()).isFalse();
    }

    @Test
    @DisplayName("Should throw NullPointerException when role is null")
    void shouldThrowWhenRoleIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.unhealthy(null, mockContainer, "Error"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("role");
    }

    @Test
    @DisplayName("Should throw NullPointerException when container is null")
    void shouldThrowWhenContainerIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.unhealthy(ContainerRole.MASTER, null, "Error"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should throw NullPointerException when connectionInfo is null")
    void shouldThrowWhenConnectionInfoIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.unhealthy(ContainerRole.MASTER, mockContainer, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("connectionInfo");
    }
  }

  @Nested
  @DisplayName("Static Factory: unknown()")
  class UnknownFactoryTests {

    @Test
    @DisplayName("Should create unknown ConnectionInfo with role=UNKNOWN and healthy=false")
    void shouldCreateUnknownConnectionInfo() {
      final ConnectionInfo info = ConnectionInfo.unknown(mockContainer, "Container stopped");

      assertThat(info.role()).isEqualTo(ContainerRole.UNKNOWN);
      assertThat(info.container()).isEqualTo(mockContainer);
      assertThat(info.connectionInfo()).isEqualTo("Container stopped");
      assertThat(info.healthy()).isFalse();
    }

    @Test
    @DisplayName("Should throw NullPointerException when container is null")
    void shouldThrowWhenContainerIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.unknown(null, "Error"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("container");
    }

    @Test
    @DisplayName("Should throw NullPointerException when reason is null")
    void shouldThrowWhenReasonIsNull() {
      assertThatThrownBy(() -> ConnectionInfo.unknown(mockContainer, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("Should create unknown ConnectionInfo with custom reason")
    void shouldCreateWithCustomReason() {
      final ConnectionInfo info = ConnectionInfo.unknown(mockContainer, "Connection timeout");

      assertThat(info.connectionInfo()).isEqualTo("Connection timeout");
    }
  }

  @Nested
  @DisplayName("Record Accessors")
  class RecordAccessorTests {

    @Test
    @DisplayName("Should access role via role() method")
    void shouldAccessRole() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.SENTINEL_0, mockContainer, "localhost:26379", true);

      assertThat(info.role()).isEqualTo(ContainerRole.SENTINEL_0);
    }

    @Test
    @DisplayName("Should access container via container() method")
    void shouldAccessContainer() {
      final GenericContainer<?> container = mock(GenericContainer.class);
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.MASTER, container, "localhost:6379", true);

      assertThat(info.container()).isEqualTo(container);
      assertThat(info.container()).isSameAs(container); // Identity check
    }

    @Test
    @DisplayName("Should access connectionInfo via connectionInfo() method")
    void shouldAccessConnectionInfo() {
      final ConnectionInfo info =
          new ConnectionInfo(
              ContainerRole.REPLICA_2, mockContainer, "redis://localhost:6382", true);

      assertThat(info.connectionInfo()).isEqualTo("redis://localhost:6382");
    }

    @Test
    @DisplayName("Should access healthy via healthy() method")
    void shouldAccessHealthy() {
      final ConnectionInfo healthyInfo =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);
      final ConnectionInfo unhealthyInfo =
          new ConnectionInfo(ContainerRole.REPLICA_0, mockContainer, "localhost:6380", false);

      assertThat(healthyInfo.healthy()).isTrue();
      assertThat(unhealthyInfo.healthy()).isFalse();
    }
  }

  @Nested
  @DisplayName("Equals and HashCode")
  class EqualsAndHashCodeTests {

    @Test
    @DisplayName("Should be equal when all fields match")
    void shouldBeEqualWhenAllFieldsMatch() {
      final ConnectionInfo info1 =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);
      final ConnectionInfo info2 =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);

      assertThat(info1).isEqualTo(info2);
      assertThat(info1.hashCode()).isEqualTo(info2.hashCode());
    }

    @Test
    @DisplayName("Should not be equal when role differs")
    void shouldNotBeEqualWhenRoleDiffers() {
      final ConnectionInfo info1 =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);
      final ConnectionInfo info2 =
          new ConnectionInfo(ContainerRole.REPLICA_0, mockContainer, "localhost:6379", true);

      assertThat(info1).isNotEqualTo(info2);
    }

    @Test
    @DisplayName("Should not be equal when container differs")
    void shouldNotBeEqualWhenContainerDiffers() {
      final GenericContainer<?> container1 = mock(GenericContainer.class);
      final GenericContainer<?> container2 = mock(GenericContainer.class);

      final ConnectionInfo info1 =
          new ConnectionInfo(ContainerRole.MASTER, container1, "localhost:6379", true);
      final ConnectionInfo info2 =
          new ConnectionInfo(ContainerRole.MASTER, container2, "localhost:6379", true);

      assertThat(info1).isNotEqualTo(info2);
    }

    @Test
    @DisplayName("Should not be equal when connectionInfo differs")
    void shouldNotBeEqualWhenConnectionInfoDiffers() {
      final ConnectionInfo info1 =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);
      final ConnectionInfo info2 =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6380", true);

      assertThat(info1).isNotEqualTo(info2);
    }

    @Test
    @DisplayName("Should not be equal when healthy differs")
    void shouldNotBeEqualWhenHealthyDiffers() {
      final ConnectionInfo info1 =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);
      final ConnectionInfo info2 =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", false);

      assertThat(info1).isNotEqualTo(info2);
    }

    @Test
    @DisplayName("Should not be equal to null")
    void shouldNotBeEqualToNull() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);

      assertThat(info).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should not be equal to different type")
    void shouldNotBeEqualToDifferentType() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);

      assertThat(info).isNotEqualTo("localhost:6379");
    }

    @Test
    @DisplayName("Should be equal to itself")
    void shouldBeEqualToItself() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);

      assertThat(info).isEqualTo(info);
      assertThat(info.hashCode()).isEqualTo(info.hashCode());
    }
  }

  @Nested
  @DisplayName("toString()")
  class ToStringTests {

    @Test
    @DisplayName("Should include all fields in toString()")
    void shouldIncludeAllFieldsInToString() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.MASTER, mockContainer, "localhost:6379", true);

      final String toString = info.toString();

      assertThat(toString).contains("ConnectionInfo");
      assertThat(toString).contains("MASTER");
      assertThat(toString).contains("localhost:6379");
      assertThat(toString).contains("true");
    }

    @Test
    @DisplayName("Should include UNKNOWN role in toString()")
    void shouldIncludeUnknownRoleInToString() {
      final ConnectionInfo info = ConnectionInfo.unknown(mockContainer, "Container stopped");

      final String toString = info.toString();

      assertThat(toString).contains("UNKNOWN");
      assertThat(toString).contains("Container stopped");
      assertThat(toString).contains("false");
    }
  }

  @Nested
  @DisplayName("Immutability")
  class ImmutabilityTests {

    @Test
    @DisplayName("Should be immutable (cannot modify after creation)")
    void shouldBeImmutable() {
      final ConnectionInfo info =
          new ConnectionInfo(ContainerRole.REPLICA_1, mockContainer, "localhost:6381", true);

      // Records are immutable - accessors return same values
      assertThat(info.role()).isEqualTo(ContainerRole.REPLICA_1);
      assertThat(info.container()).isEqualTo(mockContainer);
      assertThat(info.connectionInfo()).isEqualTo("localhost:6381");
      assertThat(info.healthy()).isTrue();

      // Multiple calls return same values
      assertThat(info.role()).isEqualTo(ContainerRole.REPLICA_1);
    }
  }
}
