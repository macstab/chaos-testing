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
 * <p>Simulates JVM code cache exhaustion: the JIT compiler fills the native code cache with
 * synthetic compiled methods until it reaches capacity and shuts down permanently. All subsequent
 * application code runs in the interpreter, causing a severe throughput collapse.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: CodeCachePressure with {@code classCount} classes × {@code methodsPerClass} methods,
 *       each JIT-compiled approximately 15,000 times to maximise cache consumption
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * 10–50x throughput drop once JIT is permanently disabled; zero exceptions are thrown; the
 * degradation accumulates over hours or days before being noticed.
 *
 * <h2>Industry references</h2>
 *
 * <p>JIT code cache exhaustion: the Atlassian Confluence CodeCache full incident (2019) caused a
 * complete service performance collapse with no alerts, no errors, and a gradual degradation that
 * looked like a memory leak in monitoring but had a clean heap.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosJvmCodeCacheFull(classCount = 500, methodsPerClass = 100)
 * class CodeCacheFullTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmCodeCacheFull.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmCodeCacheFullComposer",
    severity = Severity.CRITICAL)
public @interface IncidentChaosJvmCodeCacheFull {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Number of synthetic classes to generate and JIT-compile. */
  int classCount() default 500;

  /** Number of methods per synthetic class. */
  int methodsPerClass() default 100;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmCodeCacheFull[] value();
  }
}
