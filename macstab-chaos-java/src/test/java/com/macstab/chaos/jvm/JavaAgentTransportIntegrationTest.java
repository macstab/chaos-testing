/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end proof that {@link JavaAgentTransport} delivers the chaos JVM agent into a real JDK 21
 * container: agent jar copied into the image overlay, plan file written, JAVA_TOOL_OPTIONS
 * augmented. We do not exercise the agent's runtime instrumentation here — the agent project's own
 * integration tests cover that surface. This test verifies the <em>delivery contract</em>.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("JavaAgentTransport — container delivery (openjdk:21-slim)")
class JavaAgentTransportIntegrationTest {

  private GenericContainer<?> container;

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      container.stop();
    }
  }

  @Test
  @DisplayName("prepare() copies agent jar, writes plan, sets JAVA_TOOL_OPTIONS")
  void prepareEndToEnd() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("eclipse-temurin:21-jre"))
            .withCommand("sleep", "infinity");
    final JavaAgentTransport transport = new JavaAgentTransport();

    transport.prepare(container);
    assertThat(transport.isActive(container)).isTrue();

    container.start();

    // Agent jar landed at the contract path.
    assertThat(
            container
                .execInContainer("test", "-f", JavaAgentTransport.CONTAINER_AGENT_PATH)
                .getExitCode())
        .isZero();

    // Plan file exists with valid empty-plan JSON.
    final var planContents =
        container.execInContainer("cat", JavaAgentTransport.CONTAINER_PLAN_PATH).getStdout().trim();
    assertThat(planContents).isEqualTo(JavaAgentTransport.EMPTY_PLAN_JSON);

    // JAVA_TOOL_OPTIONS picked up the -javaagent flag.
    final var envOut =
        container.execInContainer("/bin/sh", "-c", "echo \"$JAVA_TOOL_OPTIONS\"").getStdout();
    assertThat(envOut)
        .contains("-javaagent:" + JavaAgentTransport.CONTAINER_AGENT_PATH)
        .contains("configFile=" + JavaAgentTransport.CONTAINER_PLAN_PATH);
  }

  @Test
  @DisplayName("applyPlan() writes a new plan file the agent's poller will pick up")
  void applyPlan() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("eclipse-temurin:21-jre"))
            .withCommand("sleep", "infinity");
    final JavaAgentTransport transport = new JavaAgentTransport();
    transport.prepare(container);
    container.start();

    final String customPlan = "{\"scenarios\":[{\"id\":\"smoke\"}]}";
    transport.applyPlan(container, customPlan);

    final var contents =
        container.execInContainer("cat", JavaAgentTransport.CONTAINER_PLAN_PATH).getStdout().trim();
    assertThat(contents).isEqualTo(customPlan);
  }

  @Test
  @DisplayName("clearPlan() resets to empty plan")
  void clearPlanResetsToEmpty() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("eclipse-temurin:21-jre"))
            .withCommand("sleep", "infinity");
    final JavaAgentTransport transport = new JavaAgentTransport();
    transport.prepare(container);
    container.start();

    transport.applyPlan(container, "{\"scenarios\":[{\"id\":\"keep-alive\"}]}");
    transport.clearPlan(container);

    final var contents =
        container.execInContainer("cat", JavaAgentTransport.CONTAINER_PLAN_PATH).getStdout().trim();
    assertThat(contents).isEqualTo(JavaAgentTransport.EMPTY_PLAN_JSON);
  }
}
