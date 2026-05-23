/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.execveat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * After {@link #successesBeforeFailure} successful {@code execveat} calls, injects {@code E2BIG}
 * on every subsequent call, causing the calling code to observe an argument-list-too-large failure
 * that persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code E2BIG}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call fails until the rule is removed. This is
 * distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional delay).
 * Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.</li>
 *   <li>The interposer maintains a per-rule success counter. Each {@code execveat} call that
 *       passes the counter check decrements the remaining budget; the counter does not reset
 *       automatically between test methods when the annotation is at class scope.</li>
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code execveat} call
 *       sets {@code errno = E2BIG} and returns {@code -1} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code -1} return, {@code errno} 7,
 *       {@code strerror}: "Argument list too long"; the {@code dirfd} must be closed by the
 *       caller to avoid an fd leak since no close-on-exec processing occurs for failed execs.</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = E2BIG}; assert that the counter threshold aligns
 *       with the application's expected exec budget so the failure fires at a predictable point
 *       in the test scenario.</li>
 *   <li>Container runtimes using {@code execveat} with {@code AT_EMPTY_PATH} must close the open
 *       {@code dirfd} in the {@code E2BIG} error path — assert that every exec failure closes
 *       the dirfd before propagating the error, since failed execs do not trigger
 *       {@code FD_CLOEXEC} processing on the dirfd.</li>
 *   <li>FAIL_AFTER is more accurate than probabilistic ERRNO for modelling environment variable
 *       accumulation across Kubernetes admission webhook chains: each webhook layer adds headers,
 *       tracing context, and sidecar env vars; the cumulative argument vector is well below
 *       {@code ARG_MAX} for the first N launches but crosses the limit deterministically after
 *       enough webhook transformations — assert that the runtime's argument pruning logic or
 *       error surface activates at the expected call count, not at a random trial.</li>
 * </ul>
 * Production failure mode: a container runtime uses {@code execveat(AT_EMPTY_PATH)} to launch
 * sidecar containers injected by admission webhooks; each webhook layer adds environment variables
 * ({@code OTEL_*}, {@code AWS_*}, service-mesh metadata); after N container launches the
 * cumulative environment size crosses {@code ARG_MAX}, all subsequent launches fail with
 * {@code E2BIG}, and the runtime's error path leaks the open {@code dirfd} it prepared for exec.
 *
 * <h2>Deep technical dive</h2>
 * <p>The FAIL_AFTER counter models the progressive accumulation of environment variables across
 * a series of exec calls more accurately than a probabilistic ERRNO. In real {@code E2BIG} incidents,
 * the failure is not random — it occurs when cumulative environment inflation from CI/CD tooling,
 * service-mesh sidecars, and observability agents pushes the argument vector past {@code ARG_MAX}
 * (128 KiB on Linux by default for the sum of argv + envp + their NUL terminators). The Nth exec
 * succeeds; the (N+1)th fails. Setting {@link #successesBeforeFailure} to the observed number of
 * execs before failure reproduces this deterministic threshold exactly.
 *
 * <p>The {@code execveat} context adds a layer of complexity absent from plain {@code execve}:
 * the caller must open a {@code dirfd} before calling exec. Under FAIL_AFTER, the first N calls
 * open and exec correctly (dirfd closed by kernel on success with {@code FD_CLOEXEC}). After the
 * counter trips, every subsequent call opens the dirfd, then receives {@code E2BIG} from the
 * interposer — the kernel never sees the call, so no close-on-exec processing occurs, and the
 * dirfd remains open. Applications that omit an explicit {@code close(dirfd)} in the {@code E2BIG}
 * error path leak one fd per failed exec, progressively exhausting {@code RLIMIT_NOFILE} and
 * creating a compounding failure where the argument-size problem is followed by an fd-exhaustion
 * problem.
 *
 * <p>The counter does not reset between test methods when the annotation is placed at class scope.
 * This is intentional: it allows a test suite to exercise the "first N succeed" phase in early
 * test methods and the "all subsequent fail" phase in later methods without re-deploying the
 * container. When per-method isolation is needed, place the annotation at method scope so the
 * counter re-initialises before each test.
 *
 * <p>Contrast with {@code ChaosExecveatE2big} (ERRNO, probabilistic): the ERRNO variant fires
 * with probability {@code p} on each call independently, making it suited for testing transient
 * argument-size failures under load. FAIL_AFTER is suited for testing what happens when an
 * application reaches a resource ceiling deterministically — the failure boundary that real
 * production incidents produce when environment accumulation crosses {@code ARG_MAX}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatE2bigFailAfter(successesBeforeFailure = 50)
 * class ExecveatArgAccumulationTest {
 *   @Test
 *   void runtimeClosesDirfdAndReportsArgSizeOnE2bigAfterThreshold(ConnectionInfo info) {
 *     // first 50 execveat calls succeed; subsequent calls return E2BIG;
 *     // verify dirfd closed on each failure; E2BIG error surfaced with arg size context
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to match the
 * application's observed exec count before environment accumulation exceeds {@code ARG_MAX} in
 * production; values in the range 10–200 cover most container-launch scenarios; 0 means the
 * very first exec fails, which tests cold-start failure handling.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container
 * in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveatE2bigFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.E2BIG)
public @interface ChaosExecveatE2bigFailAfter {

  /**
   * @return number of matched calls allowed to succeed before failure begins ({@code >= 0})
   */
  long successesBeforeFailure() default 0L;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-process
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosExecveatE2bigFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveatE2bigFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveatE2bigFailAfter[] value();
  }
}
