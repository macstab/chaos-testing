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
 * <p>Intercepts {@code java.util.zip.Inflater} operations and injects extreme expansion ratios,
 * simulating the effect of decompressing a zip-bomb payload — a tiny compressed input that explodes
 * to gigabytes of output, exhausting heap and CPU.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a heap-pressure stressor at the GC level combined with an inflater intercept, causing
 * decompression operations to appear to return much more data than expected. In production, zip
 * bombs appear in file-upload handlers, log decompression pipelines, and HTTP clients that
 * decompress response bodies without bounding the decompressed size.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * An unbounded decompression of a zip-bomb rapidly exhausts heap, triggering continuous full GCs
 * and eventually an OOM. Services that do not limit decompressed content size are trivially
 * vulnerable. This is classified as a resource-exhaustion attack vector in OWASP.
 *
 * <h2>Industry references</h2>
 *
 * <p>OWASP "Denial of Service Cheat Sheet" §Zip Bomb. CVE-2019-11040 (PHP GD zip bomb) and
 * CVE-2022-28389 (Apache HTTP zip bomb bypass) are representative real-world instances.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosZipBomb(probability = 0.5)
 * class ZipBombResilienceTest {
 *   @Test
 *   void fileUploadHandlerRejectsExcessiveDecompression(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosZipBomb.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.ZipBombComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosZipBomb {

  /**
   * Probability in {@code (0.0, 1.0]} that an inflater operation triggers extreme expansion.
   *
   * @return probability; default 0.5
   */
  double probability() default 0.5;

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
    CompositeChaosZipBomb[] value();
  }
}
