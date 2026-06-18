/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Replaces the return value of every matched method with {@code null} (or numeric zero for
 * primitives) at the method exit point, simulating a misbehaving downstream that returns a legal
 * but semantically wrong result — for example, a cache that returns {@code null} on a miss but the
 * caller expects a non-null object.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code METHOD_EXIT} operations via the JVM chaos agent using prefix-match patterns
 * for class and method names, then substitutes the real return value with a synthetic null/zero.
 * The method body executes normally (side effects occur); only the return value is discarded.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Silent data corruption: the method ran and committed state, but the caller received a corrupt
 * result. The caller may retry the write (duplicate), skip processing (silent data loss), or throw
 * a {@code NullPointerException}. Unlike exception injection, the application has no signal that
 * anything went wrong at the call site.
 *
 * <h2>Industry references</h2>
 *
 * <p>Return value corruption is the basis of several well-known security vulnerabilities: SSL
 * return value confusion bugs (CVE-2014-0224, CCS injection) caused by callers not checking the
 * return value of security-critical functions. The same structural weakness exists in application
 * code that ignores return values.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosMethodReturnCorruption(
 *     classPattern = "com.example.inventory",
 *     methodNamePattern = "getQuantity")
 * class ReturnCorruptionTest {
 *   @Test
 *   void checkoutHandlesNullQuantityGracefully(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosMethodReturnCorruption.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.MethodReturnCorruptionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosMethodReturnCorruption {

  /**
   * Prefix matched against the binary class name. Defaults to {@code "*"} — matches every class.
   * Override to scope the fault to a specific package or class prefix.
   */
  String classPattern() default "*";

  /**
   * Prefix matched against the method name. Defaults to {@code "*"} — matches every method.
   * Override to target a specific method name or prefix.
   */
  String methodNamePattern() default "*";

  /**
   * Container id to target. Empty string applies to every JVM-agent container.
   *
   * @return container id; default ""
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosMethodReturnCorruption[] value();
  }
}
