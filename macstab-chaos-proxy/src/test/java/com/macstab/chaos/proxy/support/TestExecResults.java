/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.support;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import org.testcontainers.containers.Container.ExecResult;

/**
 * Test support factory for {@link ExecResult} mocks.
 *
 * <p>Centralizes creation of {@code ExecResult} mocks to avoid duplication across test classes.
 * Uses {@code lenient()} stubbing so the mock can be used in both verified and unverified
 * assertions without Mockito complaining about unused interactions.
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * final ExecResult result = TestExecResults.success();
 * final ExecResult failure = TestExecResults.failure("iptables error");
 * final ExecResult custom  = TestExecResults.of(0, "stdout content", "");
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TestExecResults {

  private TestExecResults() {
    throw new UnsupportedOperationException("TestExecResults is a utility class");
  }

  /**
   * Create a successful result (exit code 0, empty output).
   *
   * @return mocked {@code ExecResult} with exit code 0
   */
  public static ExecResult success() {
    return of(0, "", "");
  }

  /**
   * Create a successful result with stdout content.
   *
   * @param stdout standard output content
   * @return mocked {@code ExecResult} with exit code 0
   */
  public static ExecResult success(final String stdout) {
    return of(0, stdout, "");
  }

  /**
   * Create a failed result (exit code 1) with a stderr message.
   *
   * @param stderr error message
   * @return mocked {@code ExecResult} with exit code 1
   */
  public static ExecResult failure(final String stderr) {
    return of(1, "", stderr);
  }

  /**
   * Create a result with full control over exit code, stdout, and stderr.
   *
   * @param exitCode process exit code
   * @param stdout standard output
   * @param stderr standard error
   * @return mocked {@code ExecResult}
   */
  public static ExecResult of(final int exitCode, final String stdout, final String stderr) {
    final ExecResult result = mock(ExecResult.class);
    lenient().when(result.getExitCode()).thenReturn(exitCode);
    lenient().when(result.getStdout()).thenReturn(stdout);
    lenient().when(result.getStderr()).thenReturn(stderr);
    return result;
  }
}
