/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates JVM virtual thread carrier pinning: {@code synchronized} blocks pin carrier threads,
 * starving the carrier pool and preventing virtual threads from being scheduled. The service
 * appears to hang with zero errors, zero rejections, and zero warnings in the logs.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: VirtualThreadCarrierPinning with {@code pinnedThreadCount} carriers held inside
 *       synthetic {@code synchronized} blocks for {@code pinDurationMs} ms each, continuously
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * JDK 21+ services using virtual threads hang completely; no exceptions are thrown; JFR
 * VirtualThreadPinned events are the only observable signal.
 *
 * <h2>Industry references</h2>
 *
 * <p>Virtual thread carrier pinning via synchronized: a known JDK 21 production pattern where
 * third-party libraries (Jedis, older JDBC drivers) hold monitors during I/O, starving the
 * ForkJoinPool carrier pool and causing a full service hang without any error output.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosJvmCarrierPinning(pinnedThreadCount = 4, pinDurationMs = 200L)
 * class CarrierPinningTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmCarrierPinning.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmCarrierPinningComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosJvmCarrierPinning {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Number of carrier threads to pin simultaneously. */
  int pinnedThreadCount() default 4;

  /** Duration in milliseconds each carrier thread is held pinned. */
  long pinDurationMs() default 100L;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmCarrierPinning[] value();
  }
}
