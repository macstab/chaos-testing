/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JavaAgentTransport (unit)")
class JavaAgentTransportTest {

  @Nested
  @DisplayName("composeJavaToolOptions")
  class ComposeJavaToolOptions {

    private static final String AGENT =
        "-javaagent:/opt/chaos/chaos-agent.jar=configFile=/etc/chaos/plan.json";

    @Test
    @DisplayName("empty existing returns the new flag verbatim")
    void emptyExisting() {
      assertThat(JavaAgentTransport.composeJavaToolOptions("", AGENT)).isEqualTo(AGENT);
    }

    @Test
    @DisplayName("null existing returns the new flag verbatim")
    void nullExisting() {
      assertThat(JavaAgentTransport.composeJavaToolOptions(null, AGENT)).isEqualTo(AGENT);
    }

    @Test
    @DisplayName("blank existing returns the new flag verbatim")
    void blankExisting() {
      assertThat(JavaAgentTransport.composeJavaToolOptions("   ", AGENT)).isEqualTo(AGENT);
    }

    @Test
    @DisplayName("non-empty existing → appends with space separator")
    void appendsWithSpace() {
      assertThat(JavaAgentTransport.composeJavaToolOptions("-Xmx512m", AGENT))
          .isEqualTo("-Xmx512m " + AGENT);
    }

    @Test
    @DisplayName("agent flag already present → returns existing unchanged (dedupe)")
    void deduplicates() {
      final String existing = "-Xmx512m " + AGENT;
      assertThat(JavaAgentTransport.composeJavaToolOptions(existing, AGENT)).isEqualTo(existing);
    }

    @Test
    @DisplayName("user JVM flags are preserved")
    void preservesUserFlags() {
      final String userFlags = "-Xmx1g -Dfoo=bar -XX:+UseG1GC";
      assertThat(JavaAgentTransport.composeJavaToolOptions(userFlags, AGENT))
          .isEqualTo(userFlags + " " + AGENT);
    }
  }

  @Nested
  @DisplayName("constructor")
  class Constructor {

    @Test
    @DisplayName("explicit path: null rejected")
    void nullPath() {
      assertThatThrownBy(() -> new JavaAgentTransport((Path) null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("explicit path: missing file rejected")
    void missingFile() {
      assertThatThrownBy(() -> new JavaAgentTransport(Path.of("/nonexistent/agent.jar")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("explicit path: regular file accepted")
    void regularFileAccepted() throws Exception {
      final Path tmp = Files.createTempFile("fake-chaos-agent-bootstrap-", ".jar");
      try {
        final JavaAgentTransport transport = new JavaAgentTransport(tmp);
        assertThat(transport.getAgentJar()).isEqualTo(tmp);
      } finally {
        Files.deleteIfExists(tmp);
      }
    }
  }

  @Nested
  @DisplayName("classpath discovery")
  class ClasspathDiscovery {

    @Test
    @DisplayName("locates chaos-agent-bootstrap-*.jar on the test runtime classpath")
    void locatesAgentJar() {
      // The build.gradle.kts declares the agent as `api` — so it is on the test classpath.
      final Path located = JavaAgentTransport.locateAgentJarOnClasspath();
      assertThat(located).isNotNull();
      assertThat(located.getFileName().toString())
          .startsWith(JavaAgentTransport.AGENT_JAR_PREFIX)
          .endsWith(".jar");
      assertThat(Files.isRegularFile(located)).isTrue();
    }
  }
}
