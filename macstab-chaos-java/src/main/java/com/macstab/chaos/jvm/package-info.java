/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Container-side wrapper for the chaos JVM agent.
 *
 * <h2>Two integration models</h2>
 *
 * <p>The {@code chaos-testing-java-agent} project supports two ways of attaching the agent:
 *
 * <ol>
 *   <li><strong>In-process</strong> (test JVM = target JVM) — typical for unit / slice /
 *       integration tests of an application. Use the agent project's own {@code
 *       ChaosAgentExtension} (JUnit 5) or {@code @ChaosTest} (Spring Boot 3 / 4) — they self-attach
 *       via the Attach API and expose a {@code ChaosControlPlane} bean. This module is <em>not</em>
 *       involved.
 *   <li><strong>Container-side</strong> (test JVM ≠ target JVM) — when the test drives a separate
 *       Java container via testcontainers (Spring Boot app under test, Quarkus app, custom
 *       service). The agent must be delivered into that container with a {@code -javaagent} flag,
 *       and the chaos plan must be pushed across the container boundary as JSON. This is what
 *       {@link com.macstab.chaos.jvm.CompositeJavaChaos} and {@link
 *       com.macstab.chaos.jvm.JavaAgentTransport} provide.
 * </ol>
 *
 * <h2>Container-side setup flow</h2>
 *
 * <pre>{@code
 * // 1. Construct chaos + container
 * final CompositeJavaChaos chaos = new CompositeJavaChaos();
 * final GenericContainer<?> app =
 *     new GenericContainer<>("my-app:latest").withExposedPorts(8080);
 *
 * // 2. Prepare BEFORE start — copies agent jar, sets JAVA_TOOL_OPTIONS
 * chaos.prepare(app);
 * app.start();
 *
 * // 3. Push a chaos plan (JSON serialised from chaos-agent-api types)
 * chaos.applyPlan(app, planJson);
 *
 * // ... test runs against app, agent injects faults ...
 *
 * // 4. Reset
 * chaos.clearPlan(app);
 * }</pre>
 *
 * <h2>Target JDK requirement</h2>
 *
 * <p>The agent requires <strong>JDK 21+</strong> in the target container — virtual-thread
 * instrumentation and {@code release=21} bytecode mean older runtimes fail at agent load.
 *
 * <h2>What this module deliberately does NOT do</h2>
 *
 * <ul>
 *   <li>Re-export {@code chaos-agent-api} types (use the agent module directly — it is the
 *       canonical owner of {@code ChaosPlan} / {@code ChaosScenario} / {@code ChaosSelector} /
 *       {@code ChaosEffect}).
 *   <li>Bundle the agent jar (~12 MiB). The jar is resolved from Maven Central as a transitive
 *       dependency at test time and discovered by classpath scan.
 *   <li>Provide a JUnit 5 extension. The agent's {@code ChaosAgentExtension} covers the in-process
 *       case; the container-side case is too test-shape-specific to wrap usefully — call {@code
 *       prepare} / {@code applyPlan} from your existing setup hooks.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a href="https://github.com/macstab/chaos-testing-java-agent">chaos-testing-java-agent</a>
 */
package com.macstab.chaos.jvm;
