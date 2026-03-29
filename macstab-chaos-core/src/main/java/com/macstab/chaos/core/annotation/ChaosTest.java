/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import com.macstab.chaos.core.extension.ChaosTestingExtension;

/**
 * Internal meta-annotation that auto-enables chaos testing infrastructure.
 *
 * <p><strong>Purpose:</strong> Provides a single point of extension registration for all chaos
 * testing annotations. This is a framework-internal contract; users should never apply this
 * annotation directly to test classes.
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * User Layer (test classes)
 *     ↓ uses
 * Public API (@RedisStandalone, @Resources)
 *     ↓ extends (@ChaosTest)
 * Framework Layer (ChaosTestingExtension)
 *     ↓ orchestrates
 * Infrastructure (plugins, parsers, Docker API)
 * </pre>
 *
 * <p><strong>Framework Usage (Internal):</strong>
 *
 * <pre>{@code
 * @ChaosTest  // Implicitly registers ChaosTestingExtension
 * public @interface RedisStandalone {
 *   // Container-specific attributes
 * }
 * }</pre>
 *
 * <p><strong>User Usage (External):</strong>
 *
 * <pre>{@code
 * @RedisStandalone  // ChaosTestingExtension auto-enabled (via @ChaosTest)
 * class RedisTest {
 *   // Tests run with chaos testing infrastructure
 * }
 * }</pre>
 *
 * <p><strong>Design Rationale:</strong>
 *
 * <ul>
 *   <li><strong>DRY:</strong> Single registration point for extension (not duplicated across 10+
 *       annotations)
 *   <li><strong>Maintainability:</strong> Change extension class? Update 1 file (not 10)
 *   <li><strong>Clear boundary:</strong> Framework concerns hidden from public API
 *   <li><strong>Industry standard:</strong> Matches Spring Boot (@SpringBootTest), Micronaut,
 *       Quarkus patterns
 *   <li><strong>Scalability:</strong> Adding annotation 11-100 = 1 line change (not N× @ExtendWith)
 * </ul>
 *
 * <p><strong>Not for Direct Use:</strong> This annotation should NEVER appear in user test code. It
 * exists solely for framework-internal composition.
 *
 * <p><strong>Example (Correct):</strong>
 *
 * <pre>{@code
 * // In chaos module annotation
 * @ChaosTest
 * public @interface PostgresStandalone { ... }
 *
 * // In user test (no @ChaosTest visible)
 * @PostgresStandalone
 * class PostgresTest { ... }
 * }</pre>
 *
 * <p><strong>Example (Incorrect):</strong>
 *
 * <pre>{@code
 * // ❌ NEVER do this
 * @ChaosTest
 * class UserTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 2.0
 * @see ChaosTestingExtension
 * @see Resources
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ChaosTestingExtension.class)
public @interface ChaosTest {
  // Marker annotation (no attributes needed)
}
