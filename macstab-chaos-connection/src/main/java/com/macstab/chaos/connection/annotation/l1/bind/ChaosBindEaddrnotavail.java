/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.bind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Injects {@code EADDRNOTAVAIL} into {@code bind(2)}, causing the call to return {@code -1} with
 * {@code errno = EADDRNOTAVAIL} as if the requested local address is not assigned to any network
 * interface on the host and therefore cannot be used as a binding address.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code BIND}, errno =
 * {@code EADDRNOTAVAIL}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each
 * intercepted {@code bind} call; when it fires the interposer returns {@code -1} with
 * {@code errno = EADDRNOTAVAIL} without performing any real kernel operation. No runtime
 * operation-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.NET)} on the container definition causes the
 *       extension to upload {@code libchaos-net.so} into the container and prepend it to
 *       {@code LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code connect}, {@code accept}, {@code socket},
 *       {@code bind}, {@code listen}, {@code shutdown}, {@code send}, {@code recv}, and
 *       {@code poll} at the dynamic-linker level.
 *   <li>On each intercepted {@code bind} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer returns {@code -1} and sets
 *       {@code errno = EADDRNOTAVAIL}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Server startup fails if the service is configured to bind to a specific IP address that is
 *       not present on any local interface; assert that the startup log identifies the missing
 *       address rather than reporting a generic bind failure.
 *   <li>Services configured to bind to a specific container IP address will fail after a container
 *       restart if the IP address assignment changes; assert that the service detects this condition
 *       and refreshes its address configuration rather than entering a crash loop.
 *   <li>Distinguish {@code EADDRNOTAVAIL} (address not on any interface) from {@code EADDRINUSE}
 *       (address exists but is already bound); assert that the application applies different
 *       recovery strategies: waiting for a conflict to resolve versus reconfiguring the address.
 *   <li>Assert that monitoring alerts fire when the service fails to bind to its configured address,
 *       since this typically indicates a network provisioning problem rather than an application bug.
 * </ul>
 *
 * <p>In production, {@code EADDRNOTAVAIL} on {@code bind} occurs when a service is configured to
 * bind to a specific IP address that has been removed from the interface (e.g., during floating-IP
 * failover, network reconfiguration, or container re-scheduling to a host with a different IP range),
 * or when the service configuration specifies an address on a VLAN that the container's network
 * namespace does not include.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The kernel returns {@code EADDRNOTAVAIL} from {@code bind} when the {@code sin_addr} field of
 * the address structure specifies an IP address that is not assigned to any network interface in the
 * process's network namespace. Binding to {@code INADDR_ANY} (0.0.0.0) or {@code IN6ADDR_ANY_INIT}
 * (::) always succeeds because the kernel selects the source address at connection time; only
 * explicit IP addresses trigger this check.
 *
 * <p>Container orchestration systems assign IP addresses dynamically; a service that stores its bind
 * address in a configuration file or environment variable will fail after re-scheduling to a host
 * with a different subnet. This is particularly common in Kubernetes when a pod with
 * {@code hostNetwork: true} is moved to a different node. The pod's configuration specifies the
 * previous node's IP, which is not available on the new node.
 *
 * <p>Java's {@code ServerSocket} maps {@code EADDRNOTAVAIL} to a {@code BindException} with the
 * message "Cannot assign requested address". Applications that catch {@code BindException} and
 * retry without re-reading the configured address will retry indefinitely without resolving the
 * underlying cause. This injection verifies that the application re-reads its address configuration
 * on retry or escalates to a startup failure that triggers operator intervention.
 *
 * <p>The distinction from {@code EADDRINUSE} is significant for recovery: {@code EADDRINUSE} can
 * sometimes be resolved by waiting for TIME_WAIT to expire or for the conflicting process to exit,
 * while {@code EADDRNOTAVAIL} requires a network-level change (adding the address to an interface)
 * that the application cannot perform. Applications that apply the same retry-and-wait strategy to
 * both errors will hang indefinitely on {@code EADDRNOTAVAIL}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosBindEaddrnotavail(toxicity = 0.001)
 * class BindEaddrnotavailTest {
 *   @Test
 *   void serverDetectsMissingInterfaceAddressAndFailsFast(ConnectionInfo info) {
 *     // assert that startup fails with a clear message identifying the missing address
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosBindEaddrinuse
 * @see ChaosBindEinval
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosBindEaddrnotavail.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.BIND, errno = Errno.EADDRNOTAVAIL)
public @interface ChaosBindEaddrnotavail {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
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
   * @ChaosBindEaddrnotavail(id = "primary",  probability = 0.001)
   * @ChaosBindEaddrnotavail(id = "replica",  probability = 0.01)
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
    ChaosBindEaddrnotavail[] value();
  }
}
