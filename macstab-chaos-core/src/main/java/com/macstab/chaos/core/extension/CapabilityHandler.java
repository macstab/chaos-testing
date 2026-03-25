/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.annotation.RequireCapability;
import com.macstab.chaos.core.extension.internal.ContainerResolver;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles {@link RequireCapability} annotation (validation-only).
 *
 * <p>Validates that containers have required capabilities. Does NOT add capabilities (Docker
 * limitation).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class CapabilityHandler {

  private CapabilityHandler() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Process capability requirements for container.
   *
   * @param field container field
   * @param testInstance test instance
   * @param annotations list of annotations for this container
   */
  public static void process(
      final Field field, final Object testInstance, final List<Annotation> annotations) {

    Objects.requireNonNull(field, "field must not be null");
    Objects.requireNonNull(testInstance, "testInstance must not be null");
    Objects.requireNonNull(annotations, "annotations must not be null");

    final List<Capability> requiredCapabilities = extractCapabilities(annotations);

    if (requiredCapabilities.isEmpty()) {
      return;
    }

    final GenericContainer<?> container = ContainerResolver.getContainer(field, testInstance);

    log.info(
        "Validating capabilities for container '{}': {}", field.getName(), requiredCapabilities);

    // Validate capabilities
    for (final Capability capability : requiredCapabilities) {
      validateCapability(field, container, capability);
    }

    log.info("All capabilities validated for container '{}'", field.getName());
  }

  /**
   * Extract required capabilities from annotations.
   *
   * @param annotations annotations
   * @return list of required capabilities
   */
  private static List<Capability> extractCapabilities(final List<Annotation> annotations) {
    final List<Capability> capabilities = new ArrayList<>();

    for (final Annotation annotation : annotations) {
      if (annotation instanceof RequireCapability requireCapability) {
        for (final Capability capability : requireCapability.value()) {
          if (!capabilities.contains(capability)) {
            capabilities.add(capability);
          }
        }
      }
    }

    return capabilities;
  }

  /**
   * Validate single capability.
   *
   * <p><strong>Note:</strong> This is a best-effort validation. Docker API doesn't provide direct
   * capability inspection, so we check if the container CAN perform operations that require the
   * capability.
   *
   * @param field field
   * @param container container
   * @param capability capability
   */
  private static void validateCapability(
      final Field field, final GenericContainer<?> container, final Capability capability) {

    // For NET_ADMIN, check if iptables is accessible
    if (capability == Capability.NET_ADMIN) {
      try {
        final var result = container.execInContainer("sh", "-c", "which iptables || echo MISSING");

        if (result.getStdout().contains("MISSING")) {
          throwCapabilityError(
              field,
              capability,
              "iptables not found. Install it or the capability check will fail.");
        }

        // Try to list iptables rules (requires NET_ADMIN)
        final var testResult = container.execInContainer("iptables", "-L", "-n");

        if (testResult.getExitCode() != 0) {
          throwCapabilityError(field, capability, "iptables command failed (missing NET_ADMIN?)");
        }

      } catch (final Exception e) {
        throwCapabilityError(field, capability, "Could not validate capability: " + e.getMessage());
      }
    }

    // For other capabilities, log warning (no reliable validation method)
    if (capability != Capability.NET_ADMIN) {
      log.warn(
          "Cannot validate capability {} for container '{}' (no reliable validation method). "
              + "Ensure it's configured correctly.",
          capability,
          field.getName());
    }
  }

  /**
   * Throw capability error with helpful message.
   *
   * @param field field
   * @param capability capability
   * @param reason reason
   */
  private static void throwCapabilityError(
      final Field field, final Capability capability, final String reason) {

    throw new IllegalStateException(
        String.format(
            "Container '%s' requires %s capability for chaos testing.%n"
                + "Reason: %s%n"
                + "Add capability: .withCreateContainerCmdModifier(cmd -> "
                + "cmd.getHostConfig().withCapAdd(Capability.%s))",
            field.getName(), capability, reason, capability.name()));
  }
}
