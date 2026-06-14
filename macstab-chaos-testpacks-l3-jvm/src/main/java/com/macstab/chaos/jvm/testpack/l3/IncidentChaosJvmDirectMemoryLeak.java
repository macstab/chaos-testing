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
 * <p>Simulates a Netty/gRPC off-heap ByteBuffer exhaustion: direct memory is allocated via NIO
 * {@code ByteBuffer.allocateDirect()} without a {@code Cleaner} reference, preventing GC
 * reclamation. Once the direct memory limit is reached every new NIO or Netty allocation fails.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: DirectBufferPressure allocating {@code totalMb} MB total in {@code bufferSizeMb} MB
 *       chunks, retained without Cleaner (leak mode)
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Heap is completely clean; the pod responds normally to liveness probes; every new NIO, Netty, or
 * gRPC operation that requires a direct buffer throws {@code OutOfMemoryError: Direct buffer
 * memory}.
 *
 * <h2>Industry references</h2>
 *
 * <p>Netty direct memory leaks: a common pattern in services using Netty, gRPC-Java, or Kafka
 * clients where reference counting bugs or missing buffer releases cause gradual off-heap
 * exhaustion invisible to standard heap monitoring.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosJvmDirectMemoryLeak(totalMb = 512, bufferSizeMb = 4)
 * class DirectMemoryLeakTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmDirectMemoryLeak.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmDirectMemoryLeakComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosJvmDirectMemoryLeak {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Total direct memory to allocate and retain in megabytes. */
  int totalMb() default 256;

  /** Size of each individual direct buffer allocation in megabytes. */
  int bufferSizeMb() default 1;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmDirectMemoryLeak[] value();
  }
}
