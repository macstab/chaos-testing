/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L2 chaos scenario annotations for the connection module.
 *
 * <p>This package contains twelve named, pre-tuned {@code @CompositeChaos<X>} annotations covering
 * the canonical connection-level failure modes encountered in cloud-native production systems. Each
 * annotation composes one or more libchaos-net rules via a {@link
 * com.macstab.chaos.core.extension.L2Composer} implementation and ships with industry-canonical
 * documentation, severity classification, and sane defaults.
 *
 * <h2>Scenario catalogue</h2>
 *
 * <table>
 *   <caption>Connection L2 scenarios by severity</caption>
 *   <tr><th>Annotation</th><th>Severity</th><th>What it simulates</th></tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosThunderingHerd}</td>
 *     <td>CRITICAL</td>
 *     <td>ECONNREFUSED blackout followed by synchronised reconnect storm on rule removal</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosConnectionRefused}</td>
 *     <td>SEVERE</td>
 *     <td>ECONNREFUSED on every connect — process crashed, firewall RST, no ready endpoints</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosUnreachableHost}</td>
 *     <td>SEVERE</td>
 *     <td>EHOSTUNREACH — dead route, VM migration, misconfigured security group</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosUnreachableNetwork}</td>
 *     <td>SEVERE</td>
 *     <td>ENETUNREACH — VPC peering loss, BGP route withdrawal, wrong routing table</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosTcpResetStorm}</td>
 *     <td>SEVERE</td>
 *     <td>Inbound data corrupted at rate — middlebox RST injection, NIC CRC errors</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosSocketEphemeralExhaustion}</td>
 *     <td>SEVERE</td>
 *     <td>EADDRNOTAVAIL on bind — ephemeral port range exhausted under connection churn</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosAcceptStorm}</td>
 *     <td>SEVERE</td>
 *     <td>EMFILE on accept — fd leak cascade, ulimit hit during traffic spike</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosSlowDownstream}</td>
 *     <td>MODERATE</td>
 *     <td>Connect latency + intermittent EPIPE — overloaded downstream, GC pause mid-response</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosPortAlreadyInUse}</td>
 *     <td>MODERATE</td>
 *     <td>EADDRINUSE on bind — port collision during rolling restart or bind-before-close</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosSendBufferStarvation}</td>
 *     <td>MODERATE</td>
 *     <td>ENOBUFS on send — NIC tx ring full, container network backpressure, memory pressure</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosPollTimeout}</td>
 *     <td>MODERATE</td>
 *     <td>Spurious poll timeout — hypervisor stall, Istio sidecar latency, scheduler hiccup</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.testpack.CompositeChaosHalfOpenConnection}</td>
 *     <td>MODERATE</td>
 *     <td>ECONNRESET on recv — remote crash without FIN, stateful firewall state-table eviction</td>
 *   </tr>
 * </table>
 *
 * <h2>Prerequisites</h2>
 *
 * <p>All scenarios in this package operate at the syscall level via libchaos-net. The target
 * container <strong>must</strong> be prepared with the libchaos-net {@code .so} before {@code
 * container.start()} — the dynamic loader honours {@code LD_PRELOAD} only at process launch.
 * Annotate the test class with {@code @SyscallLevelChaos(LibchaosLib.NET)}; {@code
 * ChaosTestingExtension} drives preparation automatically.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.connection.testpack;
