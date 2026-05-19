/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for libchaos-dns. Covers the two universally-compatible effect kinds
 * (EaiFault and Latency) across the three wildcard selector kinds (forward, reverse, any).
 * Per-host and per-IP targeting, plus the richer effects (REWRITE, SERVICE, OVERRIDE,
 * FILTER_FAMILY, LIMIT, SHUFFLE), are intentionally out of the L1 surface — for those
 * scenarios use the imperative AdvancedDnsChaos API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.dns.annotation.l1;
