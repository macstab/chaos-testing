/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm;

import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import lombok.extern.slf4j.Slf4j;

/**
 * Container-side facade for the chaos JVM agent.
 *
 * <p>Conceptually parallel to the {@code Composite<X>Chaos} facades in the libchaos modules
 * (connection / process / time / ...), but with one important distinction: <strong>the JVM agent's
 * primary surface is its own {@code ChaosControlPlane}</strong>, exposed by the {@code
 * chaos-agent-api} module. For tests where the agent runs <em>in the same JVM</em> as the test
 * process (typical for unit / integration tests of the application itself), use that API directly
 * via {@code com.macstab.chaos.jvm.testkit.ChaosAgentExtension} — there is no benefit to adding a
 * wrapper.
 *
 * <p>This facade exists for the <em>other</em> case: when the agent runs <strong>inside a target
 * container</strong> that the outer test is driving (e.g. testcontainers spinning up a Spring Boot
 * app, a Quarkus app, or any JDK 21+ workload). Then plan deployment is a file-copy operation
 * across the container boundary, not an in-process API call. {@link #applyPlan} writes the plan
 * JSON to the container's plan file; the agent's startup-config poller hot-reloads it.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>Construct: {@code new CompositeJavaChaos()}
 *   <li>Pre-start: {@link #prepare(GenericContainer)} — copies agent jar, sets {@code
 *       JAVA_TOOL_OPTIONS}
 *   <li>Post-start: {@link #applyPlan(GenericContainer, String)} pushes a plan; {@link
 *       #clearPlan(GenericContainer)} resets to no scenarios
 * </ol>
 *
 * <p>Use {@code com.macstab.chaos.jvm.api.ChaosPlan} (from {@code chaos-agent-api}) plus a Jackson
 * {@code ObjectMapper} to build plan JSON in a typed way. This module deliberately does not
 * re-export those types — they belong to the agent project.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CompositeJavaChaos {

  private final JavaAgentTransport transport;

  /** Default constructor — auto-discovers the agent jar on the classpath. */
  public CompositeJavaChaos() {
    this(new JavaAgentTransport());
  }

  /**
   * Constructor with an explicit transport (advanced use / testing).
   *
   * @param transport delivery transport
   */
  public CompositeJavaChaos(final JavaAgentTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
  }

  /**
   * Pre-start preparation — must be called before {@code container.start()}.
   *
   * @param container target container
   */
  public void prepare(final GenericContainer<?> container) {
    transport.prepare(container);
  }

  /**
   * Returns {@code true} when {@link #prepare} has been applied to {@code container}.
   *
   * @param container container to probe
   * @return whether the agent is wired into this container
   */
  public boolean isActive(final GenericContainer<?> container) {
    return transport.isActive(container);
  }

  /**
   * Push a chaos plan to the container — the agent hot-reloads on file change.
   *
   * @param container running container (must be prepared)
   * @param planJson serialised {@code ChaosPlan} JSON
   */
  public void applyPlan(final GenericContainer<?> container, final String planJson) {
    transport.applyPlan(container, planJson);
  }

  /**
   * Reset the container to an empty plan.
   *
   * @param container running container (must be prepared)
   */
  public void clearPlan(final GenericContainer<?> container) {
    transport.clearPlan(container);
  }

  /**
   * Underlying transport — exposed for advanced scenarios that need direct access to the agent jar
   * path or the JAVA_TOOL_OPTIONS composition logic.
   *
   * @return transport
   */
  public JavaAgentTransport transport() {
    return transport;
  }
}
