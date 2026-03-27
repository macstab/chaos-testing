/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/**
 * Unit tests for {@link CurlCommandBuilder}.
 *
 * <p>Tests command generation, shell escaping, configuration, and validation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CurlCommandBuilder")
class CurlCommandBuilderTest {

  private HttpCommandBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new CurlCommandBuilder();
  }

  // ==================== Command Generation ====================

  @Nested
  @DisplayName("Command Generation")
  class CommandGeneration {

    @Test
    @DisplayName("should build GET request with default config")
    void shouldBuildGetRequest() {
      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost:8080/api");

      // THEN
      assertThat(cmd)
          .contains("curl")
          .contains("-s") // Silent
          .contains("-L") // Follow redirects
          .contains("'http://localhost:8080/api'")
          .contains("2>&1"); // Redirect stderr
    }

    @Test
    @DisplayName("should build GET request with fail-on-error")
    void shouldBuildGetRequestFailOnError() {
      // WHEN
      final String cmd = builder.buildGetRequestFailOnError("http://localhost/api");

      // THEN
      assertThat(cmd)
          .contains("curl")
          .contains("-s")
          .contains("-f") // Fail on HTTP errors
          .contains("'http://localhost/api'");
    }

    @Test
    @DisplayName("should build POST request with JSON payload")
    void shouldBuildPostJsonRequest() {
      // GIVEN
      final String json = "{\"name\":\"test\",\"value\":123}";

      // WHEN
      final String cmd = builder.buildPostJsonRequest("http://localhost/api", json);

      // THEN
      assertThat(cmd)
          .contains("curl")
          .contains("-X POST")
          .contains("-H 'Content-Type: application/json'")
          .contains("-d '" + json + "'")
          .contains("'http://localhost/api'");
    }

    @Test
    @DisplayName("should build PUT request with JSON payload")
    void shouldBuildPutJsonRequest() {
      // GIVEN
      final String json = "{\"status\":\"updated\"}";

      // WHEN
      final String cmd = builder.buildPutJsonRequest("http://localhost/resource/1", json);

      // THEN
      assertThat(cmd)
          .contains("curl")
          .contains("-X PUT")
          .contains("-H 'Content-Type: application/json'")
          .contains("-d '" + json + "'");
    }

    @Test
    @DisplayName("should build DELETE request")
    void shouldBuildDeleteRequest() {
      // WHEN
      final String cmd = builder.buildDeleteRequest("http://localhost/resource/1");

      // THEN
      assertThat(cmd)
          .contains("curl")
          .contains("-X DELETE")
          .contains("'http://localhost/resource/1'");
    }
  }

  // ==================== Shell Escaping ====================

  @Nested
  @DisplayName("Shell Escaping")
  class ShellEscaping {

    @Test
    @DisplayName("should escape single quotes in JSON payload")
    void shouldEscapeSingleQuotesInJson() {
      // GIVEN
      final String json = "{\"name\":\"O'Reilly\"}";

      // WHEN
      final String cmd = builder.buildPostJsonRequest("http://localhost", json);

      // THEN - Single quote escaped as '\''
      assertThat(cmd).contains("O'\\''Reilly");
    }

    @Test
    @DisplayName("should escape single quotes in URL")
    void shouldEscapeSingleQuotesInUrl() {
      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost/api?name=O'Reilly");

      // THEN
      assertThat(cmd).contains("O'\\''Reilly");
    }

    @Test
    @DisplayName("should protect against dollar sign variable expansion")
    void shouldProtectAgainstDollarSignExpansion() {
      // GIVEN
      final String json = "{\"price\":\"$50\",\"var\":\"${HOME}\"}";

      // WHEN
      final String cmd = builder.buildPostJsonRequest("http://localhost", json);

      // THEN - Wrapped in single quotes = no expansion
      assertThat(cmd).contains("'").contains("$50").contains("${HOME}");
      // Verify single-quote wrapping prevents expansion
      assertThat(cmd).matches(".*-d '.*\\$50.*'.*");
    }

    @Test
    @DisplayName("should handle empty strings")
    void shouldHandleEmptyStrings() {
      // WHEN
      final String cmd = builder.buildPostJsonRequest("http://localhost", "");

      // THEN - Empty payload is valid
      assertThat(cmd).contains("-d ''");
    }

    @Test
    @DisplayName("should handle URLs with query parameters")
    void shouldHandleUrlsWithQueryParameters() {
      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost/api?key=value&foo=bar");

      // THEN - Query params preserved
      assertThat(cmd).contains("'http://localhost/api?key=value&foo=bar'");
    }

    @Test
    @DisplayName("should handle complex JSON with nested quotes")
    void shouldHandleComplexJson() {
      // GIVEN
      final String json = "{\"message\":\"It's a 'test' message\"}";

      // WHEN
      final String cmd = builder.buildPostJsonRequest("http://localhost", json);

      // THEN - All quotes escaped
      assertThat(cmd).contains("It'\\''s a '\\''test'\\'' message");
    }
  }

  // ==================== Configuration ====================

  @Nested
  @DisplayName("Configuration")
  class Configuration {

    @Test
    @DisplayName("should apply connection timeout")
    void shouldApplyConnectionTimeout() {
      // GIVEN
      final HttpCommandConfig config =
          HttpCommandConfig.builder().connectionTimeout(Duration.ofSeconds(5)).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).contains("--max-time 5");
    }

    @Test
    @DisplayName("should apply max retries")
    void shouldApplyMaxRetries() {
      // GIVEN
      final HttpCommandConfig config = HttpCommandConfig.builder().maxRetries(3).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).contains("--retry 3");
    }

    @Test
    @DisplayName("should apply fail-on-error flag")
    void shouldApplyFailOnErrorFlag() {
      // GIVEN
      final HttpCommandConfig config = HttpCommandConfig.builder().failOnHttpError(true).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).contains("-f");
    }

    @Test
    @DisplayName("should disable silent mode when configured")
    void shouldDisableSilentMode() {
      // GIVEN
      final HttpCommandConfig config = HttpCommandConfig.builder().silent(false).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).doesNotContain("-s");
    }

    @Test
    @DisplayName("should disable follow redirects when configured")
    void shouldDisableFollowRedirects() {
      // GIVEN
      final HttpCommandConfig config = HttpCommandConfig.builder().followRedirects(false).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).doesNotContain("-L");
    }

    @Test
    @DisplayName("should apply HTTP proxy without authentication")
    void shouldApplyProxyWithoutAuth() {
      // GIVEN
      final HttpCommandConfig.ProxyConfig proxy =
          HttpCommandConfig.ProxyConfig.builder().host("proxy.corp.com").port(8080).build();
      final HttpCommandConfig config =
          HttpCommandConfig.builder().proxy(Optional.of(proxy)).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).contains("-x 'proxy.corp.com:8080'");
      assertThat(cmd).doesNotContain("--proxy-user");
    }

    @Test
    @DisplayName("should apply HTTP proxy with authentication")
    void shouldApplyProxyWithAuth() {
      // GIVEN
      final HttpCommandConfig.ProxyConfig proxy =
          HttpCommandConfig.ProxyConfig.builder()
              .host("proxy.corp.com")
              .port(8080)
              .username(Optional.of("user"))
              .password(Optional.of("pass"))
              .build();
      final HttpCommandConfig config =
          HttpCommandConfig.builder().proxy(Optional.of(proxy)).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).contains("-x 'proxy.corp.com:8080'").contains("--proxy-user 'user:pass'");
    }

    @Test
    @DisplayName("should apply custom CA certificate")
    void shouldApplyCustomCaCertificate() {
      // GIVEN
      final Path certPath = Path.of("/etc/ssl/certs/custom-ca.crt");
      final HttpCommandConfig config =
          HttpCommandConfig.builder().caCertificatePath(Optional.of(certPath)).build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd).contains("--cacert '/etc/ssl/certs/custom-ca.crt'");
    }

    @Test
    @DisplayName("should apply custom headers")
    void shouldApplyCustomHeaders() {
      // GIVEN
      final HttpCommandConfig config =
          HttpCommandConfig.builder()
              .customHeaders(Map.of("Authorization", "Bearer token123", "X-Request-ID", "abc-123"))
              .build();
      final HttpCommandBuilder builder = new CurlCommandBuilder(config);

      // WHEN
      final String cmd = builder.buildGetRequest("http://localhost");

      // THEN
      assertThat(cmd)
          .contains("-H 'Authorization: Bearer token123'")
          .contains("-H 'X-Request-ID: abc-123'");
    }
  }

  // ==================== Validation ====================

  @Nested
  @DisplayName("Validation")
  class Validation {

    @Test
    @DisplayName("should throw NullPointerException when URL is null (GET)")
    void shouldThrowOnNullUrl_get() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildGetRequest(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("url");
    }

    @Test
    @DisplayName("should throw NullPointerException when URL is null (GET fail-on-error)")
    void shouldThrowOnNullUrl_getFailOnError() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildGetRequestFailOnError(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("url");
    }

    @Test
    @DisplayName("should throw NullPointerException when URL is null (POST)")
    void shouldThrowOnNullUrl_post() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildPostJsonRequest(null, "{}"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("url");
    }

    @Test
    @DisplayName("should throw NullPointerException when JSON payload is null (POST)")
    void shouldThrowOnNullPayload_post() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildPostJsonRequest("http://localhost", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("jsonPayload");
    }

    @Test
    @DisplayName("should throw NullPointerException when URL is null (PUT)")
    void shouldThrowOnNullUrl_put() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildPutJsonRequest(null, "{}"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("url");
    }

    @Test
    @DisplayName("should throw NullPointerException when JSON payload is null (PUT)")
    void shouldThrowOnNullPayload_put() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildPutJsonRequest("http://localhost", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("jsonPayload");
    }

    @Test
    @DisplayName("should throw NullPointerException when URL is null (DELETE)")
    void shouldThrowOnNullUrl_delete() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildDeleteRequest(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("url");
    }

    @Test
    @DisplayName("should throw NullPointerException when config is null")
    void shouldThrowOnNullConfig() {
      // WHEN / THEN
      assertThatThrownBy(() -> new CurlCommandBuilder(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config");
    }
  }

  // ==================== Tool Information ====================

  @Nested
  @DisplayName("Tool Information")
  class ToolInformation {

    @Test
    @DisplayName("should report curl as tool name")
    void shouldReportToolName() {
      // WHEN
      final String toolName = builder.getToolName();

      // THEN
      assertThat(toolName).isEqualTo("curl");
    }

    @Test
    @DisplayName("should report availability as true")
    void shouldReportAvailability() {
      // WHEN
      final boolean available = builder.isAvailable();

      // THEN
      assertThat(available).isTrue();
    }

    @Test
    @DisplayName("should return true via fallback when 'which' fails but 'command -v' succeeds")
    void shouldReturnTrueViaFallbackWhenWhichFails() throws Exception {
      final Process whichFail = mock(Process.class);
      when(whichFail.waitFor()).thenReturn(1); // which curl → not found

      final Process commandVOk = mock(Process.class);
      when(commandVOk.waitFor()).thenReturn(0); // command -v curl → found

      // Each ProcessBuilder construction gets a different Process: first→fail, second→ok
      final int[] callCount = {0};
      try (var mocked = mockConstruction(ProcessBuilder.class, (pb, ctx) -> {
        when(pb.redirectErrorStream(anyBoolean())).thenReturn(pb);
        if (callCount[0]++ == 0) {
          when(pb.start()).thenReturn(whichFail);
        } else {
          when(pb.start()).thenReturn(commandVOk);
        }
      })) {
        assertThat(new CurlCommandBuilder().isAvailable()).isTrue();
      }
    }

    @Test
    @DisplayName("should return false when both 'which' and 'command -v' fail")
    void shouldReturnFalseWhenBothChecksFail() throws Exception {
      final Process fail = mock(Process.class);
      when(fail.waitFor()).thenReturn(1);

      try (var mocked = mockConstruction(ProcessBuilder.class,
          (pb, ctx) -> when(pb.start()).thenReturn(fail))) {
        assertThat(new CurlCommandBuilder().isAvailable()).isFalse();
      }
    }

    @Test
    @DisplayName("should return false when ProcessBuilder throws IOException")
    void shouldReturnFalseWhenProcessBuilderThrows() throws Exception {
      try (var mocked = mockConstruction(ProcessBuilder.class,
          (pb, ctx) -> when(pb.start()).thenThrow(new java.io.IOException("exec failed")))) {
        assertThat(new CurlCommandBuilder().isAvailable()).isFalse();
      }
    }
  }
}
