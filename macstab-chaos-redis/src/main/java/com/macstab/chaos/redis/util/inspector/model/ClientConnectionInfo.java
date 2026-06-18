/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.inspector.model;

/**
 * Represents one line from Redis CLIENT LIST output.
 *
 * <p>Captures metadata about an active client connection, including address, age, idle time, and
 * last command executed.
 *
 * @param id client connection ID
 * @param addr client address (e.g., "127.0.0.1:54321")
 * @param name client name (may be empty)
 * @param ageSeconds connection age in seconds
 * @param idleSeconds idle time in seconds
 * @param lastCmd last command executed (may be empty)
 * @param db current database number
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public record ClientConnectionInfo(
    long id, String addr, String name, long ageSeconds, long idleSeconds, String lastCmd, int db) {}
