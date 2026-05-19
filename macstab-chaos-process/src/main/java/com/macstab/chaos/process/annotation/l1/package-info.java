/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for libchaos-process. One annotation per legal (selector x errno x effect)
 * tuple per {@link com.macstab.chaos.process.model.ProcessSelector#validErrnos()}. Grouped by
 * selector into sub-packages so IDE autocomplete shows only the relevant family.
 *
 * <p>Three effect kinds covered (libchaos-process has the widest grammar of any libchaos library):
 *
 * <ul>
 *   <li><strong>ErrnoFault</strong> ({@code Chaos<Selector><Errno>}) — probabilistic injection
 *   <li><strong>Latency</strong> ({@code Chaos<Selector>Latency}) — unconditional pre-call delay
 *   <li><strong>FailAfter</strong> ({@code Chaos<Selector><Errno>FailAfter}) — counter-gated, the
 *       libchaos-process unique effect modeling resource exhaustion
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.process.annotation.l1;
