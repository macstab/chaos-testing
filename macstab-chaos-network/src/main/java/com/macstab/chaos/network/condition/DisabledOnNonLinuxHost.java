/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.network.condition;

import java.io.File;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 execution condition that disables tests on non-Linux host operating systems (macOS,
 * Windows), while enabling them on Linux hosts or inside any container.
 *
 * <p><strong>Use Case:</strong> Testcontainers + Redis Sentinel require native Docker networking
 * (host network mode), which only works on:
 *
 * <ul>
 *   <li>Linux host (native Docker daemon)
 *   <li>Any dev container / CI container (Linux-based, even when running on macOS/Windows host)
 * </ul>
 *
 * <p><strong>Does NOT work on:</strong>
 *
 * <ul>
 *   <li>macOS host (Docker Desktop uses VM, no host networking)
 *   <li>Windows host (Docker Desktop uses WSL2/Hyper-V, no host networking)
 * </ul>
 *
 * <p><strong>Detection Strategy:</strong>
 *
 * <ol>
 *   <li>Check for {@code /.dockerenv} file (Docker container marker)
 *   <li>If present → <strong>enable</strong> (container is always Linux-based)
 *   <li>If absent → check {@code os.name} system property
 *   <li>If Linux → <strong>enable</strong>
 *   <li>Otherwise → <strong>disable</strong>
 * </ol>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * @DisabledOnNonLinuxHost
 * class SentinelIntegrationTest {
 *   // Only runs on Linux host or in dev containers
 * }
 * }</pre>
 *
 * <p><strong>Custom Reason:</strong>
 *
 * <pre>{@code
 * @DisabledOnNonLinuxHost("Sentinel requires native Docker networking")
 * class SentinelFailoverTest {
 *   // ...
 * }
 * }</pre>
 *
 * <p><strong>Why Not {@code @EnabledOnOs(OS.LINUX)}?</strong>
 *
 * <p>JUnit's built-in {@code @EnabledOnOs} only checks {@code os.name}, which reports "Mac OS X"
 * even inside a dev container running on macOS. This annotation adds container detection via {@code
 * /.dockerenv}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see org.junit.jupiter.api.condition.EnabledOnOs
 * @see org.junit.jupiter.api.condition.DisabledOnOs
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(DisabledOnNonLinuxHost.Condition.class)
public @interface DisabledOnNonLinuxHost {

  /**
   * Custom reason for disabling the test (shown in test reports).
   *
   * <p>Default: "Disabled on macOS/Windows host (Docker networking not native). Enabled on Linux
   * host or any dev container."
   *
   * @return custom reason text
   */
  String value() default
      "Disabled on macOS/Windows host (Docker networking not native). "
          + "Enabled on Linux host or any dev container.";

  /**
   * JUnit 5 execution condition that evaluates {@link DisabledOnNonLinuxHost}.
   *
   * <p>This condition is stateless and thread-safe.
   */
  final class Condition implements ExecutionCondition {

    /** Docker container marker file (exists in all Docker containers). */
    private static final String DOCKER_ENV_FILE = "/.dockerenv";

    /**
     * Creates a condition instance.
     *
     * <p>JUnit 5 instantiates this class via reflection (default constructor required). Condition
     * is stateless (no instance fields), so multiple instances are safe.
     */
    public Condition() {
      // Stateless - no initialization needed
    }

    /**
     * Evaluate whether the test should be executed.
     *
     * <p>Decision flow:
     *
     * <ol>
     *   <li>In container? → <strong>enabled</strong>
     *   <li>On Linux host? → <strong>enabled</strong>
     *   <li>On macOS/Windows host? → <strong>disabled</strong>
     * </ol>
     *
     * @param context test context
     * @return enabled if Linux host or container, disabled otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {

      // Priority 1: Container detection (always Linux-based)
      if (isRunningInContainer()) {
        return ConditionEvaluationResult.enabled(
            "Running in container (Linux-based, native Docker networking available)");
      }

      // Priority 2: Host OS detection
      final var os = System.getProperty("os.name").toLowerCase();
      if (os.contains("linux")) {
        return ConditionEvaluationResult.enabled(
            "Running on Linux host (native Docker daemon, host networking supported)");
      }

      // macOS/Windows host → disable
      final var reason =
          context
              .getElement()
              .flatMap(
                  element ->
                      Optional.ofNullable(element.getAnnotation(DisabledOnNonLinuxHost.class)))
              .map(DisabledOnNonLinuxHost::value)
              .orElse("Disabled on non-Linux host");

      return ConditionEvaluationResult.disabled(String.format("%s (Detected OS: %s)", reason, os));
    }

    /**
     * Check if running inside a Docker container.
     *
     * <p>Detection: {@code /.dockerenv} file exists in all Docker containers (created by Docker
     * daemon).
     *
     * @return {@code true} if {@code /.dockerenv} exists, {@code false} otherwise
     */
    private boolean isRunningInContainer() {
      return new File(DOCKER_ENV_FILE).exists();
    }
  }
}
