/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 (Incident) chaos scenario annotations for HTTP/HTTPS service-to-service communication.
 *
 * <p>Each annotation composes rules across connection, DNS, time, and JVM domains to simulate
 * named, compound production incidents observed in HTTP-based microservice architectures: cascading
 * timeouts, retry amplification storms, partial outages, and SSL handshake failures under load.
 *
 * <p>Annotate your test class or method and ensure the container under test has
 * {@code @SyscallLevelChaos} with the appropriate {@link
 * com.macstab.chaos.core.syscall.LibchaosLib} values for the domains used by the scenario.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.http.testpack.l3;
