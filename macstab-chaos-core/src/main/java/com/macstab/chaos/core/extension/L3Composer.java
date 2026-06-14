/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import java.lang.annotation.Annotation;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

/**
 * Translates an L3 incident scenario annotation into concrete multi-domain chaos rules, applies
 * them to a target container, and knows how to remove them.
 *
 * <p>Identical contract to {@link L2Composer} but used by L3 annotations (those meta-annotated with
 * {@link ChaosL3}). L3 composers compose rules from multiple domain APIs simultaneously — e.g.
 * {@code NetRule} + {@code DnsRule} + {@code TimeRule} — to produce a realistic, compound
 * production-incident simulation.
 *
 * <p><strong>Lifecycle contract:</strong>
 *
 * <ol>
 *   <li>{@link #apply} is called once per container match at {@code beforeAll} or {@code
 *       beforeEach}. It must apply all rules and return opaque handles for later removal.
 *   <li>{@link #removeAll} is called at {@code afterAll} or {@code afterEach} with the same
 *       handles. It must remove every rule that {@code apply} installed, even if some removals fail
 *       (best-effort, log and continue).
 *   <li>{@link #describe} may be called at any time for logging; it must be side-effect-free.
 * </ol>
 *
 * <p><strong>Implementation requirements:</strong>
 *
 * <ul>
 *   <li>Must be stateless — one instance may be reused across multiple containers and test classes.
 *   <li>Must have a public no-arg constructor (reflectively instantiated by {@link
 *       L3AnnotationProcessor}).
 *   <li>The {@code handles} list returned by {@code apply} is stored opaquely; use {@code
 *       instanceof} dispatch in {@code removeAll} to recover typed handles.
 * </ul>
 *
 * @param <A> the L3 incident scenario annotation type this composer handles
 * @author Christian Schnapka - Macstab GmbH
 */
public interface L3Composer<A extends Annotation> {

  /**
   * Applies the incident scenario described by {@code annotation} to {@code container}.
   *
   * @param container the running target container (guaranteed non-null and started)
   * @param annotation the L3 annotation instance carrying scenario parameters
   * @return opaque handles representing every rule installed; passed back verbatim to {@link
   *     #removeAll} — must not be {@code null}, may be empty
   * @throws IllegalArgumentException if annotation attribute values are invalid
   * @throws Exception if rule installation fails (wrapped in {@code
   *     ExtensionConfigurationException} by the processor)
   */
  List<Object> apply(GenericContainer<?> container, A annotation);

  /**
   * Removes every rule that was installed by a prior {@link #apply} call.
   *
   * <p>Each removal attempt should be wrapped in try/catch so a single domain failure does not
   * prevent cleanup of the remaining domains.
   *
   * @param container the container the rules were applied to
   * @param handles the opaque list returned by the matching {@code apply} call
   */
  void removeAll(GenericContainer<?> container, List<Object> handles);

  /**
   * Returns human-readable lines describing what this incident scenario applies and why. Used for
   * logging ({@code INFO}/{@code DEBUG}) and the {@link ChaosApplicationReport}.
   *
   * <p>Typical structure: first line names the incident; subsequent lines name each domain
   * component and its tunable parameters; last line states the severity and impact class.
   *
   * @param annotation the annotation instance to introspect for parameter values
   * @return non-null, non-empty list of description lines (at least one)
   */
  List<String> describe(A annotation);
}
