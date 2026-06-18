/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.time.Duration;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

/**
 * Measures replication lag between a Redis master and a replica.
 *
 * <p><strong>How it works:</strong>
 *
 * <ol>
 *   <li>Writes a unique key to master with a timestamp value
 *   <li>Polls replica until the key appears with the same value
 *   <li>Returns duration between write and successful read
 * </ol>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * Duration lag = ReplicationLagMeasurer.measureReplicationLag(master, replica);
 * assertThat(lag).isLessThan(Duration.ofMillis(100));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class ReplicationLagMeasurer {

  private ReplicationLagMeasurer() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Measures replication lag with default 5-second timeout.
   *
   * @param master master container (must be running)
   * @param replica replica container (must be running)
   * @return replication lag duration
   * @throws IllegalStateException if replication does not complete within 5 seconds
   */
  public static Duration measureReplicationLag(
      final GenericContainer<?> master, final GenericContainer<?> replica) {
    return measureReplicationLag(master, replica, Duration.ofSeconds(5));
  }

  /**
   * Measures replication lag with custom timeout.
   *
   * @param master master container (must be running) — must not be null
   * @param replica replica container (must be running) — must not be null
   * @param timeout maximum wait time for replication — must not be null
   * @return replication lag duration
   * @throws IllegalStateException if replication does not complete within timeout
   */
  public static Duration measureReplicationLag(
      final GenericContainer<?> master, final GenericContainer<?> replica, final Duration timeout) {
    Objects.requireNonNull(master, "master");
    Objects.requireNonNull(replica, "replica");
    Objects.requireNonNull(timeout, "timeout");

    final String testKey = "replication-lag-test-" + System.nanoTime();
    final String testValue = String.valueOf(System.currentTimeMillis());

    final RedisURI masterUri =
        RedisURI.builder().withHost(master.getHost()).withPort(master.getFirstMappedPort()).build();
    final RedisURI replicaUri =
        RedisURI.builder()
            .withHost(replica.getHost())
            .withPort(replica.getFirstMappedPort())
            .build();

    try (final RedisClient masterClient = RedisClient.create(masterUri);
        final RedisClient replicaClient = RedisClient.create(replicaUri)) {

      final var masterConn = masterClient.connect().sync();
      final var replicaConn = replicaClient.connect().sync();

      final long startNanos = System.nanoTime();
      masterConn.set(testKey, testValue);

      final long timeoutNanos = timeout.toNanos();
      String replicaValue = null;

      while (System.nanoTime() - startNanos < timeoutNanos) {
        replicaValue = replicaConn.get(testKey);
        if (testValue.equals(replicaValue)) {
          final long lagNanos = System.nanoTime() - startNanos;
          masterConn.del(testKey);
          return Duration.ofNanos(lagNanos);
        }
        try {
          Thread.sleep(1);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while measuring replication lag", e);
        }
      }

      throw new IllegalStateException(
          "Replication did not complete within "
              + timeout.toMillis()
              + "ms. Master wrote: "
              + testValue
              + ", Replica has: "
              + replicaValue);
    }
  }
}
