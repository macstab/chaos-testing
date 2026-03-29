/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

import java.util.Collection;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.util.PackageInstaller;

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
 * @since 2.0
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
}
