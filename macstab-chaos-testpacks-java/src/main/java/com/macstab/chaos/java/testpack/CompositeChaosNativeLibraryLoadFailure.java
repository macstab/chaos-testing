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
 * <p>Causes every {@code System.loadLibrary()} and {@code System.load()} call to throw an {@code
 * UnsatisfiedLinkError}, simulating a missing or incompatible native library (.so/.dll) at the
 * deployment target.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code NATIVE_LIBRARY_LOAD} operations via the JVM chaos agent and injects an
 * {@code UnsatisfiedLinkError} at probability {@link #probability()}. In production, native library
 * load failures occur after cross-platform deployment (e.g. x86 jar on ARM), after an OS upgrade
 * changes shared library paths, or when a JNI library was not included in the deployment artifact.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * A failed native library load in a {@code static} block causes the containing class to become
 * permanently unloadable ({@code NoClassDefFoundError} on subsequent loads). Applications that
 * depend on native acceleration (cryptography, compression, database drivers) will fall back to
 * pure-Java implementations or fail entirely.
 *
 * <h2>Industry references</h2>
 *
 * <p>The JNI specification §2.1 documents the library loading mechanism. Bouncycastle's JCE
 * provider, Netty's native transport, and RocksDB all use JNI and are examples of widely-used
 * libraries sensitive to this failure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosNativeLibraryLoadFailure(probability = 1.0)
 * class NativeLibraryLoadFailureTest {
 *   @Test
 *   void applicationFallsBackToJavaImplementationOnJniFailure(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosNativeLibraryLoadFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.NativeLibraryLoadFailureComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosNativeLibraryLoadFailure {

  /**
   * Probability in {@code (0.0, 1.0]} that a native library load throws {@code
   * UnsatisfiedLinkError}.
   *
   * @return probability; default 1.0
   */
  double probability() default 1.0;

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
    CompositeChaosNativeLibraryLoadFailure[] value();
  }
}
