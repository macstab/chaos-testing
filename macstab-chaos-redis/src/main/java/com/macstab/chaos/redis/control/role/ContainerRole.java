/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.control.role;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the role of a Redis container in a Sentinel cluster topology.
 *
 * <p><strong>Topology Roles:</strong>
 *
 * <ul>
 *   <li>{@link #MASTER} - Single master node (writes)
 *   <li>{@link #REPLICA_0} to {@link #REPLICA_8} - Up to 9 replica nodes (reads)
 *   <li>{@link #SENTINEL_0} to {@link #SENTINEL_8} - Up to 9 Sentinel monitors
 *   <li>{@link #UNKNOWN} - Unable to determine role (stopped/failed container)
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Enums are inherently thread-safe (singleton instances).
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Semantic container access
 * GenericContainer<?> master = cluster.getContainer(ContainerRole.MASTER);
 * GenericContainer<?> replica0 = cluster.getContainer(ContainerRole.REPLICA_0);
 *
 * // Type checking
 * if (role.isReplica()) {
 *     int index = role.replicaIndex(); // 0-8
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public enum ContainerRole {

  /** Master node (single instance, handles writes). */
  MASTER(RoleType.MASTER, -1),

  /** Replica node #0 (reads, async replication from master). */
  REPLICA_0(RoleType.REPLICA, 0),

  /** Replica node #1 (reads, async replication from master). */
  REPLICA_1(RoleType.REPLICA, 1),

  /** Replica node #2 (reads, async replication from master). */
  REPLICA_2(RoleType.REPLICA, 2),

  /** Replica node #3 (reads, async replication from master). */
  REPLICA_3(RoleType.REPLICA, 3),

  /** Replica node #4 (reads, async replication from master). */
  REPLICA_4(RoleType.REPLICA, 4),

  /** Replica node #5 (reads, async replication from master). */
  REPLICA_5(RoleType.REPLICA, 5),

  /** Replica node #6 (reads, async replication from master). */
  REPLICA_6(RoleType.REPLICA, 6),

  /** Replica node #7 (reads, async replication from master). */
  REPLICA_7(RoleType.REPLICA, 7),

  /** Replica node #8 (reads, async replication from master). */
  REPLICA_8(RoleType.REPLICA, 8),

  /** Sentinel monitor #0 (monitors master + replicas). */
  SENTINEL_0(RoleType.SENTINEL, 0),

  /** Sentinel monitor #1 (monitors master + replicas). */
  SENTINEL_1(RoleType.SENTINEL, 1),

  /** Sentinel monitor #2 (monitors master + replicas). */
  SENTINEL_2(RoleType.SENTINEL, 2),

  /** Sentinel monitor #3 (monitors master + replicas). */
  SENTINEL_3(RoleType.SENTINEL, 3),

  /** Sentinel monitor #4 (monitors master + replicas). */
  SENTINEL_4(RoleType.SENTINEL, 4),

  /** Sentinel monitor #5 (monitors master + replicas). */
  SENTINEL_5(RoleType.SENTINEL, 5),

  /** Sentinel monitor #6 (monitors master + replicas). */
  SENTINEL_6(RoleType.SENTINEL, 6),

  /** Sentinel monitor #7 (monitors master + replicas). */
  SENTINEL_7(RoleType.SENTINEL, 7),

  /** Sentinel monitor #8 (monitors master + replicas). */
  SENTINEL_8(RoleType.SENTINEL, 8),

  /**
   * Unknown role (container stopped, failed, or unable to determine).
   *
   * <p>Returned when connection inspection fails or container is in invalid state.
   */
  UNKNOWN(RoleType.UNKNOWN, -1);

  private final RoleType type;
  private final int index;

  ContainerRole(final RoleType type, final int index) {
    this.type = Objects.requireNonNull(type, "type");
    this.index = index;
  }

  /**
   * Checks if this role is the master.
   *
   * @return {@code true} if {@link #MASTER}, {@code false} otherwise
   */
  public boolean isMaster() {
    return type == RoleType.MASTER;
  }

  /**
   * Checks if this role is a replica.
   *
   * @return {@code true} if any {@code REPLICA_*}, {@code false} otherwise
   */
  public boolean isReplica() {
    return type == RoleType.REPLICA;
  }

  /**
   * Checks if this role is a Sentinel monitor.
   *
   * @return {@code true} if any {@code SENTINEL_*}, {@code false} otherwise
   */
  public boolean isSentinel() {
    return type == RoleType.SENTINEL;
  }

  /**
   * Gets the replica index (0-8).
   *
   * <p><strong>Example:</strong> {@link #REPLICA_0} → 0, {@link #REPLICA_8} → 8
   *
   * @return replica index (0-8)
   * @throws IllegalStateException if not a replica role
   */
  public int replicaIndex() {
    if (!isReplica()) {
      throw new IllegalStateException("Not a replica: " + this);
    }
    return index;
  }

  /**
   * Gets the Sentinel index (0-8).
   *
   * <p><strong>Example:</strong> {@link #SENTINEL_0} → 0, {@link #SENTINEL_8} → 8
   *
   * @return Sentinel index (0-8)
   * @throws IllegalStateException if not a Sentinel role
   */
  public int sentinelIndex() {
    if (!isSentinel()) {
      throw new IllegalStateException("Not a sentinel: " + this);
    }
    return index;
  }

  /**
   * Gets the role type (master, replica, sentinel, or unknown).
   *
   * @return role type (never null)
   */
  public RoleType getRoleType() {
    return type;
  }

  /**
   * Finds a replica role by index.
   *
   * @param index replica index (0-8)
   * @return corresponding {@code REPLICA_*} role
   * @throws IllegalArgumentException if index out of range [0, 8]
   */
  public static ContainerRole replicaByIndex(final int index) {
    if (index < 0 || index > 8) {
      throw new IllegalArgumentException("Replica index must be 0-8, got: " + index);
    }
    return Arrays.stream(values())
        .filter(ContainerRole::isReplica)
        .filter(r -> r.index == index)
        .findFirst()
        .orElseThrow(); // Should never happen (all indices 0-8 exist)
  }

  /**
   * Finds a Sentinel role by index.
   *
   * @param index Sentinel index (0-8)
   * @return corresponding {@code SENTINEL_*} role
   * @throws IllegalArgumentException if index out of range [0, 8]
   */
  public static ContainerRole sentinelByIndex(final int index) {
    if (index < 0 || index > 8) {
      throw new IllegalArgumentException("Sentinel index must be 0-8, got: " + index);
    }
    return Arrays.stream(values())
        .filter(ContainerRole::isSentinel)
        .filter(s -> s.index == index)
        .findFirst()
        .orElseThrow(); // Should never happen (all indices 0-8 exist)
  }

  /**
   * Role type categorization (master, replica, sentinel, unknown).
   *
   * <p>Internal enum for type checking without string comparison.
   */
  private enum RoleType {
    MASTER,
    REPLICA,
    SENTINEL,
    UNKNOWN
  }
}
