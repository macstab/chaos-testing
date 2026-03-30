/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Represents one entry from Redis SLOWLOG GET.
 *
 * <p>Each entry captures metadata about a slow command execution, including timing, arguments, and
 * client information.
 *
 * @param id unique slowlog entry ID
 * @param timestampMicros unix timestamp in microseconds when command was executed
 * @param duration command execution duration
 * @param command Redis command name (e.g., "GET", "SET")
 * @param args command arguments
 * @param clientAddr client address (e.g., "127.0.0.1:12345")
 * @param clientName client name (may be empty)
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public record SlowLogEntry(
    long id,
    long timestampMicros,
    Duration duration,
    String command,
    List<String> args,
    String clientAddr,
    String clientName) {

  /**
   * Canonical constructor — defensively copies args to ensure immutability.
   */
  public SlowLogEntry {
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(clientAddr, "clientAddr");
    Objects.requireNonNull(clientName, "clientName");
    args = List.copyOf(Objects.requireNonNull(args, "args"));
  }
}
