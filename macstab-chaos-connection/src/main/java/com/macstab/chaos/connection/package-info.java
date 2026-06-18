/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Connection-chaos module: outbound TCP/UDP fault injection for testcontainer-managed services.
 *
 * <h2>Two backends, two lifecycles</h2>
 *
 * <p>This module composes two fault-injection mechanisms behind a single {@link
 * com.macstab.chaos.core.api.ConnectionChaos} facade. They differ in capability surface, setup
 * cost, and — critically — in <em>when</em> they have to be installed:
 *
 * <table>
 *   <caption>Backend comparison</caption>
 *   <tr>
 *     <th>Backend</th>
 *     <th>Term</th>
 *     <th>Lifecycle</th>
 *     <th>Setup cost</th>
 *     <th>Required capability</th>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.strategy.toxiproxy.ToxiproxyConnectionChaos}</td>
 *     <td><strong>proxy-level fault injection</strong></td>
 *     <td>applied at runtime against a running container; lazily installed on first verb call</td>
 *     <td>{@code toxiproxy-server} binary fetched from GitHub on demand</td>
 *     <td>{@code NET_ADMIN} for {@code iptables} redirect</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.connection.strategy.libchaos.LibchaosNetConnectionChaos}</td>
 *     <td><strong>syscall-level fault injection</strong></td>
 *     <td><strong>must be installed before {@code container.start()}</strong> via
 *         {@code LD_PRELOAD}</td>
 *     <td>vendored {@code .so}, no network access required</td>
 *     <td>none — runs in-process inside the target container</td>
 *   </tr>
 * </table>
 *
 * <h2>Capability uplift over Toxiproxy</h2>
 *
 * <p>libchaos-net is a strict superset of Toxiproxy's surface <em>except</em> for bandwidth
 * shaping. Specifically, libchaos-net adds:
 *
 * <ul>
 *   <li>per-syscall granularity ({@code socket}, {@code bind}, {@code listen}, {@code connect},
 *       {@code accept}, {@code shutdown}, {@code send}, {@code recv}, {@code poll})
 *   <li>the full POSIX errno palette ({@link com.macstab.chaos.connection.model.Errno})
 *   <li>UDP, unix-domain-socket, and DNS-level injection (the latter intercepting {@code
 *       getaddrinfo})
 *   <li>recv-payload corruption ({@link com.macstab.chaos.connection.model.Effect.Corrupt})
 *   <li>listen/accept-side faults ({@link
 *       com.macstab.chaos.connection.api.AdvancedConnectionChaos#failListen}, {@link
 *       com.macstab.chaos.connection.api.AdvancedConnectionChaos#failAccept})
 *   <li>file-descriptor exhaustion via {@link
 *       com.macstab.chaos.core.exception.LibchaosNotPreparedException Errno#EMFILE} on {@code
 *       socket()}
 * </ul>
 *
 * <h2>Routing inside the composite</h2>
 *
 * <p>{@link com.macstab.chaos.connection.CompositeConnectionChaos} routes per operation. With
 * {@code [LibchaosNetConnectionChaos, ToxiproxyConnectionChaos]} (the {@code standard()}
 * arrangement), capability fall-through means:
 *
 * <table>
 *   <caption>Per-verb routing</caption>
 *   <tr><th>Verb</th><th>Routes to</th></tr>
 *   <tr><td>{@code addLatency}, {@code rejectConnections}, {@code timeoutConnections},
 *       {@code dropPackets}, {@code slowClose}</td><td>libchaos-net</td></tr>
 *   <tr><td>{@code limitBandwidth}</td><td>Toxiproxy (libchaos-net cannot model bandwidth)</td></tr>
 *   <tr><td>{@code removeToxic}, {@code removeAllToxics}</td><td>fan-out to both</td></tr>
 *   <tr><td>{@code advanced().*} (any verb on
 *       {@link com.macstab.chaos.connection.api.AdvancedConnectionChaos})</td>
 *       <td>libchaos-net only — Toxiproxy cannot model these</td></tr>
 * </table>
 *
 * <h2>Pre-flight: opting into syscall-level chaos</h2>
 *
 * <p>If your test only uses portable verbs, no setup is required — Toxiproxy installs lazily on
 * first call.
 *
 * <p>If your test wants the advanced surface, the test class must declare {@link
 * com.macstab.chaos.core.annotation.SyscallLevelChaos @SyscallLevelChaos} so that {@link
 * com.macstab.chaos.core.extension.ChaosTestingExtension ChaosTestingExtension} drives {@code
 * LibchaosTransport.prepare()} into the pre-start window. Skipping this step and then calling
 * {@code chaos.advanced().*} raises {@link
 * com.macstab.chaos.core.exception.LibchaosNotPreparedException} loudly at the call site — there is
 * no silent fallback by design.
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)              // pre-start prep, mandatory for advanced
 * class MyTest {
 *
 *   @Test
 *   void simulatesDnsOutage(RedisConnectionInfo info, ConnectionChaos chaos) {
 *     ((AdvancedConnectionChaos) chaos)
 *         .failDnsResolve(container, "redis.internal", Errno.EHOSTUNREACH, 1.0);
 *     // …
 *   }
 *
 *   @Test
 *   void simpleLatency(RedisConnectionInfo info, ConnectionChaos chaos) {
 *     // Portable verb: routes through libchaos-net automatically (already prepared).
 *     chaos.addLatency(container, "redis.internal:6379", Duration.ofMillis(200));
 *   }
 * }
 * }</pre>
 *
 * <h2>Failure modes worth knowing</h2>
 *
 * <ul>
 *   <li><strong>Container started before prepare()</strong> — libchaos-net inactive; {@code
 *       chaos.advanced().<verb>()} raises {@link
 *       com.macstab.chaos.core.exception.LibchaosNotPreparedException} with the fix in the message.
 *   <li><strong>Distroless / scratch container</strong> — variant resolution fails in {@code
 *       LibchaosTransport.prepare()}; surfaces as {@link
 *       com.macstab.chaos.core.exception.ChaosOperationFailedException} at builder time.
 *   <li><strong>Toxiproxy install fails (offline CI / corp proxy)</strong> — sticky-fail state
 *       machine in {@link com.macstab.chaos.connection.strategy.toxiproxy.ToxiproxyConnectionChaos}
 *       short circuits subsequent calls with a clear message; recovery requires a fresh container.
 *       Tests that only use syscall-level chaos never trigger Toxiproxy install at all.
 *   <li><strong>Container restart mid-test</strong> — {@code LD_PRELOAD} survives (env on launch),
 *       {@code .so} survives (image-layer copy), <em>rules do not</em> (config in {@code /tmp}). No
 *       persistent volume is mounted by design.
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.core.annotation.SyscallLevelChaos
 * @see com.macstab.chaos.connection.api.AdvancedConnectionChaos
 * @see com.macstab.chaos.connection.CompositeConnectionChaos
 */
package com.macstab.chaos.connection;
