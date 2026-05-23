/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.dns;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.jvm.annotation.l1.JvmInterceptorBinding;
import com.macstab.chaos.jvm.annotation.l1.JvmSelectorKind;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Parks the calling thread inside {@link java.net.InetAddress#getByName(String)
 * InetAddress.getByName()} for the configured number of milliseconds before the JVM's DNS
 * resolver is consulted — every hostname-to-address lookup takes at least {@code delayMs} longer
 * than normal.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code DNS} selector family with the {@code delay}
 * effect applied to the {@code DNS_RESOLVE} operation. It intercepts the JVM's DNS resolution path
 * at the JSSE / {@code java.net} level — specifically {@code InetAddress.getByName(String)} and
 * {@code InetAddress.getAllByName(String)} — and artificially inflates the resolution latency by
 * parking the calling thread before the resolver is consulted. This is distinct from the
 * libchaos DNS module, which operates at the libc {@code getaddrinfo} level and affects all
 * processes on the host; this annotation affects only JVM-level name resolution.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code InetAddress.getByName(String)} and
 * {@code InetAddress.getAllByName(String)}. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the JVM's name-service lookup begins (before the
 *       {@code NameService} SPI chain is consulted).
 *   <li>The delay effect calls {@code LockSupport.parkNanos} on the calling thread for the
 *       configured duration in milliseconds.
 *   <li>After the park returns, the real resolver runs — checking the JVM's internal DNS cache
 *       ({@code InetAddress} positive/negative cache) and, on a cache miss, calling the OS
 *       resolver — and returns the result to the caller.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The wall-clock time of a connection-establishment call (which internally calls
 *       {@code getByName}) is at least {@code delayMs} longer — assert via client-side timing.
 *   <li>Connection pools that resolve hostnames lazily (e.g. on first checkout after a cache
 *       miss) show elevated checkout latency proportional to {@code delayMs} — assert the pool
 *       checkout time.
 *   <li>HTTP clients with connection-timeout settings shorter than {@code delayMs} throw a
 *       {@code ConnectException} or {@code SocketTimeoutException} before the connection is
 *       attempted — assert the exception type.
 *   <li>Cached addresses are not affected: once an address is in the JVM's positive cache, the
 *       interceptor still fires but the real resolver returns immediately from cache — the total
 *       delay is {@code delayMs} regardless of whether the cache is warm.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a corporate DNS resolver under
 * heavy load that takes 800 ms to respond — every new outbound HTTP connection from a Java service
 * that does not pre-warm its connection pool stalls for 800 ms at the DNS step, pushing the
 * end-to-end latency over the configured 1-second timeout and causing cascading
 * {@code ConnectTimeoutException} failures across all dependent services.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> {@code InetAddress.getByName} is the canonical JVM DNS
 * entry point used by {@code Socket}, {@code URL}, {@code HttpURLConnection}, and all high-level
 * HTTP clients (OkHttp, Apache HttpClient, JDK 11 {@code HttpClient}). The agent retransforms
 * {@code InetAddress} via the bootstrap class loader channel. The interceptor fires on cache hits
 * as well as cache misses — the park applies before any lookup, not just uncached lookups.
 *
 * <p><strong>JVM DNS cache interaction.</strong> The JVM maintains two caches: a positive cache
 * (successful lookups, TTL controlled by {@code networkaddress.cache.ttl} security property,
 * default 30 s) and a negative cache (failed lookups, TTL controlled by
 * {@code networkaddress.cache.negative.ttl}, default 10 s). This annotation's delay fires
 * before the cache is checked, so even cache-hit paths are delayed. The real resolver (OS
 * {@code getaddrinfo}) is only invoked on a cache miss.
 *
 * <p><strong>Distinction from the libchaos DNS module.</strong> The libchaos DNS module injects
 * latency at the native {@code getaddrinfo} call, affecting all processes and all languages
 * sharing the host's libc. This annotation operates exclusively within the JVM's Java-level
 * resolver path and does not affect other processes or non-Java code running in the same
 * container.
 *
 * <p><strong>Thread binding.</strong> The park is applied to the calling thread, which may be a
 * virtual thread on JDK 21+. Parking a virtual thread at a {@code InetAddress} call that
 * internally calls native code (e.g. {@code getaddrinfo} via JNI) pins the carrier thread for
 * the duration of the park before the native call, and then again during the native call itself
 * (as native calls always pin the carrier in JDK 21).
 *
 * <p><strong>Custom name services.</strong> Applications that install a custom
 * {@code sun.net.spi.nameservice.NameService} (e.g. for service-discovery integration) still pass
 * through {@code InetAddress.getByName} — the interceptor fires regardless of which
 * {@code NameService} implementation is active.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosDnsResolveDelay(delayMs = 600)
 * class DnsDelayTest {
 *
 *   @Test
 *   void connectionTimesOutWhenDnsIsSlow(AppConnectionInfo info) {
 *     // client has 500 ms connect timeout; DNS alone takes 600 ms
 *     assertThatThrownBy(() -> client.connect(info, "downstream-service"))
 *         .isInstanceOf(ConnectException.class)
 *         .hasMessageContaining("timed out");
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Required:</strong>
 *
 * <ul>
 *   <li>{@code @JvmAgentChaos} on the container annotation — attaches the chaos agent before the
 *       JVM starts; omitting it causes {@code ExtensionConfigurationException} at {@code beforeAll}.
 *   <li>{@code macstab-chaos-java} on the test classpath — the translator class must be loadable.
 *   <li>A Java container image — the container must run a JVM process.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.jvm.api.OperationType#DNS_RESOLVE
 * @see com.macstab.chaos.jvm.api.ChaosSelector#dns(java.util.Set)
 * @see ChaosDnsResolveInjectException
 */
@Repeatable(ChaosDnsResolveDelay.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.jvm.annotation.l1.translators.DelayTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.DNS,
    operationType = OperationType.DNS_RESOLVE)
public @interface ChaosDnsResolveDelay {

  /**
   * @return min delay in milliseconds
   */
  long delayMs() default 100L;

  /**
   * @return max delay in milliseconds (defaults to delayMs for deterministic delay)
   */
  long maxDelayMs() default 100L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosDnsResolveDelay(id = "primary",  probability = 0.001)
   * @ChaosDnsResolveDelay(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosDnsResolveDelay[] value();
  }
}
