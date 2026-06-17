/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.poll;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.connection.annotation.l1.recv.ChaosRecvEtimedout;
import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;

/**
 * Overrides the {@code timeout} argument of every intercepted {@code poll(2)} call with {@link
 * #timeoutMs}, causing the call to return {@code 0} (no events ready) after at most {@link
 * #timeoutMs} milliseconds even when the application passed a longer or infinite timeout.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code POLL}, effect = TIMEOUT
 * OVERRIDE) tuple. Unlike errno variants, this primitive does not return {@code -1}; instead it
 * replaces the caller-supplied timeout with {@link #timeoutMs} and delegates the modified call to
 * the real kernel. A Bernoulli trial with probability {@link #toxicity} gates whether the timeout
 * override fires on each call. When it does not fire, the original timeout value is passed
 * unchanged to the kernel.
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
 *   <li>On each intercepted {@code poll} call a Bernoulli trial with probability {@link #toxicity}
 *       is conducted; when it fires the interposer replaces the {@code timeout} argument with
 *       {@link #timeoutMs} before issuing the real kernel call.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>Servers that use infinite or very long poll timeouts (blocking {@code poll(-1)}) to wait
 *       for the next client request will periodically return early with zero events; assert that
 *       the server's main loop correctly handles the zero-event timeout return and re-enters the
 *       poll without treating the early return as an error.
 *   <li>Application-level heartbeat or keep-alive logic that relies on {@code poll} blocking for a
 *       configured duration may send keep-alives more frequently than intended when poll returns
 *       early; assert that the keep-alive logic is not driven solely by poll timeout expiry.
 *   <li>Command pipelines and request-response protocols that use {@code poll} to implement
 *       per-request timeouts will experience unexpectedly short effective timeouts; assert that the
 *       protocol layer retries correctly on zero-event poll returns.
 *   <li>Redis's blocking command implementation ({@code BLPOP}, {@code BRPOP}) uses {@code poll}
 *       with the configured command timeout; this injection causes blocking commands to return
 *       early without data, testing that clients handle the null response correctly.
 * </ul>
 *
 * <p>In production, spurious zero-return poll results occur when a signal is delivered to the
 * polling thread (POSIX requires poll to return on signal delivery, which may return 0 if no
 * readiness events occurred), when a real-time clock rollback causes the timeout to appear expired
 * prematurely, and during kernel debugging where kprobes introduce delays that interact with
 * timeout accounting.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>POSIX specifies that {@code poll} returns {@code 0} when the timeout expires before any of the
 * monitored file descriptors become ready, and a positive value equal to the number of ready
 * descriptors otherwise. A return of {@code 0} is not an error; it is a normal indication that the
 * timeout elapsed. Applications must be prepared to receive {@code 0} from {@code poll} at any
 * time, including when they pass a long timeout, because signals can interrupt the wait.
 *
 * <p>This injection tests a subtler contract than error injection: it verifies that application
 * logic driven by {@code poll} is correct when the timeout fires unexpectedly early. Applications
 * that assume poll will block for at least as long as the requested timeout may have bugs that only
 * manifest when the system is under signal load or when the timeout is shorter than expected.
 *
 * <p>The injected {@link #timeoutMs} value replaces the application's timeout before the kernel
 * call; from the kernel's perspective the poll was requested with a short timeout. The returned
 * value may be {@code 0} (if no events occurred within {@link #timeoutMs}) or positive (if events
 * occurred before the shortened timeout expired). In either case, the poll did not error and the
 * application must handle the result correctly.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosPollTimeout(timeoutMs = 10, toxicity = 0.1)
 * class PollTimeoutTest {
 *   @Test
 *   void serverMainLoopHandlesSpuriousZeroPollReturns(ConnectionInfo info) {
 *     // assert that the server re-enters poll on zero return and continues serving requests
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosPollLatency
 * @see ChaosRecvEtimedout
 */
@Repeatable(ChaosPollTimeout.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator =
        "com.macstab.chaos.connection.annotation.l1.translators.ConnectionTimeoutTranslator")
public @interface ChaosPollTimeout {

  /**
   * @return timeout to enforce on every match, in milliseconds (strictly positive)
   */
  long timeoutMs() default 5000L;

  /**
   * @return per-call match probability, in {@code (0.0, 1.0]}
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
   * @ChaosPollTimeout(id = "primary",  probability = 0.001)
   * @ChaosPollTimeout(id = "replica",  probability = 0.01)
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
    ChaosPollTimeout[] value();
  }
}
