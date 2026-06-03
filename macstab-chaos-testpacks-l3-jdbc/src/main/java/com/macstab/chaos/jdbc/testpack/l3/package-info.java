/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 (Incident) chaos scenario annotations for JDBC-connected services.
 *
 * <p>Each annotation in this package composes rules across multiple domains — connection, DNS,
 * filesystem, process, and JVM — to simulate named, compound production incidents that real
 * JDBC/database deployments have experienced: connection pool exhaustion, primary failovers,
 * WAL pressure, network partitions, and disk-full conditions.
 *
 * <p>Annotate your test class or method and ensure the container under test has
 * {@code @SyscallLevelChaos} with the appropriate {@link com.macstab.chaos.core.syscall.LibchaosLib}
 * values for the domains used by the scenario.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.jdbc.testpack.l3;
