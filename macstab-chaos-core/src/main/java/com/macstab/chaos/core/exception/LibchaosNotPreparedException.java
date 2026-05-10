/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.exception;

import org.testcontainers.containers.GenericContainer;

/**
 * Thrown when a syscall-level chaos operation is invoked against a container that was not prepared
 * with the corresponding libchaos library.
 *
 * <p><strong>Why this exists:</strong> libchaos-* libraries hook syscalls via {@code LD_PRELOAD},
 * which the Linux dynamic loader honours <em>only at process start</em>. The library must therefore
 * be installed on the container <strong>before</strong> {@code container.start()}. Once the
 * container is running there is no way to retrofit syscall interception.
 *
 * <p>This exception is raised when a caller attempts an advanced (syscall-level) verb on a
 * container that lacks the {@code macstab.chaos.<lib>.active} label — i.e. the caller skipped the
 * preparation step. Surfacing this loudly is intentional: silent fallback would be a worse failure
 * mode than a clear error message at the call site.
 *
 * <p><strong>Recommended fix</strong> is encoded in the exception message and points at the
 * sanctioned entry point ({@code @SyscallLevelChaos} on the test class, which the {@code
 * ChaosTestingExtension} reads to drive {@code LibchaosTransport.prepare()} pre-start).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public class LibchaosNotPreparedException extends ChaosOperationFailedException {

  /**
   * @param libShortName short name from {@code LibchaosLib} (e.g. {@code "net"}, {@code "io"})
   * @param container the container that was not prepared
   */
  public LibchaosNotPreparedException(
      final String libShortName, final GenericContainer<?> container) {
    super(buildMessage(libShortName, imageNameOf(container)));
  }

  /**
   * @param libShortName short name from {@code LibchaosLib}
   * @param dockerImageName the container's image name (for diagnostic context)
   */
  public LibchaosNotPreparedException(final String libShortName, final String dockerImageName) {
    super(buildMessage(libShortName, dockerImageName));
  }

  private static String imageNameOf(final GenericContainer<?> container) {
    if (container == null) {
      return "<null>";
    }
    try {
      return container.getDockerImageName();
    } catch (final RuntimeException ex) {
      return "<unknown>";
    }
  }

  private static String buildMessage(final String libShortName, final String image) {
    final String upper = libShortName == null ? "<unknown>" : libShortName.toUpperCase();
    return ("libchaos-"
        + libShortName
        + " was not prepared for container '"
        + image
        + "'. "
        + "Syscall-level fault injection requires LD_PRELOAD to be set BEFORE container.start(). "
        + "Fix: declare @SyscallLevelChaos(LibchaosLib."
        + upper
        + ") on the test class so ChaosTestingExtension wires LibchaosTransport.prepare() "
        + "into the pre-start hook.");
  }
}
