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
 * <p>Simulates a JIT deoptimization storm caused by class retransformation: JVMTI class
 * retransformation invalidates all compiled code for the affected classes, forcing the JIT to
 * deoptimise and recompile on the next invocation. Repeated at high frequency this creates a
 * continuous CPU spike and throughput collapse.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: SafepointStorm every {@code gcIntervalMs} ms with retransformation of {@code
 *       retransformClassCount} classes per cycle — each retransformation invalidates previously
 *       compiled native code and triggers deoptimisation safepoints
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * 2–5 s CPU spike followed by 80% throughput drop during the recompilation phase; self-resolving
 * after the JIT recompiles, but repeats on the next cycle.
 *
 * <h2>Industry references</h2>
 *
 * <p>JIT deoptimisation storms: observed in APM/instrumentation agent interactions (Datadog,
 * Dynatrace) where agent class retransformation during peak traffic caused repeated deopt storms,
 * CPU throttling, and P99 latency spikes that resolved without intervention.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosJvmDeoptimizationStorm(gcIntervalMs = 300L, retransformClassCount = 100)
 * class DeoptimizationStormTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmDeoptimizationStorm.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmDeoptimizationStormComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosJvmDeoptimizationStorm {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Interval between safepoint cycles in milliseconds. */
  long gcIntervalMs() default 200L;

  /** Number of classes retransformed per safepoint cycle. */
  int retransformClassCount() default 50;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmDeoptimizationStorm[] value();
  }
}
