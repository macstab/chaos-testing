/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 (Incident) chaos scenario annotations for Redis-connected services.
 *
 * <p>Each annotation in this package composes rules across multiple domains — connection, DNS,
 * time, memory, and JVM — to simulate named, compound production incidents that real Redis
 * deployments have experienced: failover storms, cache avalanches, clock drift TTL corruption,
 * OOM eviction cascades, and slow-log backlogs.
 *
 * <p>Annotate your test class or method and ensure the container under test has
 * {@code @SyscallLevelChaos} with the appropriate {@link com.macstab.chaos.core.syscall.LibchaosLib}
 * values for the domains used by the scenario.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.redis.testpack.l3;
