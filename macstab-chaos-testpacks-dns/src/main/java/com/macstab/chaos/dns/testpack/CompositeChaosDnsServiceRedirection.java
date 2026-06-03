/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>The service token in every forward DNS lookup is silently rewritten to an invalid service name
 * before the resolver is consulted. Applications that pass a service name (port alias) alongside the
 * hostname to {@code getaddrinfo()} receive a different service binding — or an {@code EAI_SERVICE}
 * error if the replacement name is unknown. Tests whether the application correctly handles service
 * name mismatches and unexpected port assignments.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code DnsRule.service(anyForward(), serviceName)} via libchaos-dns. In production
 * this happens when a service registry entry is corrupted, when a port alias in {@code /etc/services}
 * diverges between container images, or when a sidecar proxy remaps the service port during a
 * blue/green switchover.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Applications that specify a service name to resolve the correct port will silently connect to the
 * wrong port or fail with an unexpected error. Connection pools warm against the wrong endpoint.
 * Applications that ignore the service token and hard-code ports are unaffected, making this a
 * useful compliance test for service-registry-aware code.
 *
 * <h2>Industry references</h2>
 *
 * <p>Service-name redirection as a misconfiguration vector is described in the POSIX {@code
 * getaddrinfo(3)} spec (IEEE Std 1003.1-2017) and in RFC 3493 §6.1 ("Basic Socket Interface
 * Extensions"). Port-aliasing bugs in container orchestrators are covered in Kubernetes issue
 * tracker discussions on headless service DNS and EndpointSlice service name propagation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @PostgresStandalone
 * @SyscallLevelChaos(LibchaosLib.DNS)
 * @CompositeChaosDnsServiceRedirection(serviceName = "invalid-svc")
 * class MyResilienceTest {
 *
 *   @ServiceHealthCheck
 *   void healthy() { assertThat(service.ping()).isEqualTo("ok"); }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosDnsServiceRedirection.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.dns.testpack.composers.DnsServiceRedirectionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosDnsServiceRedirection {

  /**
   * Hostname to target. {@code "*"} (the default) applies the service rewrite to all forward
   * lookups. Provide a specific hostname to limit the chaos to one dependency.
   */
  String host() default "*";

  /**
   * Replacement service name passed to the resolver. Defaults to {@code "invalid-svc"}, which
   * causes {@code EAI_SERVICE} on any standard resolver. Use a valid service name (e.g. {@code
   * "http"}) to test port-binding mismatches instead of outright resolution failure.
   */
  String serviceName() default "invalid-svc";

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-dns.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosDnsServiceRedirection[] value();
  }
}
