/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1.stressors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Exhausts the JVM's string intern table (string pool) by interning a large number of synthetic
 * strings that are never released, simulating frameworks or code that incorrectly intern arbitrary
 * user-supplied strings into the permanent string pool.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>A JVM agent stressor L1 primitive. Unlike interceptor primitives, stressors do not intercept a
 * specific JVM operation — they spawn a self-driving background routine that runs from activation
 * ({@code beforeAll} or {@code beforeEach}) until cleanup ({@code afterAll} or {@code afterEach}).
 * The stressor generates {@link #internCount()} unique synthetic strings, each of length {@link
 * #stringLengthBytes()} bytes, and calls {@code String.intern()} on each, adding them to the JVM's
 * global string table where they remain until the JVM exits or a GC cycle unloads the class loader
 * that owns them (which, for interned strings, is never).
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>The stressor generates {@link #internCount()} distinct strings of {@link
 *       #stringLengthBytes()} characters. Each string is unique (e.g. formed from a UUID or a
 *       sequential index) to ensure it is not already in the pool.
 *   <li>Each string is passed to {@code String.intern()}, which looks up the string in the JVM's
 *       native string table (a hash table in native memory, not the Java heap) and adds it if not
 *       present. The returned reference points to the canonical pooled instance.
 *   <li>The stressor does not retain Java-heap references to the interned strings (the intern table
 *       itself holds a strong reference via native memory). The strings cannot be GC-collected as
 *       long as they are in the pool.
 *   <li>The string table grows by {@link #internCount()} entries. On HotSpot, the string table is a
 *       fixed-size hashtable ({@code -XX:StringTableSize}) with open addressing; once it fills,
 *       each subsequent {@code intern()} call must traverse longer chains, causing {@code intern()}
 *       latency to grow linearly with the number of entries.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li><strong>Increased {@code String.intern()} latency.</strong> Application code that calls
 *       {@code intern()} (e.g. XML parsers, serialisation frameworks, annotation processors) will
 *       experience growing latency as the string table fills; assert that string interning does not
 *       appear on the critical request path or that its latency is bounded.
 *   <li><strong>Native memory growth.</strong> The string table lives in native memory (not the
 *       Java heap); interned strings accumulate there. On containerised environments with tight
 *       total memory limits, native memory growth can trigger an OOM kill; assert that the
 *       container's native memory usage is monitored and bounded.
 *   <li><strong>GC overhead from scanning the string table.</strong> The GC must scan the string
 *       table during full GC to determine which strings are still referenced by Java code (from
 *       class constant pools, etc.); a large string table increases full-GC pause time; assert that
 *       full-GC pauses remain within SLA.
 *   <li><strong>{@code -XX:StringTableSize} saturation.</strong> If the string table's bucket count
 *       ({@code StringTableSize}, default 65536 in Java 11+) is insufficient for {@link
 *       #internCount()} entries, collision chains grow and every {@code intern()} call becomes a
 *       linear search; assert that the application's throughput degrades predictably rather than
 *       hanging.
 *   <li><strong>Production failure mode:</strong> legacy XML parsers (SAX, Stax) and some ORM
 *       frameworks call {@code String.intern()} on element names, attribute names, and enumeration
 *       values read from user-supplied documents. When an attacker or a misconfigured client sends
 *       documents with many unique element names, the intern table grows without bound and the
 *       JVM's native memory is exhausted.
 * </ul>
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The JVM string intern table ({@code StringTable} in HotSpot source) is a concurrent hash table
 * stored in native memory. It maps the hash and content of a string to a weak reference to the
 * canonical Java {@code String} object on the heap. The weak reference allows the GC to collect
 * interned strings if no Java code holds a strong reference to them — but in practice, class
 * constant pools hold strong references to all string literals, and code that uses {@code
 * String.intern()} typically assigns the result to a variable, so interned strings are almost never
 * collected.
 *
 * <p>The table uses open addressing with a fixed bucket count ({@code -XX:StringTableSize}). When
 * the load factor exceeds roughly 70%, lookup performance degrades as collision chains lengthen.
 * HotSpot resizes the table on some GC cycles, but the resize is limited in scope; under heavy
 * intern pressure, performance degradation accumulates faster than resizing compensates.
 *
 * <p>The total native memory consumed by interned strings is approximately {@code internCount *
 * (stringLengthBytes * 2 + 48)} bytes (assuming UTF-16 storage, which Java uses internally), plus
 * table metadata overhead. With {@code internCount = 100,000} and {@code stringLengthBytes = 64},
 * total consumption is roughly 15 MB of native memory — enough to be significant on containers with
 * 256 MB limits.
 *
 * <p>Modern Java frameworks avoid {@code String.intern()} in hot paths in favour of other
 * canonicalisation approaches (e.g. interning via a {@code ConcurrentHashMap}). This stressor is
 * therefore most relevant for testing applications that depend on legacy libraries or JDK classes
 * that still use the string pool aggressively.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @ChaosStringInternPressure(internCount = 50_000, stringLengthBytes = 128)
 * class StringInternExhaustionTest {
 *   @Test
 *   void xmlParserRemainsResponsiveUnderInternTablePressure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><strong>{@code @JvmAgentChaos}</strong> on the container annotation — attaches the chaos
 *       agent before the container JVM starts; omitting it causes an {@code
 *       ExtensionConfigurationException} at {@code beforeAll}.
 *   <li><strong>Chaos agent JAR</strong> accessible at the path configured in
 *       {@code @JvmAgentChaos}.
 *   <li><strong>{@code macstab-chaos-java} on the test classpath</strong> — required for the
 *       translator.
 *   <li><strong>Java container image</strong> — the target must run a JVM; the agent cannot
 *       intercept native executables.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Repeatable(ChaosStringInternPressure.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.jvm.annotation.l1.translators.StringInternPressureTranslator")
public @interface ChaosStringInternPressure {

  /**
   * @return number of strings to intern (> 0)
   */
  int internCount() default 100_000;

  /**
   * @return per-string length in bytes (> 0)
   */
  int stringLengthBytes() default 64;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the JVM agent is not active on the container
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosStringInternPressure(id = "primary",  probability = 0.001)
   * @ChaosStringInternPressure(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosStringInternPressure[] value();
  }
}
