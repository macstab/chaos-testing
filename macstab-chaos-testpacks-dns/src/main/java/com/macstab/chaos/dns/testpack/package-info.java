/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L2 chaos scenario annotations for the DNS module.
 *
 * <p>This package contains nine named, pre-tuned {@code @CompositeChaos<X>} annotations covering
 * the canonical DNS failure modes encountered in cloud-native production systems. Each annotation
 * composes one or more libchaos-dns rules via a {@link com.macstab.chaos.core.extension.L2Composer}
 * implementation and ships with industry-canonical documentation, severity classification, and sane
 * defaults.
 *
 * <h2>Scenario catalogue</h2>
 *
 * <table>
 *   <caption>DNS L2 scenarios by severity</caption>
 *   <tr><th>Annotation</th><th>Severity</th><th>What it simulates</th></tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosDnsCachePoisoning}</td>
 *     <td>CRITICAL</td>
 *     <td>BGP hijack / Kaminsky-style poisoning — all lookups silently rewritten</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosNxDomain}</td>
 *     <td>SEVERE</td>
 *     <td>NXDOMAIN (EAI_NONAME) — zone delegation break, CDN edge resolver outage</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosDnsBlackhole}</td>
 *     <td>SEVERE</td>
 *     <td>EAI_FAIL — firewall policy drop, split-horizon REFUSED</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosDnsServiceRedirection}</td>
 *     <td>SEVERE</td>
 *     <td>Service-token rewrite — corrupted service registry, port alias divergence</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosDnsTemporaryFailure}</td>
 *     <td>MODERATE</td>
 *     <td>SERVFAIL (EAI_AGAIN) — overloaded resolver, DNSSEC validation chain break</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosDnsTimeout}</td>
 *     <td>MODERATE</td>
 *     <td>8-second resolver latency — resolver under load, UDP loss forcing retransmit</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosIpv6OnlyResolution}</td>
 *     <td>MODERATE</td>
 *     <td>IPv4 answers stripped — IPv6-only network migration, NAT gateway failure</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosShuffledAnswerOrder}</td>
 *     <td>MILD</td>
 *     <td>Random answer order — round-robin DNS, Route 53 weighted routing</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.dns.testpack.CompositeChaosReverseDnsFailure}</td>
 *     <td>MILD</td>
 *     <td>Reverse lookup EAI_NONAME — no PTR records in cloud VPC</td>
 *   </tr>
 * </table>
 *
 * <h2>Prerequisites</h2>
 *
 * <p>All scenarios in this package require a container prepared with libchaos-dns before {@code
 * container.start()}. Annotate the test class with {@code @SyscallLevelChaos(LibchaosLib.DNS)};
 * {@code ChaosTestingExtension} drives preparation automatically.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.dns.testpack;
