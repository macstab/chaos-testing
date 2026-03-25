/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.http;

import static org.assertj.core.api.Assertions.*;

import com.macstab.chaos.core.platform.Platform;
import com.macstab.chaos.core.platform.PlatformDetector;
import com.macstab.chaos.core.shell.Shell;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.Container.ExecResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link CurlCommandBuilder}.
 *
 * <p>Tests real curl execution in a container with HTTP server.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("CurlCommandBuilder Integration")
class CurlCommandBuilderIntegrationTest {

  @Container
  static GenericContainer<?> ubuntu =
      new GenericContainer<>(DockerImageName.parse("ubuntu:22.04"))
          .withCommand("sleep", "infinity");

  private static Platform platform;
  private static Shell shell;
  private HttpCommandBuilder builder;

  @BeforeAll
  static void setUpContainer() throws Exception {
    // Detect platform and shell
    platform = PlatformDetector.detect(ubuntu);
    shell = platform.getDefaultShell();

    // Install curl and Python (for HTTP server)
    shell.exec(ubuntu, "apt-get update -qq");
    shell.exec(ubuntu, "apt-get install -y curl python3 >/dev/null 2>&1");
  }

  @BeforeEach
  void setUp() {
    builder = new CurlCommandBuilder();
  }

  @Test
  @DisplayName("should execute GET request successfully")
  void shouldExecuteGetRequest() throws Exception {
    // GIVEN: HTTP server running
    startHttpServer(8000);

    // WHEN: Execute GET
    final String cmd = builder.buildGetRequest("http://localhost:8000");
    final ExecResult result = shell.exec(ubuntu, cmd);

    // THEN: Success with HTML response
    assertThat(result.getExitCode()).isZero();
    assertThat(result.getStdout()).contains("Directory listing for /");

    // CLEANUP
    stopHttpServer();
  }

  @Test
  @DisplayName("should execute POST request with JSON successfully")
  void shouldExecutePostRequest() throws Exception {
    // GIVEN: HTTP server running
    startHttpServer(8001);

    // WHEN: Execute POST
    final String json = "{\"test\":\"value\"}";
    final String cmd = builder.buildPostJsonRequest("http://localhost:8001", json);
    final ExecResult result = shell.exec(ubuntu, cmd);

    // THEN: Success (Python server accepts POST)
    assertThat(result.getExitCode()).isZero();

    // CLEANUP
    stopHttpServer();
  }

  @Test
  @DisplayName("should return zero exit code on HTTP 200")
  void shouldReturnZeroOn200() throws Exception {
    // GIVEN: HTTP server running
    startHttpServer(8002);

    // WHEN: Execute GET
    final String cmd = builder.buildGetRequest("http://localhost:8002");
    final ExecResult result = shell.exec(ubuntu, cmd);

    // THEN: Exit code 0
    assertThat(result.getExitCode()).isZero();

    // CLEANUP
    stopHttpServer();
  }

  @Test
  @DisplayName("should return non-zero exit code on HTTP 404 with fail-on-error")
  void shouldReturnNonZeroOn404() throws Exception {
    // GIVEN: HTTP server running
    startHttpServer(8003);

    // WHEN: Request non-existent resource with fail-on-error
    final String cmd = builder.buildGetRequestFailOnError("http://localhost:8003/nonexistent");
    final ExecResult result = shell.exec(ubuntu, cmd);

    // THEN: Non-zero exit code (curl returns 22 for HTTP errors with -f)
    assertThat(result.getExitCode()).isNotZero();

    // CLEANUP
    stopHttpServer();
  }

  @Test
  @DisplayName("should handle connection timeout")
  void shouldHandleConnectionTimeout() throws Exception {
    // GIVEN: Unreachable host with short timeout
    final HttpCommandConfig config =
        HttpCommandConfig.builder().connectionTimeout(java.time.Duration.ofSeconds(1)).build();
    final HttpCommandBuilder builder = new CurlCommandBuilder(config);

    // WHEN: Try to connect to unreachable host
    final String cmd = builder.buildGetRequest("http://192.0.2.1:9999"); // TEST-NET-1, unreachable
    final ExecResult result = shell.exec(ubuntu, cmd);

    // THEN: Non-zero exit code (timeout or connection refused)
    assertThat(result.getExitCode()).isNotZero();
  }

  // ==================== Test Helpers ====================

  /**
   * Start Python SimpleHTTPServer on specified port.
   *
   * @param port server port
   */
  private void startHttpServer(final int port) throws Exception {
    // Start Python HTTP server in background
    shell.exec(ubuntu, "cd /tmp && nohup python3 -m http.server " + port + " >/dev/null 2>&1 &");

    // Wait for server to start
    Thread.sleep(1000);
  }

  /** Stop all Python HTTP servers. */
  private void stopHttpServer() throws Exception {
    // Kill all Python HTTP servers
    shell.exec(ubuntu, "pkill -f 'python3 -m http.server' || true");

    // Wait for cleanup
    Thread.sleep(500);
  }
}
