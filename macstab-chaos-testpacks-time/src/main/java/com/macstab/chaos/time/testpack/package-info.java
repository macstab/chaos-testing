/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L2 chaos scenario annotations for the time module.
 *
 * <p>This package contains seven named, pre-tuned {@code @CompositeChaos<X>} annotations covering
 * the canonical time-anomaly failure modes encountered in cloud-native production systems. Each
 * annotation composes one or more libchaos-time rules via a
 * {@link com.macstab.chaos.core.extension.L2Composer} implementation and ships with
 * industry-canonical documentation, severity classification, and sane defaults.
 *
 * <h2>Scenario catalogue</h2>
 *
 * <table>
 *   <caption>Time L2 scenarios by severity</caption>
 *   <tr><th>Annotation</th><th>Severity</th><th>What it simulates</th></tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.time.testpack.CompositeChaosFrozenClock}</td>
 *     <td>SEVERE</td>
 *     <td>Clock pinned to Unix epoch — all TTLs, leases and heartbeat timers stop advancing</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.time.testpack.CompositeChaosClockSkew}</td>
 *     <td>MODERATE</td>
 *     <td>CLOCK_REALTIME forward jump — NTP re-sync, VM live-migration burst skew</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.time.testpack.CompositeChaosLeapSecond}</td>
 *     <td>MODERATE</td>
 *     <td>+1000 ms REALTIME offset — IERS leap-second insertion (2012/2016 incident model)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.time.testpack.CompositeChaosTimeTravel}</td>
 *     <td>MODERATE</td>
 *     <td>CLOCK_REALTIME backward jump — NTP step-back correction, VM snapshot restore</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.time.testpack.CompositeChaosTimerCascade}</td>
 *     <td>MODERATE</td>
 *     <td>nanosleep extra latency — CPU-starved scheduler, timer-cascade thundering herd</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.time.testpack.CompositeChaosSlowMonotonic}</td>
 *     <td>MILD</td>
 *     <td>CLOCK_MONOTONIC negative offset — TSC drift, wall-vs-monotonic divergence</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.time.testpack.CompositeChaosNanosleepInterruption}</td>
 *     <td>MILD</td>
 *     <td>nanosleep EINTR injection — signal-interrupted sleep, EINTR retry-loop regression</td>
 *   </tr>
 * </table>
 *
 * <h2>Prerequisites</h2>
 *
 * <p>All scenarios in this package require a container prepared with libchaos-time before
 * {@code container.start()}. Annotate the test class with
 * {@code @SyscallLevelChaos(LibchaosLib.TIME)}; {@code ChaosTestingExtension} drives preparation
 * automatically.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.time.testpack;
