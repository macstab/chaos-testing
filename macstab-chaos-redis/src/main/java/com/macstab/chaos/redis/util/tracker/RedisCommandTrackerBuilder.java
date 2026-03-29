/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.redis.util.RedisCommandTracker;

/**
 * Builder for {@link RedisCommandTracker} with custom configuration.
 *
 * <p>Extracted from {@code RedisCommandTracker} to keep the tracker class under 200 lines.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * RedisCommandTracker tracker = RedisCommandTracker.builder()
 *     .container(replicaContainer)
 *     .trackCommands(Set.of("GET", "HGETALL"))
 *     .filterReplication(true)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class RedisCommandTrackerBuilder {

  private GenericContainer<?> container;
  private Set<String> commands = Set.of("GET", "SET", "HGET", "HSET", "DEL", "INCR", "DECR");
  private boolean filterReplication = true;

  /** Creates a new builder instance. */
  public RedisCommandTrackerBuilder() {
    // public constructor required for standalone use
  }

  /**
   * Sets the Redis container to monitor.
   *
   * @param container the container (must not be null when {@link #build()} is called)
   * @return this builder
   */
  public RedisCommandTrackerBuilder container(final GenericContainer<?> container) {
    this.container = container;
    return this;
  }

  /**
   * Sets which commands to track (case-insensitive).
   *
   * @param commands set of Redis command names (must not be null)
   * @return this builder
   */
  public RedisCommandTrackerBuilder trackCommands(final Set<String> commands) {
    this.commands = Objects.requireNonNull(commands, "commands");
    return this;
  }

  /**
   * Enables or disables replication traffic filtering.
   *
   * <p>When {@code true} (default), lines containing {@code :6379]} are excluded (these are
   * replication-internal commands, not client commands).
   *
   * @param filter {@code true} to filter replication traffic
   * @return this builder
   */
  public RedisCommandTrackerBuilder filterReplication(final boolean filter) {
    this.filterReplication = filter;
    return this;
  }

  /**
   * Builds the configured {@link RedisCommandTracker}.
   *
   * @return new tracker instance
   * @throws NullPointerException if container was not set
   */
  public RedisCommandTracker build() {
    Objects.requireNonNull(container, "container not set — call .container(container) first");

    final Set<String> trackedCommands = Set.copyOf(commands);
    final boolean filterRep = filterReplication;

    final Predicate<String> filter =
        line -> {
          final boolean hasCommand =
              trackedCommands.stream()
                  .anyMatch(cmd -> line.contains("\"" + cmd.toUpperCase() + "\""));
          if (!hasCommand) {
            return false;
          }
          return !filterRep || !line.contains(":6379]");
        };

    return new RedisCommandTracker(container, filter);
  }
}
