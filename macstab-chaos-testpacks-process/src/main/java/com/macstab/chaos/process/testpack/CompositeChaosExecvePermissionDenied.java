/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Every {@code execve()} call fails with {@code EACCES}, simulating a permission-denied failure
 * when trying to execute a binary. From the application's perspective it cannot launch any
 * external process: shell-out commands fail, helper utilities cannot be started, and any feature
 * that relies on spawning child processes to run scripts or tools is broken.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.EXECVE, ProcessErrno.EACCES, toxicity)} via
 * libchaos-process. In production {@code EACCES} from {@code execve} occurs when a container is
 * started on a read-only or noexec-mounted filesystem, when AppArmor or SELinux policy denies
 * execution of a binary, or when a secret-management system removes the executable bit during
 * a credential rotation that also updates the binary.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * With {@code toxicity = 1.0} every exec-based operation fails immediately. Features that rely
 * on shell-out — log rotation, health-check scripts, certificate renewal via certbot, external
 * hash utilities — are completely unavailable. Applications that do not handle exec failure
 * gracefully may enter an inconsistent state or silently skip critical housekeeping tasks.
 * Operator intervention to fix the mount policy or AppArmor profile is required.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code EACCES} from {@code execve} on a noexec mount is documented in the Linux
 * {@code execve(2)} man-page. The Kubernetes documentation on PodSecurityContext
 * {@code readOnlyRootFilesystem} and the AppArmor/SELinux profile guides describe how security
 * policies produce this error in production container environments.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosExecvePermissionDenied
 * class ExecvePermissionDeniedTest {
 *   @Test
 *   void healthCheckScriptFailureIsDetectedAndReported() {
 *     // assert: EACCES surfaced as health-check failure; metric emitted; no silent skip
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosExecvePermissionDenied.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.ExecvePermissionDeniedComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosExecvePermissionDenied {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code EACCES} fires on each {@code execve()} call.
   * Defaults to {@code 1.0} (every exec attempt is denied).
   */
  double toxicity() default 1.0;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosExecvePermissionDenied[] value();
  }
}
