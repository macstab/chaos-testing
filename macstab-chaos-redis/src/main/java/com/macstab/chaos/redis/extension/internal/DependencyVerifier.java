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
 * DependencyVerifier.requireCacheModule();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 */
public final class DependencyVerifier {

  private static final String REDIS_CACHE_CHAOS_CLASS =
      "com.macstab.chaos.cache.redis.RedisCacheChaosProvider";

  private DependencyVerifier() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Verifies that the macstab-chaos-cache module is present on the classpath.
   *
   * <p>Required when {@code enableNetworkChaos=true} is set on a Redis annotation. The cache
   * module provides the {@code RedisCacheChaosProvider} Toxiproxy integration.
   *
   * @throws IllegalStateException if the cache module is not on the classpath
   */
  public static void requireCacheModule() {
    if (!isPresent(REDIS_CACHE_CHAOS_CLASS)) {
      throw new IllegalStateException(
          "enableNetworkChaos=true requires macstab-chaos-cache (RedisCacheChaosProvider) on classpath.\n"
              + "This proxies Redis traffic: client → Toxiproxy → Redis\n\n"
              + "Add to your build.gradle.kts:\n"
              + "    testImplementation(\"com.macstab:macstab-chaos-cache:${version}\")");
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
