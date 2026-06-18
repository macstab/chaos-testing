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
 * <p>Allocates and retains direct (off-heap) NIO byte buffers totalling {@link #targetMb()} MB,
 * exhausting the JVM's direct-buffer memory limit and causing subsequent {@code
 * ByteBuffer.allocateDirect()} calls to throw {@code OutOfMemoryError: Direct buffer memory}.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code DIRECT_BUFFER} stressor via the JVM chaos agent. The stressor allocates
 * direct buffers in fixed-size chunks and retains strong references, preventing GC-driven
 * reclamation via {@code Cleaner}. In production, direct-buffer leaks occur when NIO channels,
 * Netty ByteBuf instances, or mapped files are not explicitly released and the GC does not run fast
 * enough to trigger their finalisers.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Once the direct-buffer limit is exhausted, all further NIO operations that require a direct
 * buffer fail immediately. Netty-based services, gRPC servers, and Kafka clients are particularly
 * sensitive. Recovery requires either a GC cycle (which may not happen soon enough) or a restart.
 *
 * <h2>Industry references</h2>
 *
 * <p>Direct buffer memory is governed by {@code -XX:MaxDirectMemorySize}. When unset, the limit
 * defaults to {@code -Xmx}. Netty's documentation on off-heap pooled allocator discusses the leak
 * patterns that lead to direct-buffer exhaustion.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosDirectBufferLeak(targetMb = 256)
 * class DirectBufferLeakTest {
 *   @Test
 *   void applicationHandlesDirectBufferOom(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosDirectBufferLeak.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.DirectBufferLeakComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosDirectBufferLeak {

  /**
   * Total direct buffer memory to allocate and retain, in megabytes.
   *
   * @return target in MB; default 256
   */
  int targetMb() default 256;

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
    CompositeChaosDirectBufferLeak[] value();
  }
}
