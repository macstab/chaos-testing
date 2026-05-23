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
 * Throws the configured exception (default: {@link java.io.IOException}) inside
 * {@link java.net.InetAddress#getByName(String) InetAddress.getByName()} before the JVM's DNS
 * resolver is consulted — every hostname lookup fails immediately with the injected exception,
 * making all new outbound connections by hostname impossible.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>An L1 JVM chaos primitive targeting the {@code DNS} selector family with the
 * {@code injectException} effect applied to the {@code DNS_RESOLVE} operation. It intercepts
 * {@code InetAddress.getByName(String)} and {@code InetAddress.getAllByName(String)} at the JVM
 * level and throws before the JVM's name-service chain is consulted. This is distinct from the
 * libchaos DNS module, which operates at the native {@code getaddrinfo} level; this annotation
 * affects only JVM-level hostname resolution and not native processes in the same container. The
 * annotation is declared on the test class or method alongside a container annotation and is
 * active for the lifetime of the annotated scope.
 *
 * <h2>What chaos this applies</h2>
 *
 * <p>The JVM agent installs a Byte Buddy interceptor on {@code InetAddress.getByName(String)} and
 * {@code InetAddress.getAllByName(String)}. When the interceptor fires:
 *
 * <ol>
 *   <li>Execution is captured before the JVM's positive DNS cache is checked and before the
 *       {@code NameService} SPI is consulted.
 *   <li>The exception injection effect constructs and throws an instance of the configured
 *       exception class (default: {@code java.io.IOException} with the configured message).
 *   <li>The exception propagates up the call stack to the first handler — typically the networking
 *       layer wrapping {@code getByName} in a {@code Socket} or HTTP client, which converts it to
 *       a {@code ConnectException} or {@code UnknownHostException} as appropriate.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Any call to {@code new Socket(hostname, port)} or an HTTP client's {@code connect} step
 *       throws {@code UnknownHostException} (or the configured exception type) — assert the
 *       exception type and that it is not swallowed silently.
 *   <li>Connection pools that resolve hostnames on checkout (rather than at creation time) fail
 *       checkout with the injected exception — assert that the pool's error handler increments a
 *       failure metric.
 *   <li>IP-literal addresses (e.g. {@code "192.168.1.1"}) bypass {@code getByName} resolution
 *       and are not affected — assert that direct-IP connections continue to work while
 *       hostname-based connections fail.
 *   <li>Service-discovery clients that fall back to a cached address list on resolution failure
 *       activate their fallback — assert that the fallback address is used.
 * </ul>
 *
 * <p><strong>Production failure mode this simulates:</strong> a corporate DNS server that becomes
 * unreachable mid-operation, causing the OS resolver to return SERVFAIL for every query — the
 * JVM wraps each SERVFAIL as an {@code UnknownHostException}, all outbound service calls fail
 * immediately, and the application's circuit breaker opens within one polling interval, rejecting
 * all requests until DNS is restored.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p><strong>Interception point.</strong> The agent retransforms {@code java.net.InetAddress} via
 * the bootstrap class loader instrumentation channel. The injected exception is thrown before any
 * positive or negative cache lookup — even hostnames that were successfully resolved in the past
 * and whose cache entry is still valid fail under this annotation.
 *
 * <p><strong>Exception type selection.</strong> {@code InetAddress.getByName} declares
 * {@code throws UnknownHostException}. Callers compile against this signature and typically catch
 * {@code UnknownHostException} or its parent {@code IOException}. Configure
 * {@code exceptionClassName = "java.net.UnknownHostException"} to inject the exact type that
 * callers expect and exercise their specific catch blocks. Injecting a {@code RuntimeException}
 * instead tests whether callers have forgotten to handle the checked exception path.
 *
 * <p><strong>JVM DNS cache bypass.</strong> The JVM's positive and negative caches are consulted
 * inside the real {@code getByName} implementation. Because the inject-exception effect throws
 * before the real implementation runs, even cached addresses are never returned. This is more
 * severe than a real NXDOMAIN response, which would populate the negative cache and eventually be
 * returned from cache rather than re-querying the resolver.
 *
 * <p><strong>Distinction from {@code ChaosDnsResolveDelay}.</strong> The delay effect eventually
 * returns a valid address after a park; outbound connections succeed (with added latency). The
 * inject-exception effect always fails the lookup; outbound connections are impossible by hostname.
 * Use inject-exception to test circuit breakers and fallback logic; use delay to test
 * timeout-tolerance.
 *
 * <p><strong>Custom name services.</strong> A custom {@code sun.net.spi.nameservice.NameService}
 * installed by the application (e.g. for Consul service discovery) is never reached when this
 * annotation is active — the exception fires before the SPI chain is consulted. If the
 * application has a secondary lookup path that does not use {@code InetAddress}, that path is
 * also not affected.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosDnsResolveInjectException(
 *     exceptionClassName = "java.net.UnknownHostException",
 *     message = "chaos: DNS unavailable")
 * class DnsFailureTest {
 *
 *   @Test
 *   void circuitBreakerOpensWhenDnsFails(AppConnectionInfo info) {
 *     // after 3 failures the circuit breaker should open
 *     for (int i = 0; i < 3; i++) {
 *       assertThatThrownBy(() -> client.call(info)).isInstanceOf(ServiceException.class);
 *     }
 *     assertThat(client.circuitBreakerState(info)).isEqualTo("OPEN");
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
 * @see ChaosDnsResolveDelay
 */
@Repeatable(ChaosDnsResolveInjectException.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.ExceptionInjectionTranslator")
@JvmInterceptorBinding(
    selectorKind = JvmSelectorKind.DNS,
    operationType = OperationType.DNS_RESOLVE)
public @interface ChaosDnsResolveInjectException {

  /**
   * @return binary class name of the exception to throw (e.g. "java.io.IOException")
   */
  String exceptionClassName() default "java.io.IOException";

  /**
   * @return exception message
   */
  String message() default "injected by chaos L1";

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
   * @ChaosDnsResolveInjectException(id = "primary",  probability = 0.001)
   * @ChaosDnsResolveInjectException(id = "replica",  probability = 0.01)
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
    ChaosDnsResolveInjectException[] value();
  }
}
