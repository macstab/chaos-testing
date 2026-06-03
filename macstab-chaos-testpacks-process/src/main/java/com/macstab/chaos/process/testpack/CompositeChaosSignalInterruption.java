/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Every {@code waitpid()} call fails with {@code EINTR} at the configured probability,
 * simulating the call being interrupted by a signal before any child changes state. This bypasses
 * the kernel's {@code SA_RESTART} logic — no real signal is delivered — which means applications
 * that rely on {@code SA_RESTART} to silently restart interrupted syscalls will surface latent
 * bugs in their EINTR-handling code paths.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.WAITPID, ProcessErrno.EINTR, toxicity)}
 * via libchaos-process. In production {@code EINTR} from {@code waitpid} occurs when a signal
 * (typically {@code SIGCHLD}, {@code SIGHUP}, or {@code SIGUSR1}) arrives while the parent is
 * blocked in wait. Applications must wrap {@code waitpid} in a loop that retries on {@code EINTR}
 * — any code that treats {@code EINTR} as a permanent failure will silently stop reaping children.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Mild</strong><br>
 * At {@code toxicity = 0.3} about a third of wait calls are interrupted. Well-implemented retry
 * loops absorb these transparently. The scenario exercises the retry path without causing
 * sustained failures — the service degrades only if its EINTR-retry logic is absent or broken.
 * A correctly-implemented service shows no user-visible impact.
 *
 * <h2>Industry references</h2>
 *
 * <p>The requirement to restart {@code waitpid} on {@code EINTR} is documented in POSIX.1-2017
 * (System Interfaces, {@code waitpid}) and in W. Richard Stevens, <em>Advanced Programming in
 * the UNIX Environment</em> (3rd ed.), §10.5: "Interrupted System Calls". The pattern of
 * languages and runtimes silently swallowing EINTR rather than retrying is a well-known source
 * of latent bugs in UNIX systems programming.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosSignalInterruption(toxicity = 0.3)
 * class SignalInterruptionTest {
 *   @Test
 *   void waitpidEintrIsRetriedTransparently() {
 *     // assert: child eventually reaped; no zombie accumulation; EINTR not surfaced to caller
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosSignalInterruption.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.SignalInterruptionComposer",
    severity = Severity.MILD)
public @interface CompositeChaosSignalInterruption {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code EINTR} fires on each {@code waitpid()} call.
   * Defaults to {@code 0.3} (about one in three wait calls is interrupted).
   */
  double toxicity() default 0.3;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosSignalInterruption[] value();
  }
}
