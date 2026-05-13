/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Two-tier time chaos: libfaketime wall-clock manipulation plus libchaos-time libc-symbol
 * precision, including the unique per-clock {@code OFFSET} effect for surfacing wall-vs-monotonic
 * skew bugs.
 *
 * <h2>Two backends, one facade</h2>
 *
 * <p>{@link com.macstab.chaos.time.CompositeTimeChaos} aggregates two strategies behind a unified
 * {@link com.macstab.chaos.core.api.TimeChaos} interface.
 *
 * <table>
 *   <caption>Backend comparison</caption>
 *   <tr>
 *     <th></th>
 *     <th>{@link com.macstab.chaos.time.LibfaketimeTimeChaos LibfaketimeTimeChaos}</th>
 *     <th>{@link com.macstab.chaos.time.strategy.libchaos.LibchaosTimeChaos
 *       LibchaosTimeChaos}</th>
 *   </tr>
 *   <tr>
 *     <td><strong>Mechanism</strong></td>
 *     <td>{@code LD_PRELOAD} libfaketime + timestamp-file driven {@code +/-Ns} / {@code x<factor>}
 *         offsets</td>
 *     <td>{@code LD_PRELOAD} hook of {@code clock_gettime} / {@code nanosleep} / {@code
 *         usleep}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Installation</strong></td>
 *     <td>Pre-start via {@code enableDynamicTime()} + apt/apk install of {@code libfaketime}</td>
 *     <td>Pre-start — {@code @SyscallLevelChaos(LibchaosLib.TIME)}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Granularity</strong></td>
 *     <td>Whole container — global wall-clock offset / speed multiplier</td>
 *     <td>Per libc-symbol × per errno × per probability OR per-clock {@code OFFSET}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Test capability</strong></td>
 *     <td>"Container thinks it's tomorrow", "Time runs 2x faster"</td>
 *     <td>"clock_gettime returns EINVAL", "monotonic clock jumps -1.5s while wall is untouched",
 *         "nanosleep returns EINTR (bypassing SA_RESTART)", "usleep takes an extra 200ms"</td>
 *   </tr>
 * </table>
 *
 * <h2>Capability uplift via {@code advanced()}</h2>
 *
 * <p>{@link com.macstab.chaos.time.CompositeTimeChaos#advanced()} returns the libchaos-time
 * strategy as an {@link com.macstab.chaos.time.api.AdvancedTimeChaos}. This exposes typed verbs
 * grouped by syscall:
 *
 * <ul>
 *   <li><strong>clock_gettime</strong> — {@code failClockGet}, {@code failClockGetWithErrno},
 *       {@code slowClockGet}, {@code skewClock(TimeClock, Duration)}
 *   <li><strong>nanosleep</strong> — {@code failNanosleep}, {@code slowNanosleep}, {@code
 *       signalInterruptSleep}
 *   <li><strong>usleep</strong> — {@code failUsleep}, {@code slowUsleep}, {@code
 *       signalInterruptMicrosleep}
 *   <li><strong>Raw escape hatches</strong> — {@code errno}, {@code latency}, {@code offset}
 *   <li><strong>Generic</strong> — {@code apply}, {@code applyAll}, {@code remove}, {@code
 *       removeAll}
 * </ul>
 *
 * <h2>The per-clock OFFSET unique capability</h2>
 *
 * <p>libchaos-time is the only library that lets a test skew <em>one</em> clock without touching
 * the others. {@code OFFSET:-1500} attached to {@code clock_gettime/monotonic} shifts
 * {@code CLOCK_MONOTONIC} reads 1.5s into the past while {@code CLOCK_REALTIME},
 * {@code CLOCK_BOOTTIME}, and the wall-clock timestamp seen by libfaketime stay correct. This is
 * the canonical surface for finding latent assumptions that wall-clock and monotonic time advance
 * in lockstep — they do not in production after suspend, NTP correction, or clock-source
 * migration.
 *
 * <p><strong>Effect compatibility:</strong> {@code OFFSET} is rejected by the libchaos-time C
 * parser on every selector except {@code clock_gettime} (with or without a clock qualifier). The
 * {@link com.macstab.chaos.time.model.TimeRule} record enforces this at the Java boundary so the
 * error surfaces at the call site, not silently at config-load time.
 *
 * <h2>Verb-to-backend routing</h2>
 *
 * <table>
 *   <caption>Which strategy handles which verb</caption>
 *   <tr><th>Verb</th><th>libchaos-time</th><th>libfaketime</th></tr>
 *   <tr><td>{@code shift} / {@code drift}</td><td>—</td><td>✓</td></tr>
 *   <tr><td>All advanced verbs (failClockGet, skewClock, failNanosleep, …)</td><td>✓</td><td>—</td></tr>
 * </table>
 *
 * <h2>Probability guidance</h2>
 *
 * <p>Many time rules need a low probability to avoid breaking the container itself — {@code
 * clock_gettime:ERRNO:EINVAL@1.0} prevents the JVM from reading any timestamp, {@code
 * nanosleep:ERRNO:EINTR@1.0} stops every sleep, and so on. For sustained chaos use
 * {@code 0.001}–{@code 0.01}; for deterministic single-shot tests use {@code 1.0}. {@code OFFSET}
 * rules are typically safe at probability 1.0 because they do not cause syscall failures.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/TIME.md">libchaos-time
 *     technical reference</a>
 */
package com.macstab.chaos.time;
