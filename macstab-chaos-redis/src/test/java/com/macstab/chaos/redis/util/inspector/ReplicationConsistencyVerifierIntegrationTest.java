/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.command.RedisCommandBuilder;
import com.macstab.chaos.redis.factory.RawSentinelCluster;
import com.macstab.chaos.redis.factory.SentinelContainerFactory;
import com.macstab.chaos.redis.util.inspector.ReplicationConsistencyVerifier;
import com.macstab.chaos.redis.util.inspector.model.ConsistencyResult;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Integration tests for {@link ReplicationConsistencyVerifier}.
 *
 * <p>Note: This test uses a Sentinel cluster to verify replication consistency between master and
 * replica.
 */
@DisplayName("ReplicationConsistencyVerifier — Integration")
class ReplicationConsistencyVerifierIntegrationTest {

  private static RawSentinelCluster cluster;
  private static RedisClient masterClient;
  private static StatefulRedisConnection<String, String> masterConnection;
  private static RedisCommands<String, String> masterCommands;
  private static RedisClient replicaClient;
  private static StatefulRedisConnection<String, String> replicaConnection;
  private static RedisCommands<String, String> replicaCommands;

  @BeforeAll
  static void setUp() {
    cluster = SentinelContainerFactory.createSentinelCluster(1, 3, false);
    

    masterClient =
        RedisClient.create(
            RedisURI.builder()
                .withHost(cluster.master().getHost())
                .withPort(cluster.master().getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT))
                .build());
    masterConnection = masterClient.connect();
    masterCommands = masterConnection.sync();

    replicaClient =
        RedisClient.create(
            RedisURI.builder()
                .withHost(cluster.replicas().get(0).getHost())
                .withPort(cluster.replicas().get(0).getMappedPort(RedisCommandBuilder.DEFAULT_REDIS_PORT))
                .build());
    replicaConnection = replicaClient.connect();
    replicaCommands = replicaConnection.sync();
  }

  @AfterAll
  static void tearDown() {
    if (masterConnection != null) {
      masterConnection.close();
    }
    if (masterClient != null) {
      masterClient.shutdown();
    }
    if (replicaConnection != null) {
      replicaConnection.close();
    }
    if (replicaClient != null) {
      replicaClient.shutdown();
    }
    if (cluster != null) {
      cluster.stop();
    }
  }

  @Nested
  @DisplayName("forContainers()")
  class ForContainers {

    @Test
    @DisplayName("Should create verifier without error")
    void shouldCreateVerifier() {
      // ARRANGE / ACT
      try (final ReplicationConsistencyVerifier verifier =
          ReplicationConsistencyVerifier.forContainers(
              cluster.master(), cluster.replicas().get(0))) {
        // ASSERT
        assertThat(verifier).isNotNull();
      }
    }

    @Test
    @DisplayName("Should close without throwing")
    void shouldCloseWithoutError() {
      // ARRANGE
      final ReplicationConsistencyVerifier verifier =
          ReplicationConsistencyVerifier.forContainers(
              cluster.master(), cluster.replicas().get(0));

      // ACT / ASSERT
      verifier.close();
    }
  }

  @Nested
  @DisplayName("verify small key set")
  class VerifySmallKeySet {

    @Test
    @DisplayName("Should verify small key set with eventual consistency")
    void shouldVerifySmallKeySet() {
      // ARRANGE
      try (final ReplicationConsistencyVerifier verifier =
          ReplicationConsistencyVerifier.forCommands(masterCommands, replicaCommands)) {
        // ACT
        final ConsistencyResult result =
            verifier.verify(10, Duration.ofSeconds(10));

        // ASSERT
        assertThat(result.consistencyRatio()).isGreaterThanOrEqualTo(0.8);
      }
    }
  }

  @Nested
  @DisplayName("verify with assertConsistencyAtLeast")
  class VerifyWithAssert {

    @Test
    @DisplayName("Should pass assertConsistencyAtLeast with threshold")
    void shouldPassWithThreshold() {
      // ARRANGE
      try (final ReplicationConsistencyVerifier verifier =
          ReplicationConsistencyVerifier.forCommands(masterCommands, replicaCommands)) {
        // ACT
        final ConsistencyResult result =
            verifier.verify(20, Duration.ofSeconds(10));

        // ASSERT
        result.assertConsistencyAtLeast(0.8);
      }
    }
  }

  @Nested
  @DisplayName("forCommands() variant")
  class ForCommandsVariant {

    @Test
    @DisplayName("Should work with existing Lettuce connections")
    void shouldWorkWithExistingConnections() {
      // ARRANGE
      try (final ReplicationConsistencyVerifier verifier =
          ReplicationConsistencyVerifier.forCommands(masterCommands, replicaCommands)) {
        // ACT
        final ConsistencyResult result =
            verifier.verify(5, Duration.ofSeconds(10));

        // ASSERT
        assertThat(result.totalKeys()).isEqualTo(5);
      }
    }
  }
}
