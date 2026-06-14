/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in a test class as the health-check probe for L2 / L3 chaos scenarios.
 *
 * <p>L2 and L3 annotations require a health check — there is no meaningful pass signal without one.
 * The annotated method is called by the framework before applying chaos (baseline check) and after
 * removing it (recovery check). If either check fails the test is marked as a chaos failure with
 * the health check's assertion error as the cause.
 *
 * <p><strong>Convention.</strong> The annotated method must be zero-argument and return {@code
 * void}. It should contain at least one assertion that proves the service is responsive:
 *
 * <pre>{@code
 * @ServiceHealthCheck
 * void healthy() {
 *   assertThat(service.ping()).isEqualTo("ok");
 * }
 * }</pre>
 *
 * <p><strong>Fallback.</strong> When no {@code @ServiceHealthCheck} method is found and the test
 * runs within a Spring Boot context, the framework attempts to verify {@code /actuator/health}
 * automatically. If neither is available the framework raises {@code
 * ExtensionConfigurationException} at test startup.
 *
 * <p><strong>L1 exemption.</strong> L1 primitives placed directly on a test method do not require a
 * health check — the test body's own assertions serve that role.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosL2
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServiceHealthCheck {

  /**
   * Optional URL to call as the health probe. When empty (the default), the annotated method is
   * invoked directly. When non-empty the framework issues an HTTP GET to this URL and expects a 2xx
   * response.
   *
   * @return health endpoint URL, or empty string to use the annotated method
   */
  String value() default "";
}
