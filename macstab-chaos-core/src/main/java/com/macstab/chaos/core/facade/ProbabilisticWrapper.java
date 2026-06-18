/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.facade;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * Generic probabilistic wrapper for chaos providers.
 *
 * <p>Uses dynamic proxy to intercept all method calls and apply them probabilistically based on
 * rate and seed.
 *
 * <p><strong>Implementation:</strong>
 *
 * <ul>
 *   <li>Each method call has {@code rate} probability of executing
 *   <li>Uses {@code seed} for repeatable random behavior
 *   <li>Skips utility methods (reset, isSupported, installTools)
 *   <li>Works for ANY chaos provider interface (CpuChaos, MemoryChaos, etc.)
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Uses JDK's L64X128MixRandom generator which is explicitly
 * thread-safe. Safe for concurrent access from multiple threads without external synchronization.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * CpuChaos base = new CgroupsCpuChaos();
 * CpuChaos probabilistic = ProbabilisticWrapper.wrap(base, 0.3, 42);
 *
 * // 30% chance of executing (repeatable with seed=42)
 * probabilistic.throttle(container, 50);
 * }</pre>
 *
 * @param <T> chaos provider interface type
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
final class ProbabilisticWrapper<T> implements InvocationHandler {
  private final T delegate;
  private final double rate;
  private final RandomGenerator random;

  private ProbabilisticWrapper(final T delegate, final double rate, final long seed) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");

    if (rate < 0.0 || rate > 1.0) {
      throw new IllegalArgumentException("rate must be in [0.0, 1.0], got: " + rate);
    }
    this.rate = rate;

    // Use thread-safe, seedable random generator (JDK 17+)
    // L64X128MixRandom: Fast, high-quality, explicitly thread-safe
    this.random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
  }

  /**
   * Wrap chaos provider with probabilistic behavior.
   *
   * <p><strong>Thread Safety:</strong> Each call creates a new wrapper with independent Random
   * instance.
   *
   * @param <T> chaos provider interface type
   * @param chaos chaos provider to wrap
   * @param rate execution probability (0.0 = never, 1.0 = always)
   * @param seed random seed for repeatability
   * @return wrapped chaos provider
   * @throws NullPointerException if chaos is null
   * @throws IllegalArgumentException if rate not in [0.0, 1.0]
   */
  @SuppressWarnings("unchecked")
  static <T> T wrap(final T chaos, final double rate, final long seed) {
    Objects.requireNonNull(chaos, "chaos must not be null");

    return (T)
        Proxy.newProxyInstance(
            chaos.getClass().getClassLoader(),
            chaos.getClass().getInterfaces(),
            new ProbabilisticWrapper<>(chaos, rate, seed));
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {

    final String methodName = method.getName();

    // Utility methods always execute (setup/cleanup/capability checks)
    if (isUtilityMethod(method)) {
      log.trace("Executing utility method: {}", methodName);
      return method.invoke(delegate, args);
    }

    // Query methods always execute (getters/status checks)
    if (isQueryMethod(method)) {
      log.trace("Executing query method: {}", methodName);
      return method.invoke(delegate, args);
    }

    // Only ACTION methods are probabilistic
    final boolean execute = random.nextDouble() < rate;

    if (execute) {
      log.debug("Applying chaos action: {} (rate={}, passed)", methodName, rate);
      return method.invoke(delegate, args);
    } else {
      log.trace("Skipping chaos action: {} (rate={}, failed)", methodName, rate);

      // Action skipped - return null for void methods
      if (method.getReturnType() == Void.TYPE) {
        return null;
      }

      // Non-void action methods should not be skipped
      // (This shouldn't happen - all actions are void in chaos providers)
      log.warn(
          "Non-void action method skipped: {} - this may indicate API design issue", methodName);
      return null;
    }
  }

  /**
   * Check if method is utility method (should always execute).
   *
   * <p>Utility methods:
   *
   * <ul>
   *   <li>reset - cleanup
   *   <li>isSupported - capability check
   *   <li>installTools - setup
   * </ul>
   *
   * @param method method to check
   * @return true if utility method
   */
  private boolean isUtilityMethod(final Method method) {
    final String name = method.getName();
    return "reset".equals(name) || "isSupported".equals(name) || "installTools".equals(name);
  }

  /**
   * Check if method is query method (should always execute).
   *
   * <p>Query methods return information without side effects:
   *
   * <ul>
   *   <li>getCurrentUsage - read current state
   *   <li>getPressure - read metrics
   *   <li>listProcesses - read list
   *   <li>getters (get*) - read state
   * </ul>
   *
   * <p><strong>Design Principle:</strong> Only ACTION methods (void return, cause side effects) are
   * probabilistic. Query methods always execute to allow accurate state inspection.
   *
   * @param method method to check
   * @return true if query method (non-void or starts with 'get'/'list')
   */
  private boolean isQueryMethod(final Method method) {
    final String name = method.getName();

    // Non-void methods are queries
    if (method.getReturnType() != Void.TYPE) {
      return true;
    }

    // Methods starting with 'get' or 'list' are queries
    return name.startsWith("get") || name.startsWith("list");
  }
}
