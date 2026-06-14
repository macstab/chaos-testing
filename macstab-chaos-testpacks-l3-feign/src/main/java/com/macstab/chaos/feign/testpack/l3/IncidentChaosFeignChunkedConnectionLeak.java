/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates the OpenFeign chunked-response connection-leak: a chunked HTTP response that never
 * completes is never explicitly closed by Apache HttpClient, so the connection is never returned to
 * the pool. The pool drains silently — no exception is thrown, no metric spikes — until the pod
 * eventually runs out of file descriptors and OOMs.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>Connection: RECV timeout — the chunked response hangs indefinitely, blocking the connection
 *       from ever being returned to the pool
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>SEVERE</strong><br>
 * The pool drains silently with no exception and no obvious error signal. The pod eventually OOMs.
 * Extremely difficult to diagnose in production.
 *
 * <h2>Industry references</h2>
 *
 * <p>OpenFeign issue #1474 — chunked response not closed when using Apache HttpClient; connection
 * never returned to pool.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosFeignChunkedConnectionLeak
 * class ChunkedConnectionLeakTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosFeignChunkedConnectionLeak.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.feign.testpack.l3.composers.FeignChunkedConnectionLeakComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosFeignChunkedConnectionLeak {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosFeignChunkedConnectionLeak[] value();
  }
}
