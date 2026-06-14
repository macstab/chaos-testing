/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.testpack;

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
 * <p>A single bit is flipped in the buffer returned by {@code read()} after libc returns
 * successfully. The corruption is applied with a very low probability ({@code toxicity=0.01}) — 1
 * in 100 reads — to match real-world silent data corruption rates on failing storage hardware.
 *
 * <h2>How it's created</h2>
 *
 * <p>Injects an {@code IoRule.corrupt(path, READ, 0.01)} via libchaos-io. The corruption is applied
 * post-syscall: libc returns success, but one bit in the returned buffer is flipped before the
 * application observes the data. In production, this models DRAM bit-flip (soft-ECC error), bad
 * NVMe sector returning wrong data, or storage controller firmware bug.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Critical</strong><br>
 * Silent data corruption is the hardest failure mode to detect. The application receives a
 * successful read with subtly wrong data. Without end-to-end checksums (CRC, SHA, etc.) the
 * corruption propagates silently into downstream stores. Data loss and silent state divergence
 * between replicas are the typical consequences.
 *
 * <h2>Industry references</h2>
 *
 * <p>Barroso et al. (Google, USENIX FAST 2008) measured DRAM error rates of 1–8 errors per gigabyte
 * per year in production fleets. Checksum-less storage layers (pre-ext4, certain SSDs) can return
 * wrong data without an I/O error; POSIX makes no guarantee of payload integrity on {@code read()}
 * success. Netflix Chaos Engineering explicitly tests for this via read-corruption faults in their
 * storage resilience suite.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * @CompositeChaosReadCorruption(path = "/data/store")
 * class ReadCorruptionTest {
 *
 *   @Test
 *   void checksumDetectsCorruption(FilesystemChaos chaos, GenericContainer<?> svc) {
 *     // Application must detect and reject bit-flipped reads via its own integrity layer
 *     assertThatThrownBy(() -> svc.execInContainer("verify-checksum", "/data/store/snapshot"))
 *         .hasMessageContaining("checksum mismatch");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosReadCorruption.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.filesystem.testpack.composers.ReadCorruptionComposer",
    severity = Severity.CRITICAL)
public @interface CompositeChaosReadCorruption {

  /**
   * Path prefix on which {@code read()} buffer corruption is applied. Must be an absolute path
   * (start with {@code /}). Defaults to {@code "*"} — wildcard matching every path.
   */
  String path() default "*";

  /**
   * Probability that a matched read has one bit flipped in its returned buffer. Very low values
   * (the default {@code 0.01} = 1%) match real-world hardware corruption rates.
   */
  double toxicity() default 0.01;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-io.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosReadCorruption[] value();
  }
}
