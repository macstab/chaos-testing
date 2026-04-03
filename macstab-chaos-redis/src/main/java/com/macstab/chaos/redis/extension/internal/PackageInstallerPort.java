/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import java.util.Collection;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.util.PackageInstaller;
import com.macstab.chaos.core.util.ToolPackage;

/**
 * Abstraction over {@link PackageInstaller} to enable unit testing of classes that install packages
 * into containers.
 *
 * <p><strong>Design:</strong> Non-functional interface (three methods), implemented as a singleton
 * default backed by the real {@link PackageInstaller}, and as a test double in unit tests.
 *
 * <p><strong>Production implementation:</strong> {@link #DEFAULT} — delegates to {@link
 * PackageInstaller}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 * @see DefaultSentinelClusterFactory
 * @see DefaultStandaloneContainerInstanceFactory
 */
public interface PackageInstallerPort {

  /** Production singleton backed by {@link PackageInstaller}. */
  PackageInstallerPort DEFAULT =
      new PackageInstallerPort() {
        @Override
        public boolean isInstalled(final GenericContainer<?> container, final String tool) {
          return PackageInstaller.isInstalled(container, tool);
        }

        @Override
        public void install(final GenericContainer<?> container, final String... packages) {
          PackageInstaller.install(container, packages);
        }

        @Override
        public void install(
            final GenericContainer<?> container,
            final Collection<String> packages,
            final boolean verify) {
          PackageInstaller.install(container, packages, verify);
        }

        @Override
        public void ensureInstalled(
            final GenericContainer<?> container, final Tool... tools) {
          PackageInstaller.ensureInstalled(container, tools);
        }

        @Override
        public void ensureInstalled(
            final GenericContainer<?> container, final ToolPackage... tools) {
          PackageInstaller.ensureInstalled(container, tools);
        }
      };

  /**
   * Checks whether the given tool is installed in the container.
   *
   * @param container target container
   * @param tool tool name (e.g., {@code "tc"})
   * @return {@code true} if installed
   */
  boolean isInstalled(GenericContainer<?> container, String tool);

  /**
   * Installs packages in the container (varargs overload).
   *
   * @param container target container
   * @param packages package names to install
   */
  void install(GenericContainer<?> container, String... packages);

  /**
   * Installs packages in the container with optional verification.
   *
   * @param container target container
   * @param packages package names to install
   * @param verify if {@code true}, verifies installation after install
   */
  void install(GenericContainer<?> container, Collection<String> packages, boolean verify);

  /**
   * Ensures known {@link Tool}s are installed exactly once per container lifetime.
   * Platform-resolved, label-guarded.
   *
   * @param container target container
   * @param tools tools to ensure are installed
   */
  void ensureInstalled(GenericContainer<?> container, Tool... tools);

  /**
   * Ensures raw-package tools are installed exactly once per container lifetime.
   * Label-guarded escape hatch for packages not in the {@link Tool} enum.
   *
   * @param container target container
   * @param tools tool-to-package bindings
   */
  void ensureInstalled(GenericContainer<?> container, ToolPackage... tools);
}
