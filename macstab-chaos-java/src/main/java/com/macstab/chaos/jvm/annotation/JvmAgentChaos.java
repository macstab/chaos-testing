/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation declaring that a test class drives chaos via the JVM agent
 * ({@code com.macstab.chaos.jvm:chaos-agent-bootstrap}).
 *
 * <p>Conceptual sibling of {@code @SyscallLevelChaos(LibchaosLib.X)} for libchaos {@code .so}
 * libraries: it documents the intended pre-start preparation contract — a test runner / extension
 * is expected to invoke {@code JavaAgentTransport.prepare(container)} on every container the
 * annotated test class spins up, before {@code container.start()}.
 *
 * <p>Today this annotation is a documentation hook — it is not yet consumed by a JUnit extension
 * in this repo. The chaos-testing-java-agent project ships its own {@code ChaosAgentExtension}
 * and {@code @ChaosTest} that wire test-process-side chaos directly. Use {@code @JvmAgentChaos}
 * for the container-side variant, where the agent runs inside a {@link
 * org.testcontainers.containers.GenericContainer} that the test is exercising.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JvmAgentChaos {}
