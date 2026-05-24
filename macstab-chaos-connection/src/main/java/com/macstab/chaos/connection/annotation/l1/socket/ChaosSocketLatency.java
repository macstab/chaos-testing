/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.socket;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Delays every {@code socket(2)} call by an additional {@link #delayMs} milliseconds before
 * delegating to the real kernel call, making socket creation slower than the application expects
 * while still returning a valid socket file descriptor.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code SOCKET}, effect = LATENCY)
 * tuple. Unlike errno variants, the latency primitive always delegates to the real kernel call
 * after the configured extra delay — the socket is created normally. A Bernoulli trial with
 * probability {@link #toxicity} gates whether the delay fires on each call. No runtime
 * operation-effect validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket}, {@code
 *       bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and {@code poll} at
 *       the dynamic-linker level.
 *   <li>On each intercepted {@code socket} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer sleeps for {@link #delayMs} ms before
 *       issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Connection establishment takes longer: because {@code socket} is the first step in
 *       establishing a connection, the injected delay adds directly to the total connection latency
 *       before {@code connect} is even called. Assert that the application's connection timeout
 *       encompasses both socket creation time and the subsequent TCP handshake.
 *   <li>Connection pools that create sockets on demand under load will see increased connection
 *       acquisition latency during pool expansion; assert that the pool's connection acquisition
 *       timeout is calibrated to accommodate slow socket creation.
 *   <li>Server-side accept paths that call {@code socket} to create new client sockets (less common
 *       — typically the kernel creates sockets via {@code accept}) are not affected; the injection
 *       targets the explicit {@code socket(2)} calls used for outbound connections.
 *   <li>Assert that the application does not time out socket creation in isolation — few
 *       applications set a timeout specifically on the {@code socket} call, so the delay will
 *       typically accumulate into the broader connection timeout.
 * </ul>
 *
 * <p>In production, slow {@code socket} calls occur when the process is CPU throttled by cgroups
 * and waits to be scheduled before entering the kernel, when the kernel's slab allocator for socket
 * structures is under memory pressure and must reclaim pages before satisfying the allocation, and
 * when a seccomp filter applies complex rules that add filtering latency to each syscall.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The {@code socket(2)} syscall is normally one of the fastest socket operations: it allocates
 * kernel memory for the socket structure, initializes protocol-specific state, allocates a file
 * descriptor, and installs the socket in the process's file descriptor table. Under non-pressured
 * conditions this takes microseconds. The injected delay is added before the syscall, so the total
 * socket creation time observed by the caller is {@link #delayMs} plus the actual kernel allocation
 * time.
 *
 * <p>The primary use case for this injection is to test connection pool behavior during slow socket
 * creation: when the pool needs to expand to handle a traffic burst, all new connection creation
 * paths are delayed by the injected amount. This can cause connection acquisition timeouts before
 * connections are established, and can reveal whether the pool correctly differentiates between
 * "socket creation is slow" and "the server is unreachable".
 *
 * <p>Java's {@code Socket} constructor calls {@code socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)} (or
 * the IPv6 equivalent) internally. For NIO, {@code SocketChannel.open()} calls the same syscall.
 * Both paths are intercepted by libchaos-net. The delay is imperceptible in normal unit tests where
 * connections are established once at setup time, but becomes significant in tests that create many
 * short-lived connections or that measure connection establishment time.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosSocketLatency(delayMs = 50, toxicity = 0.5)
 * class SocketLatencyTest {
 *   @Test
 *   void connectionPoolAcquisitionTimeoutAccountsForSlowSocketCreation(ConnectionInfo info) {
 *     // assert that acquisition timeout fires at the right threshold including socket creation time
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosSocketEmfile
 * @see ChaosSocketEnomem
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionLatencyBinding
 */
@Repeatable(ChaosSocketLatency.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionLatencyTranslator")
@ConnectionLatencyBinding(operation = NetOperation.SOCKET)
public @interface ChaosSocketLatency {

  /**
   * @return latency to apply on every match, in milliseconds (non-negative)
   */
  long delayMs() default 100L;

  /**
   * @return probability the latency fires when matched, in {@code (0.0, 1.0]}
   */
  double toxicity() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-net
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosSocketLatency(id = "primary",  probability = 0.001)
   * @ChaosSocketLatency(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosSocketLatency[] value();
  }
}
