/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 (Incident) chaos scenario annotations for gRPC-connected services.
 *
 * <p>Each annotation in this package composes rules across multiple domains — connection, DNS,
 * time, and JVM — to simulate named, compound production incidents that real gRPC deployments
 * have experienced: deadline propagation failures, connection drain during rolling deploys,
 * and load-balancing name-resolution failures.
 *
 * <p>Annotate your test class or method and ensure the container under test has
 * {@code @SyscallLevelChaos} with the appropriate {@link com.macstab.chaos.core.syscall.LibchaosLib}
 * values for the domains used by the scenario.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.grpc.testpack.l3;
