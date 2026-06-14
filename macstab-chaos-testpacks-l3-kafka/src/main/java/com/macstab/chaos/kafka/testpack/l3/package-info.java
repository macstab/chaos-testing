/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 (Incident) chaos scenario annotations for Kafka-connected services.
 *
 * <p>Each annotation in this package composes rules across multiple domains — connection, DNS,
 * time, filesystem, and JVM — to simulate named, compound production incidents that real Kafka
 * deployments have experienced: broker failures, consumer rebalances, network degradation, clock
 * drift, and storage pressure.
 *
 * <p>Annotate your test class or method and ensure the container under test has
 * {@code @SyscallLevelChaos} with the appropriate {@link
 * com.macstab.chaos.core.syscall.LibchaosLib} values for the domains used by the scenario.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.kafka.testpack.l3;
