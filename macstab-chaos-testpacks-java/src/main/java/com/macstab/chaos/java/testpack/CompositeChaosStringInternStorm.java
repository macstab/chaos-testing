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
 * <p>Interacts {@link #stringCount()} unique strings into the JVM string pool, gradually exhausting
 * the interned-string table and causing the GC to spend increasing time scanning the weak reference
 * table that backs the pool.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies a {@code STRING_INTERN_PRESSURE} stressor via the JVM chaos agent. The stressor
 * generates and interns large numbers of unique strings, permanently pinning them in the JVM's
 * string table. In production, runaway interning occurs in frameworks that intern arbitrary
 * user-supplied strings (e.g. XML attribute names, HTTP header values) without bounding the intern
 * table size.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Moderate</strong><br>
 * The interned string table is a permanent GC root; large tables extend every GC pause. With
 * enough interned strings, the GC's weak-reference scanning phase dominates pause time. The table
 * cannot be shrunk without a JVM restart.
 *
 * <h2>Industry references</h2>
 *
 * <p>JDK bug JDK-6962742 (String.intern() performance degrades with large pools) documents the
 * hash-collision cost in the native string table. The fix in Java 7u40 improved the hash function
 * but the fundamental O(n) GC scanning cost remains.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosStringInternStorm(stringCount = 100000)
 * class StringInternStormTest {
 *   @Test
 *   void gcPausesRemainBoundedUnderStringInternPressure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosStringInternStorm.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.StringInternStormComposer",
    severity = Severity.MODERATE)
public @interface CompositeChaosStringInternStorm {

  /**
   * Number of unique strings to intern.
   *
   * @return string count; default 100000
   */
  int stringCount() default 100_000;

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
    CompositeChaosStringInternStorm[] value();
  }
}
