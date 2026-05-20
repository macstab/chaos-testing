/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for libchaos-time. One annotation per (selector x errno) tuple for ERRNO, one
 * per selector for LATENCY, plus the unique CLOCK_GETTIME-only OFFSET effect. The TimeClock
 * qualifier (REALTIME / MONOTONIC / etc.) is not exposed at the L1 tier — for per-clock targeting
 * use the imperative AdvancedTimeChaos API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.time.annotation.l1;
