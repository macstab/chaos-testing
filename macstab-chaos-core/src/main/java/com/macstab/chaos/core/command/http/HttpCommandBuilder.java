/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.http;

/**
 * Platform-agnostic HTTP command builder for shell execution.
 *
 * <p>Builds shell commands for HTTP operations using platform-specific tools (curl, wget,
 * PowerShell Invoke-WebRequest, BSD fetch).
 *
 * <p>This is <strong>NOT</strong> an HTTP client library. It generates command strings that are
 * executed via {@link com.macstab.chaos.core.shell.Shell}.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@link CurlCommandBuilder} - Linux/macOS (curl)
 *   <li>{@code WgetCommandBuilder} - Alpine Linux (wget) [future]
 *   <li>{@code PowerShellWebRequestBuilder} - Windows (Invoke-WebRequest) [future]
 *   <li>{@code FetchCommandBuilder} - FreeBSD/OpenBSD (fetch) [future]
 * </ul>
 *
 * <p><strong>Security:</strong> Implementations must properly escape shell metacharacters to
 * prevent injection vulnerabilities.
 *
 * <p><strong>Exit Codes:</strong> Commands return 0 on success, non-zero on failure. Specific codes
 * vary by tool; callers should check {@code exitCode == 0}.
 *
 * <p><strong>Example Usage:</strong>
 *
 * <pre>
 * Platform platform = PlatformDetector.detect(container);
 * HttpCommandBuilder http = platform.getHttpCommandBuilder();
 * Shell shell = platform.getDefaultShell();
 *
 * String cmd = http.buildGetRequest("http://localhost:8080/api");
 * ExecResult result = shell.exec(container, cmd);
 *
 * if (result.getExitCode() == 0) {
 *   String response = result.getStdout();
 * }
 * </pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface HttpCommandBuilder {

  /**
   * Build HTTP GET request.
   *
   * @param url target URL (must not be null)
   * @return shell command string
   * @throws NullPointerException if url is null
   */
  String buildGetRequest(String url);

  /**
   * Build HTTP GET request that fails on HTTP errors.
   *
   * <p>Returns non-zero exit code for HTTP 4xx/5xx responses.
   *
   * <p><strong>Exit Codes:</strong>
   *
   * <ul>
   *   <li>0 = success (HTTP 2xx/3xx)
   *   <li>Non-zero = failure (HTTP 4xx/5xx, network errors, DNS failures, etc.)
   * </ul>
   *
   * <p><strong>Note:</strong> Specific exit codes vary by tool implementation. Callers should check
   * {@code exitCode == 0} for success, not specific error codes.
   *
   * @param url target URL (must not be null)
   * @return shell command string
   * @throws NullPointerException if url is null
   */
  String buildGetRequestFailOnError(String url);

  /**
   * Build HTTP POST request with JSON payload.
   *
   * @param url target URL (must not be null)
   * @param jsonPayload JSON request body (must not be null, will be escaped)
   * @return shell command string
   * @throws NullPointerException if url or jsonPayload is null
   */
  String buildPostJsonRequest(String url, String jsonPayload);

  /**
   * Build HTTP PUT request with JSON payload.
   *
   * @param url target URL (must not be null)
   * @param jsonPayload JSON request body (must not be null, will be escaped)
   * @return shell command string
   * @throws NullPointerException if url or jsonPayload is null
   */
  String buildPutJsonRequest(String url, String jsonPayload);

  /**
   * Build HTTP DELETE request.
   *
   * @param url target URL (must not be null)
   * @return shell command string
   * @throws NullPointerException if url is null
   */
  String buildDeleteRequest(String url);

  /**
   * Build HTTP file download command.
   *
   * <p>Downloads the resource at {@code url} and saves it to {@code outputPath} on the target
   * filesystem (inside the container). Follows redirects by default.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>
   * String cmd = http.buildDownloadRequest(
   *     "https://github.com/.../toxiproxy-server-linux-amd64",
   *     "/usr/local/bin/toxiproxy-server");
   * shell.exec(container, cmd);
   * </pre>
   *
   * @param url source URL (must not be null)
   * @param outputPath destination path on the target filesystem (must not be null)
   * @return shell command string
   * @throws NullPointerException if url or outputPath is null
   */
  String buildDownloadRequest(String url, String outputPath);

  /**
   * Check if the underlying HTTP tool is available.
   *
   * <p>Used for automatic fallback in platform detection (e.g., wget when curl unavailable).
   *
   * @return true if the tool exists and is executable
   */
  boolean isAvailable();

  /**
   * Get the name of the underlying HTTP tool.
   *
   * @return tool name (e.g., "curl", "wget", "powershell")
   */
  String getToolName();
}
