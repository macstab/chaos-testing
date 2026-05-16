/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * JVM-agent transport: copies {@code chaos-agent-bootstrap.jar} into a {@link GenericContainer},
 * writes the initial chaos plan, and appends {@code -javaagent:...} to {@code JAVA_TOOL_OPTIONS}
 * pre-start. The libchaos analogue is {@code LibchaosTransport} — same container-prep contract,
 * different payload.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>The chaos JVM agent ships as a 12 MiB shaded jar under Maven coordinate {@code
 * com.macstab.chaos.jvm:chaos-agent-bootstrap}. To inject chaos into a Java process running inside
 * a container, the JVM must be launched with {@code -javaagent:/path/to/chaos-agent.jar=...}. This
 * class wires that up cleanly without requiring the user to mutate Dockerfiles or hand-craft entry
 * scripts.
 *
 * <h2>Agent jar discovery</h2>
 *
 * <p>The default no-arg constructor scans {@link System#getProperty(String) java.class.path} for a
 * file named {@code chaos-agent-bootstrap-*.jar} — Gradle/Maven will already have resolved it as a
 * normal dependency, so it sits in the local repository cache. No JAR shipping inside this module's
 * artifact (which would otherwise add ~12 MiB).
 *
 * <p>If the test runtime is a shaded fat JAR (e.g. some Spring Boot test setups), the classpath
 * scan fails — pass an explicit path via {@link #JavaAgentTransport(Path)} instead.
 *
 * <h2>Setup flow</h2>
 *
 * <ol>
 *   <li>Construct: {@code new JavaAgentTransport()}
 *   <li>Pre-start: {@link #prepare(GenericContainer)} — copies jar, sets {@code JAVA_TOOL_OPTIONS}
 *   <li>Post-start: {@link #applyPlan(GenericContainer, String)} writes a new {@code plan.json};
 *       the agent's {@code StartupConfigPoller} hot-reloads it
 * </ol>
 *
 * <p>The container's JDK must be 21 or later — the agent requires virtual-thread instrumentation
 * support that landed in JDK 21.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a href="https://github.com/macstab/chaos-testing-java-agent">chaos-testing-java-agent</a>
 */
@Slf4j
public final class JavaAgentTransport {

  /** Filename prefix of the agent jar Gradle / Maven resolves under the standard coordinate. */
  static final String AGENT_JAR_PREFIX = "chaos-agent-bootstrap-";

  /** Where the agent jar is copied inside the container. */
  static final String CONTAINER_AGENT_PATH = "/opt/chaos/chaos-agent.jar";

  /** Where the chaos plan is written; the agent polls this path. */
  static final String CONTAINER_PLAN_PATH = "/etc/chaos/plan.json";

  /** Label set on the container after {@link #prepare} has run. Idempotency guard. */
  static final String LABEL_KEY = "macstab.chaos.jvm.active";

  /** Empty plan JSON: parses cleanly, applies no scenarios. */
  static final String EMPTY_PLAN_JSON = "{\"scenarios\":[]}";

  @Getter private final Path agentJar;

  /**
   * Creates a transport that auto-discovers {@code chaos-agent-bootstrap.jar} on the runtime
   * classpath.
   *
   * @throws ChaosOperationFailedException if the agent jar cannot be located on the classpath
   */
  public JavaAgentTransport() {
    this(locateAgentJarOnClasspath());
  }

  /**
   * Creates a transport with an explicit agent-jar path — for shaded test JARs / custom layouts
   * where classpath scanning is unreliable.
   *
   * @param agentJar absolute path to {@code chaos-agent-bootstrap-<version>.jar}
   * @throws NullPointerException if {@code agentJar} is null
   * @throws IllegalArgumentException if {@code agentJar} does not exist
   */
  public JavaAgentTransport(final Path agentJar) {
    this.agentJar = Objects.requireNonNull(agentJar, "agentJar must not be null");
    if (!Files.isRegularFile(agentJar)) {
      throw new IllegalArgumentException("agentJar does not exist or is not a file: " + agentJar);
    }
  }

  // ==================== Public API: lifecycle ====================

  /**
   * Copies the agent jar into the container, writes an empty plan, and appends {@code -javaagent}
   * to {@code JAVA_TOOL_OPTIONS}. Idempotent (label-guarded). Must be called <strong>before</strong>
   * {@code container.start()}.
   *
   * <p>Pre-existing {@code JAVA_TOOL_OPTIONS} entries placed by user code are preserved — the
   * {@code -javaagent} flag is appended.
   *
   * @param container container to prepare (must not yet be started)
   * @throws NullPointerException if {@code container} is null
   * @throws ChaosOperationFailedException if the agent jar cannot be loaded
   */
  public void prepare(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (container.getLabels().containsKey(LABEL_KEY)) {
      log.debug("chaos-jvm-agent already prepared for this container");
      return;
    }

    final byte[] agentBytes;
    try {
      agentBytes = Files.readAllBytes(agentJar);
    } catch (final Exception e) {
      throw new ChaosOperationFailedException(
          "Failed to read agent jar at " + agentJar, e);
    }

    container.withCopyToContainer(Transferable.of(agentBytes, 0644), CONTAINER_AGENT_PATH);
    container.withCopyToContainer(
        Transferable.of(EMPTY_PLAN_JSON.getBytes(StandardCharsets.UTF_8), 0644),
        CONTAINER_PLAN_PATH);

    final String agentArg =
        "-javaagent:" + CONTAINER_AGENT_PATH + "=configFile=" + CONTAINER_PLAN_PATH;
    final String existing = container.getEnvMap().getOrDefault("JAVA_TOOL_OPTIONS", "");
    container.withEnv("JAVA_TOOL_OPTIONS", composeJavaToolOptions(existing, agentArg));
    container.withLabel(LABEL_KEY, "true");

    log.info(
        "Prepared chaos-jvm-agent: jar={} ({} bytes), planPath={}",
        agentJar.getFileName(),
        agentBytes.length,
        CONTAINER_PLAN_PATH);
  }

  /**
   * Returns {@code true} if {@link #prepare} has been called on this container.
   *
   * @param container target container
   * @return {@code true} when the container carries this transport's label
   * @throws NullPointerException if {@code container} is null
   */
  public boolean isActive(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    return container.getLabels().containsKey(LABEL_KEY);
  }

  // ==================== Public API: plan management ====================

  /**
   * Replaces the container's chaos plan with the given JSON. The agent's startup-config poller
   * notices the file change and hot-reloads the plan; existing scenarios are deactivated and the
   * new ones take effect.
   *
   * @param container running container (must be prepared)
   * @param planJson valid {@code ChaosPlan} JSON serialised from {@code chaos-agent-api}
   * @throws NullPointerException if any argument is null
   * @throws IllegalStateException if {@link #prepare} was not called
   * @throws ChaosOperationFailedException if the file copy fails
   */
  public void applyPlan(final GenericContainer<?> container, final String planJson) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(planJson, "planJson must not be null");
    validateActive(container);
    container.copyFileToContainer(
        Transferable.of(planJson.getBytes(StandardCharsets.UTF_8), 0644), CONTAINER_PLAN_PATH);
    log.debug("Applied chaos plan ({} bytes) to {}", planJson.length(), CONTAINER_PLAN_PATH);
  }

  /**
   * Resets the container to an empty plan — no active scenarios.
   *
   * @param container running container (must be prepared)
   * @throws NullPointerException if {@code container} is null
   * @throws IllegalStateException if {@link #prepare} was not called
   */
  public void clearPlan(final GenericContainer<?> container) {
    applyPlan(container, EMPTY_PLAN_JSON);
  }

  // ==================== Package-private helpers (testable) ====================

  /**
   * Builds a {@code JAVA_TOOL_OPTIONS} value that appends {@code agentArg} to {@code existing},
   * deduping exact-match entries.
   *
   * @param existing current value (may be {@code null} or empty)
   * @param agentArg flag to append (e.g. {@code "-javaagent:/opt/chaos/chaos-agent.jar=..."})
   * @return combined value, space-separated
   */
  static String composeJavaToolOptions(final String existing, final String agentArg) {
    if (existing == null || existing.isBlank()) {
      return agentArg;
    }
    for (final String entry : existing.split("\\s+")) {
      if (entry.equals(agentArg)) {
        return existing;
      }
    }
    return existing + " " + agentArg;
  }

  /**
   * Locates the agent jar by scanning {@link System#getProperty(String) java.class.path} for a
   * file matching {@link #AGENT_JAR_PREFIX}.
   *
   * @return absolute path to the agent jar
   * @throws ChaosOperationFailedException if no matching entry is found
   */
  static Path locateAgentJarOnClasspath() {
    final String classpath = System.getProperty("java.class.path");
    if (classpath == null || classpath.isBlank()) {
      throw new ChaosOperationFailedException(
          "java.class.path is empty — cannot locate the chaos-agent jar. "
              + "Pass an explicit path via JavaAgentTransport(Path).");
    }
    final Optional<Path> hit =
        java.util.Arrays.stream(classpath.split(File.pathSeparator))
            .map(Paths::get)
            .filter(p -> p.getFileName() != null)
            .filter(p -> p.getFileName().toString().startsWith(AGENT_JAR_PREFIX))
            .filter(Files::isRegularFile)
            .findFirst();
    return hit.orElseThrow(
        () ->
            new ChaosOperationFailedException(
                "chaos-agent-bootstrap-*.jar not found on classpath. "
                    + "Add com.macstab.chaos.jvm:chaos-agent-bootstrap as a dependency, "
                    + "or pass an explicit path via JavaAgentTransport(Path)."));
  }

  private void validateActive(final GenericContainer<?> container) {
    if (!container.getLabels().containsKey(LABEL_KEY)) {
      throw new IllegalStateException(
          "prepare() must be called before plan operations on the chaos JVM agent");
    }
  }
}
