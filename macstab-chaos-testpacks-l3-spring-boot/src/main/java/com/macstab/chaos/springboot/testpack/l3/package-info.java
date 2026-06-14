/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 (Incident) chaos scenario annotations for Spring Boot services.
 *
 * <p>Each annotation in this package composes rules across multiple domains — connection, DNS,
 * memory, process, and JVM — to simulate named, compound production incidents that real Spring Boot
 * deployments have experienced: startup failures, graceful shutdown races, memory crises, config
 * server outages, and database outages.
 *
 * <p>Annotate your test class or method and ensure the container under test has
 * {@code @SyscallLevelChaos} with the appropriate {@link
 * com.macstab.chaos.core.syscall.LibchaosLib} values for the domains used by the scenario.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.springboot.testpack.l3;
