/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Optional;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.annotation.ConfigureContainer;
import com.macstab.chaos.core.extension.internal.ContainerResolver;
import com.macstab.chaos.core.extension.internal.ValidationUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles {@link ConfigureContainer} annotation (validation-only).
 *
 * <p>Validates container resource limits. Does NOT set limits (Docker limitation).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ContainerConfigHandler {

  private ContainerConfigHandler() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Process container configuration for container.
   *
   * @param field container field
   * @param testInstance test instance
   * @param annotations list of annotations for this container
   */
  public static void process(
      final Field field, final Object testInstance, final java.util.List<Annotation> annotations) {

    Objects.requireNonNull(field, "field must not be null");
    Objects.requireNonNull(testInstance, "testInstance must not be null");
    Objects.requireNonNull(annotations, "annotations must not be null");

    final Optional<ConfigureContainer> config = extractConfig(annotations);

    if (config.isEmpty()) {
      return;
    }

    final GenericContainer<?> container = ContainerResolver.getContainer(field, testInstance);
    final ConfigureContainer cfg = config.get();

    log.info("Validating resource limits for container '{}'", field.getName());

    // Validate memory
    if (!cfg.memory().isEmpty()) {
      ValidationUtils.validateMemoryFormat(cfg.memory());
      validateMemoryLimit(field, container, cfg.memory());
    }

    // Validate CPU count
    if (cfg.cpus() > 0) {
      ValidationUtils.validateCpuCount(cfg.cpus());
      validateCpuCount(field, container, cfg.cpus());
    }

    // Validate CPU shares
    if (cfg.cpuShares() > 0) {
      ValidationUtils.validateCpuShares(cfg.cpuShares());
      log.warn(
          "CPU shares validation not implemented for container '{}' (Docker API limitation)",
          field.getName());
    }

    // Validate disk size
    if (!cfg.diskSize().isEmpty()) {
      ValidationUtils.validateDiskSizeFormat(cfg.diskSize());
      log.warn(
          "Disk size validation not implemented for container '{}' (Docker API limitation)",
          field.getName());
    }

    log.info("Resource limits validated for container '{}'", field.getName());
  }

  /**
   * Extract ConfigureContainer annotation (field-level only).
   *
   * @param annotations annotations
   * @return ConfigureContainer if present
   */
  private static Optional<ConfigureContainer> extractConfig(
      final java.util.List<Annotation> annotations) {

    for (final Annotation annotation : annotations) {
      if (annotation instanceof ConfigureContainer cfg) {
        return Optional.of(cfg);
      }
    }

    return Optional.empty();
  }

  /**
   * Validate memory limit.
   *
   * @param field field
   * @param container container
   * @param expectedMemory expected memory (e.g., "512M")
   */
  private static void validateMemoryLimit(
      final Field field, final GenericContainer<?> container, final String expectedMemory) {

    final var inspect =
        container
            .getDockerClient()
            .inspectContainerCmd(container.getContainerId())
            .exec()
            .getHostConfig();

    final Long actualMemory = inspect.getMemory();

    if (actualMemory == null || actualMemory == 0) {
      throwConfigError(
          field,
          "memory",
          expectedMemory,
          "Container has no memory limit set. "
              + "Add: .withCreateContainerCmdModifier(cmd -> "
              + "cmd.getHostConfig().withMemory("
              + parseMemoryBytes(expectedMemory)
              + "L))");
    }

    final long expectedBytes = parseMemoryBytes(expectedMemory);

    if (actualMemory < expectedBytes) {
      throwConfigError(
          field,
          "memory",
          expectedMemory,
          String.format(
              "Container memory limit (%d bytes) is less than required (%d bytes)",
              actualMemory, expectedBytes));
    }
  }

  /**
   * Validate CPU count.
   *
   * @param field field
   * @param container container
   * @param expectedCpus expected CPU count
   */
  private static void validateCpuCount(
      final Field field, final GenericContainer<?> container, final int expectedCpus) {

    final var inspect =
        container
            .getDockerClient()
            .inspectContainerCmd(container.getContainerId())
            .exec()
            .getHostConfig();

    final Long actualCpus = inspect.getCpuCount();

    if (actualCpus == null || actualCpus == 0) {
      throwConfigError(
          field,
          "cpus",
          String.valueOf(expectedCpus),
          "Container has no CPU count set. "
              + "Add: .withCreateContainerCmdModifier(cmd -> "
              + "cmd.getHostConfig().withCpuCount("
              + expectedCpus
              + "L))");
    }

    if (actualCpus < expectedCpus) {
      throwConfigError(
          field,
          "cpus",
          String.valueOf(expectedCpus),
          String.format(
              "Container CPU count (%d) is less than required (%d)", actualCpus, expectedCpus));
    }
  }

  /**
   * Parse memory string to bytes.
   *
   * @param memory memory string (e.g., "512M", "1G")
   * @return bytes
   */
  private static long parseMemoryBytes(final String memory) {
    final String numPart = memory.substring(0, memory.length() - 1);
    final char unit = memory.charAt(memory.length() - 1);

    final long value = Long.parseLong(numPart);

    return switch (unit) {
      case 'K' -> value * 1024;
      case 'M' -> value * 1024 * 1024;
      case 'G' -> value * 1024 * 1024 * 1024;
      default -> throw new IllegalArgumentException("Invalid memory unit: " + unit);
    };
  }

  /**
   * Throw configuration error.
   *
   * @param field field
   * @param attribute attribute name
   * @param expected expected value
   * @param reason reason
   */
  private static void throwConfigError(
      final Field field, final String attribute, final String expected, final String reason) {

    throw new IllegalStateException(
        String.format(
            "Container '%s' configuration validation failed for '%s' (expected: %s).%n"
                + "Reason: %s",
            field.getName(), attribute, expected, reason));
  }
}
