/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.extension.internal;

/**
 * Verifies optional classpath dependencies for chaos engineering features.
 *
 * <p><strong>Purpose:</strong> Provides fail-fast dependency checking before starting containers,
 * producing clear error messages when optional modules are missing.
 *
 * <p><strong>Design:</strong> Static utility class — all methods are static, no instances allowed.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * // Called when enableNetworkChaos=true:
 * DependencyVerifier.requireNetworkModule();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class DependencyVerifier {

  private static final String NETWORK_CHAOS_CONTROLLER_CLASS =
      "com.macstab.chaos.network.control.NetworkChaosController";

  private static final String COMPOSITE_CONNECTION_CHAOS_CLASS =
      "com.macstab.chaos.connection.CompositeConnectionChaos";

  private DependencyVerifier() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Verifies that the macstab-chaos-network module is present on the classpath.
   *
   * <p>Required when {@code enableNetworkChaos=true} is set on a Redis annotation. The network
   * module provides {@code NetworkChaosController}, which drives the kernel packet path ({@code
   * tc/netem} + {@code iptables}) inside the container. Without this dependency, {@code
   * ControlFacade#create} would fail with a {@link NoClassDefFoundError} — this check converts that
   * into a clear startup-time error pointing at the missing build dependency.
   *
   * @throws IllegalStateException if the network module is not on the classpath
   */
  public static void requireNetworkModule() {
    if (!isPresent(NETWORK_CHAOS_CONTROLLER_CLASS)) {
      throw new IllegalStateException(
          "enableNetworkChaos=true requires macstab-chaos-network (NetworkChaosController) on classpath.\n"
              + "This injects latency, jitter and packet loss on the container's kernel packet path"
              + " (tc/netem + iptables).\n\n"
              + "Add to your build.gradle.kts:\n"
              + "    testImplementation(\"com.macstab:macstab-chaos-network:<version>\")");
    }
  }

  /**
   * Verifies that the macstab-chaos-connection module is present on the classpath.
   *
   * <p>Required when {@code enableConnectionChaos=true} is set on a Redis annotation. The
   * connection module provides {@code CompositeConnectionChaos}, which composites libchaos-net's
   * per-syscall errno injection with Toxiproxy's proxy-level fault chaos. Without this dependency,
   * {@code ControlFacade#connection()} would fail at first access with a {@link
   * ClassNotFoundException} — this check converts that into a clear startup-time error pointing at
   * the missing build dependency.
   *
   * @throws IllegalStateException if the connection module is not on the classpath
   */
  public static void requireConnectionModule() {
    if (!isPresent(COMPOSITE_CONNECTION_CHAOS_CLASS)) {
      throw new IllegalStateException(
          "enableConnectionChaos=true requires macstab-chaos-connection (CompositeConnectionChaos) on classpath.\n"
              + "This wires libchaos-net (LD_PRELOAD syscall errno injection) + Toxiproxy fallback.\n\n"
              + "Add to your build.gradle.kts:\n"
              + "    testImplementation(\"com.macstab:macstab-chaos-connection:<version>\")");
    }
  }

  /**
   * Checks if a class is present on the current classpath.
   *
   * @param className fully qualified class name
   * @return {@code true} if the class can be loaded, {@code false} otherwise
   */
  public static boolean isPresent(final String className) {
    try {
      Class.forName(className, false, DependencyVerifier.class.getClassLoader());
      return true;
    } catch (final ClassNotFoundException e) {
      return false;
    }
  }
}
