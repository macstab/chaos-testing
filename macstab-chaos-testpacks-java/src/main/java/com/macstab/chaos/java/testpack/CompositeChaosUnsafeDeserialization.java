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
 * <p>Injects an {@code InvalidClassException} into {@code ObjectInputStream.readObject()} at
 * probability {@link #probability()}, simulating a corrupt or tampered serialised payload that the
 * JVM's deserialization layer rejects during the class-resolution phase.
 *
 * <h2>How it's created</h2>
 *
 * <p>Intercepts {@code OBJECT_DESERIALIZE} operations via the JVM chaos agent and injects a {@code
 * java.io.InvalidClassException}. In production, deserialization failures occur from bit-flipped
 * data in transit, version mismatches between sender and receiver, or deliberately crafted payloads
 * that trigger gadget chains (CVE-2015-4852, Apache Commons Collections).
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Java deserialization is an extremely high-risk operation. Successful deserialization of malicious
 * payloads leads to arbitrary code execution. This scenario validates that the application handles
 * deserialization failures cleanly without exposing internal state or crashing without explanation.
 *
 * <h2>Industry references</h2>
 *
 * <p>OWASP Top 10 2021: A08 "Software and Data Integrity Failures" covers insecure deserialization.
 * NIST NVD CVE-2015-4852 (Commons Collections deserialization RCE) is the canonical example.
 * Oracle's "Java Object Serialization Specification" §3.7 documents the security implications.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @JvmAgentChaos
 * @CompositeChaosUnsafeDeserialization(probability = 0.5)
 * class DeserializationFailureTest {
 *   @Test
 *   void applicationRejectsCorruptSerializedPayload(ConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosUnsafeDeserialization.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.java.testpack.composers.UnsafeDeserializationComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosUnsafeDeserialization {

  /**
   * Probability in {@code (0.0, 1.0]} that a {@code readObject()} throws {@code
   * InvalidClassException}.
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
    CompositeChaosUnsafeDeserialization[] value();
  }
}
