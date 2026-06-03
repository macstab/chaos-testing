/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that marks a compound, multi-domain chaos scenario annotation as belonging to
 * the <strong>L3 (Incident)</strong> tier.
 *
 * <p>L3 scenarios compose rules from multiple L2 domains simultaneously — connection, DNS, time,
 * memory, filesystem, JVM — to simulate the compound, cross-cutting failure modes found in real
 * production incidents. Each L3 annotation models a specific named incident pattern (e.g.
 * "Redis Failover Storm", "JDBC Connection Pool Exhaustion under WAL Pressure").
 *
 * <p><strong>Tier progression:</strong>
 * <pre>
 * L1  {@code @Chaos<Op><Errno>}             — 1 syscall primitive
 * L2  {@code @CompositeChaos<Name>}         — 1 domain, 1–5 L1 rules
 * L3  {@code @IncidentChaos<Stack><Name>}   — N domains, named incident
 * </pre>
 *
 * <p><strong>Usage (on a scenario annotation):</strong>
 * <pre>{@code
 * @Retention(RetentionPolicy.RUNTIME)
 * @Target({ElementType.TYPE, ElementType.METHOD})
 * @ChaosL3(
 *     composer = "com.macstab.chaos.redis.testpack.l3.composers.RedisFailoverStormComposer",
 *     severity = Severity.CRITICAL)
 * public @interface IncidentChaosRedisFailoverStorm { … }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ChaosL3 {

  /**
   * Fully-qualified class name of the {@link L3Composer} implementation responsible for
   * translating this annotation into concrete chaos rules across multiple domains.
   * The class must have a public no-arg constructor.
   *
   * @return FQN of the composer class
   */
  String composer();

  /**
   * Severity classification for this incident scenario. Defaults to {@link Severity#CRITICAL}
   * because compound multi-domain incidents almost always have critical blast radius.
   *
   * @return severity; default CRITICAL
   */
  Severity severity() default Severity.CRITICAL;
}
