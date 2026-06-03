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
 * <h2>What this is</h2>
 *
 * <p>Fills the JVM code cache by generating and JIT-compiling large numbers of synthetic methods,
 * causing the JIT compiler to disable itself and forcing the JVM to revert to interpreted mode for
 * subsequently loaded methods.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code CODE_CACHE_PRESSURE} stressor via the JVM chaos agent, which generates
 * synthetic class bytecode with many methods and triggers their JIT compilation. The target
 * footprint is approximately {@link #targetMb()} MB. In production, code-cache exhaustion occurs
 * in long-running services that perform heavy dynamic proxy generation (Spring, cglib, ByteBuddy)
 * or hot-deploy in containers without restarting the JVM.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * When the code cache fills, the JIT disables itself ({@code CodeCache is full — disabling
 * compilation}). The JVM continues running in interpreted mode, which is typically 10–100× slower.
 * Throughput collapses; latency rises. The JVM prints a warning but does not throw.
 *
 * <h2>Industry references</h2>
 *
 * <p>Code cache overflow is governed by {@code -XX:ReservedCodeCacheSize}. Oracle JVM
 * Troubleshooting Guide §3.4 documents the {@code CompileTask::compile_id} flooding pattern that
 * leads to exhaustion.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosCodeCachePressure(targetMb = 16)
 * class CodeCachePressureTest {
 *   @Test
 *   void applicationRemainsResponsiveWhenJitDisabled(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosCodeCachePressure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.CodeCachePressureComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosCodeCachePressure {

  /**
   * Approximate code-cache footprint target in megabytes.
   *
   * @return target in MB; default 16
   */
  int targetMb() default 16;

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
    CompositeChaosCodeCachePressure[] value();
  }
}
