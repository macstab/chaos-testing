/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Two-tier DNS chaos: iptables/CoreDNS coarse verbs plus libchaos-dns resolver-boundary precision.
 *
 * <h2>Two backends, one facade</h2>
 *
 * <p>{@link com.macstab.chaos.dns.CompositeDnsChaos} aggregates two strategies behind a unified
 * {@link com.macstab.chaos.core.api.DnsChaos} interface.
 *
 * <table>
 *   <caption>Backend comparison</caption>
 *   <tr>
 *     <th></th>
 *     <th>{@link com.macstab.chaos.dns.strategy.iptables.IptablesDnsChaos IptablesDnsChaos}</th>
 *     <th>{@link com.macstab.chaos.dns.strategy.libchaos.LibchaosDnsChaos LibchaosDnsChaos}</th>
 *   </tr>
 *   <tr>
 *     <td><strong>Mechanism</strong></td>
 *     <td>iptables REJECT on port 53 + CoreDNS sidecar + {@code /etc/resolv.conf} tamper</td>
 *     <td>{@code LD_PRELOAD} hook of {@code getaddrinfo()} and {@code getnameinfo()}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Installation</strong></td>
 *     <td>Lazy — needs {@code NET_ADMIN} capability and a CoreDNS install</td>
 *     <td>Pre-start — must be wired before {@code container.start()}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Granularity</strong></td>
 *     <td>Per-hostname binary on/off via REJECT or NXDOMAIN/SERVFAIL/REFUSED hosts files</td>
 *     <td>Per-host/per-resolver-call × eight effect kinds × forward/reverse</td>
 *   </tr>
 *   <tr>
 *     <td><strong>EAI palette</strong></td>
 *     <td>NXDOMAIN, SERVFAIL, REFUSED via fake hosts files</td>
 *     <td>5 codes: {@code EAI_AGAIN}, {@code EAI_FAIL}, {@code EAI_NONAME}, {@code EAI_MEMORY},
 *         {@code EAI_SYSTEM}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Post-resolution transforms</strong></td>
 *     <td>None</td>
 *     <td>{@code FILTER_FAMILY}, {@code LIMIT}, {@code SHUFFLE} on the {@code addrinfo} list</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Override answer</strong></td>
 *     <td>Via {@code /etc/hosts} rewrite</td>
 *     <td>{@code OVERRIDE} synthesises a fabricated answer list</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Reverse lookup</strong></td>
 *     <td>Not supported</td>
 *     <td>{@code rdns://} selectors hook {@code getnameinfo()}</td>
 *   </tr>
 * </table>
 *
 * <h2>Capability uplift via {@code advanced()}</h2>
 *
 * <p>{@link com.macstab.chaos.dns.CompositeDnsChaos#advanced()} returns the libchaos-dns strategy
 * as a {@link com.macstab.chaos.dns.api.AdvancedDnsChaos}. This exposes verbs the iptables backend
 * cannot model:
 *
 * <ul>
 *   <li>{@code failResolution} / {@code failReverse} — inject any of 5 EAI codes
 *   <li>{@code slowResolution} / {@code slowReverse} — latency on forward or reverse
 *   <li>{@code rewriteHost} / {@code rewriteReverseHost} — pre-resolution name rewrite
 *   <li>{@code overrideAnswer} — fabricate the address list returned by {@code getaddrinfo()}
 *   <li>{@code filterFamily} — drop IPv4 or IPv6 answers
 *   <li>{@code limitAnswers} — keep only the first N answers
 *   <li>{@code shuffleAnswers} — randomise the answer-list order
 * </ul>
 *
 * <h2>Verb-to-backend routing</h2>
 *
 * <p>Unlike the filesystem and connection composites, the DNS composite does <em>not</em> use
 * capability fall-through: libchaos-dns handles both portable verbs natively, so the composite
 * routes purely by {@code supports()}. Strategies declared earlier in the constructor list win when
 * both are applicable; {@link com.macstab.chaos.dns.CompositeDnsChaos#standard()} puts libchaos-dns
 * first.
 *
 * <h2>Pre-flight requirements</h2>
 *
 * <p>Advanced verbs require the container to be prepared with libchaos-dns <em>before</em> {@code
 * container.start()}. Annotate the test class with {@code @SyscallLevelChaos(LibchaosLib.DNS)};
 * {@code ChaosTestingExtension} reads the annotation and drives {@code LibchaosTransport.prepare()}
 * into the pre-start window.
 *
 * <p>Without preparation, advanced verbs raise {@link
 * com.macstab.chaos.core.exception.LibchaosNotPreparedException} loudly at the call site — there is
 * no silent fallback.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/DNS.md">libchaos-dns
 *     technical reference</a>
 */
package com.macstab.chaos.dns;
