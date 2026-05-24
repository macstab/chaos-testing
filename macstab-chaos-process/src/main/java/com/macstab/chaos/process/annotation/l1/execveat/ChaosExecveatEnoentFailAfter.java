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
 * After {@link #successesBeforeFailure} successful {@code execveat} calls, injects {@code ENOENT}
 * on every subsequent call, causing the calling code to observe a no-such-file failure that
 * persists for the remainder of the test.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code EXECVEAT}, errno = {@code ENOENT}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N calls succeed, then the
 * counter trips permanently and every subsequent call fails until the rule is removed. This is
 * distinct from ERRNO (independent Bernoulli trial on each call) and LATENCY (unconditional delay).
 * Compile-time safety: invalid selector/errno/effect combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing the libc {@code execveat} wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter. Each {@code execveat} call that passes
 *       the counter check decrements the remaining budget; the counter does not reset automatically
 *       between test methods when the annotation is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent {@code execveat} call
 *       sets {@code errno = ENOENT} and returns {@code -1} without issuing the real kernel call.
 *   <li>The calling code receives: {@code -1} return, {@code errno} 2, {@code strerror}: "No such
 *       file or directory"; the {@code dirfd} must be closed by the caller to avoid an fd leak
 *       since no close-on-exec processing occurs for failed execs.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} calls proceed normally; all subsequent calls
 *       return {@code -1} with {@code errno = ENOENT}; assert that the application closes the
 *       {@code dirfd} on every failure and does not cache the "binary present" state that was valid
 *       during the success phase.
 *   <li>Container runtimes that cache directory fds for repeated {@code execveat} calls must handle
 *       the case where the directory's relative path no longer resolves — assert that the runtime
 *       invalidates the cached dirfd on {@code ENOENT} and retries the full path-open-then-exec
 *       sequence rather than reusing the stale dirfd.
 *   <li>Assert that the application's error message for post-threshold {@code ENOENT} includes both
 *       the dirfd number and the relative pathname (if not using {@code AT_EMPTY_PATH}) so
 *       operators can identify which directory and binary path are affected without kernel tracing
 *       tools.
 * </ul>
 *
 * Production failure mode: a container runtime caches a directory fd pointing to the container's
 * rootfs overlay; a rolling deployment updates the overlay layer during the exec sequence; after N
 * successful launches the binary path no longer exists in the new overlay, and all subsequent
 * {@code execveat} calls return {@code ENOENT}; the runtime leaks the cached dirfd and does not
 * re-attempt a fresh path resolution, causing all subsequent container launches to fail.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>FAIL_AFTER is the correct model for volume-unmount and rolling-deployment scenarios where the
 * binary disappears abruptly after a number of successful exec calls. Real {@code ENOENT} from
 * {@code execveat} in these scenarios is not probabilistic — it occurs once and remains until the
 * deployment stabilises or the volume is remounted. The ERRNO variant fires with probability p on
 * each call independently, which cannot model the "succeed N times, then fail permanently" pattern
 * that rolling deployments produce.
 *
 * <p>The {@code AT_EMPTY_PATH} flag changes the {@code ENOENT} semantics: when the flag is set and
 * the pathname is empty, the {@code dirfd} itself is the executable and no path resolution occurs —
 * {@code ENOENT} cannot come from path resolution in this mode. However, if the {@code dirfd}
 * refers to an inode that was unlinked after being opened (the "deleted but open" pattern used by
 * container runtimes for TOCTOU safety), some kernel versions may return {@code ENOENT} from the
 * exec's internal checks even though the file descriptor is valid. FAIL_AFTER with threshold N
 * tests whether the application handles this transition correctly.
 *
 * <p>Applications that cache directory fds for performance must implement cache invalidation on
 * {@code ENOENT}: the dirfd that produced N successful exec calls may produce {@code ENOENT} on the
 * (N+1)th call if the directory's content was updated or the inode was unlinked. Failing to
 * invalidate the cache means the application enters a permanent failure loop with no recovery path,
 * since retrying with the same stale dirfd will never succeed.
 *
 * <p>The dirfd fd leak risk is amplified under FAIL_AFTER: each of the first N successes closes the
 * dirfd via {@code FD_CLOEXEC} (on exec success). After the counter trips, every subsequent call
 * opens the dirfd, receives {@code ENOENT} from the interposer, and must explicitly close the dirfd
 * in the error path. Applications that omit this close accumulate one leaked fd per failed exec
 * attempt, which can exhaust {@code RLIMIT_NOFILE}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosExecveatEnoentFailAfter(successesBeforeFailure = 20)
 * class ExecveatBinaryDisappearsTest {
 *   @Test
 *   void runtimeInvalidatesDirfdCacheAndClosesDirfdOnEnoentAfterThreshold(ConnectionInfo info) {
 *     // first 20 execveat calls succeed; subsequent calls return ENOENT;
 *     // verify dirfd closed on each failure; cache invalidated; full path re-resolution attempted
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the observed
 * number of successful launches before the deployment window opens; values in the range 5–100 cover
 * most rolling-deployment scenarios; 0 means the binary is missing from the first launch (cold
 * deployment error), exercising the early-startup failure path.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosExecveatEnoentFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.EXECVEAT, errno = ProcessErrno.ENOENT)
public @interface ChaosExecveatEnoentFailAfter {

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
   * @ChaosExecveatEnoentFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosExecveatEnoentFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosExecveatEnoentFailAfter[] value();
  }
}
