/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.support;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import org.testcontainers.containers.Container.ExecResult;

/**
 * Test support factory for {@link ExecResult} mocks used in cache module tests.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class TestExecResults {

  private TestExecResults() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Create a successful result (exit code 0, empty output). */
  public static ExecResult success() {
    return of(0, "", "");
  }

  /** Create a failed result (exit code 1) with a stderr message. */
  public static ExecResult failure(final String stderr) {
    return of(1, "", stderr);
  }

  /** Create a result with full control over exit code, stdout, and stderr. */
  public static ExecResult of(final int exitCode, final String stdout, final String stderr) {
    final ExecResult result = mock(ExecResult.class);
    lenient().when(result.getExitCode()).thenReturn(exitCode);
    lenient().when(result.getStdout()).thenReturn(stdout);
    lenient().when(result.getStderr()).thenReturn(stderr);
    return result;
  }
}
