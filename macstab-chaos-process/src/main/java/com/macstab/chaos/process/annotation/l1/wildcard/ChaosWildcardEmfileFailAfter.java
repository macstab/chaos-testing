/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

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
 * After {@link #successesBeforeFailure} successful process-management syscall invocations across
 * all intercepted families, injects {@code EMFILE} on every subsequent call, modelling the
 * per-process fd table exhaustion threshold where an fd-leak causes all process-management
 * operations to fail with "Too many open files" after N successful ones.
 *
 * <h2>What this annotation is</h2>
 *
 * L1 libchaos-process primitive — one (selector = {@code WILDCARD}, errno = {@code EMFILE}, effect
 * = FAIL_AFTER) tuple. FAIL_AFTER is the counter-gated effect: the first N intercepted
 * process-management calls (across all families — fork, execve, posix_spawn, pthread_create,
 * waitpid) succeed, then the counter trips permanently and every subsequent call returns the error
 * code until the rule is removed. Compile-time safety: invalid selector/errno/effect combinations
 * have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-process.so} before the container process starts,
 *       interposing every process-management libc wrapper at the dynamic-linker level.
 *   <li>The interposer maintains a per-rule success counter shared across all intercepted syscall
 *       families; the counter does not reset automatically between test methods when the annotation
 *       is at class scope.
 *   <li>Once the counter reaches zero it trips permanently: every subsequent process-management
 *       call returns {@code -1} (or the errno value directly for pthread_create and posix_spawn)
 *       with {@code errno = EMFILE}.
 *   <li>The calling code receives: {@code fork()}/{@code posix_spawn()} return {@code -1} with
 *       {@code errno = EMFILE} (24); {@code pthread_create} returns {@code EMFILE} directly; {@code
 *       strerror(EMFILE)}: "Too many open files".
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The first {@link #successesBeforeFailure} process-management calls proceed normally; all
 *       subsequent calls return EMFILE permanently; assert that the application triggers an
 *       in-process fd audit ({@code /proc/self/fd} inventory) when EMFILE starts, identifies the
 *       source of the fd leak, and closes the leaked descriptors before retrying.
 *   <li>FAIL_AFTER models the fd-leak accumulation threshold: N process-management operations
 *       succeed while a slow fd-leak accumulates; at call N+1 the per-process fd table fills; all
 *       subsequent process-management calls return EMFILE — assert that the application detects the
 *       fd table saturation and does not retry without closing at least one fd first.
 *   <li>Assert that EMFILE is distinguished from ENFILE: EMFILE is fixable in-process by closing
 *       leaked fds and auditing RLIMIT_NOFILE; ENFILE requires platform-level intervention to raise
 *       {@code fs.file-max}; the recovery action differs and the error log must make the
 *       distinction explicit.
 * </ul>
 *
 * Production failure mode: a connection pool leaks one fd per connection cycle; after N cycles the
 * per-process fd table fills; all fork and pthread_create calls start returning EMFILE; the pool's
 * EMFILE handler does not audit {@code /proc/self/fd} and does not close any fds before retrying;
 * the retry loop returns EMFILE indefinitely while the container stops serving requests.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>The WILDCARD FAIL_AFTER counter charges across all process-management families. The EMFILE
 * phase begins when the combined traffic exhausts the counter, simulating the moment when the
 * fd-leak rate causes the fd table to fill after exactly N operations. Set {@link
 * #successesBeforeFailure} to the expected number of process-management calls before the leak
 * causes the threshold to be crossed.
 *
 * <p>In-process EMFILE recovery: (1) inventory {@code /proc/self/fd} to count open descriptors; (2)
 * identify leaked descriptors by type (sockets, pipes, files) and close the oldest or lowest-
 * priority ones; (3) retry the failed operation. The retry must close at least one fd before
 * retrying — otherwise EMFILE will fire again immediately. Applications that retry without closing
 * any fds loop indefinitely. The wildcard variant tests whether this recovery pattern is correctly
 * implemented across all process-management paths simultaneously.
 *
 * <p>The counter does not reset between test methods at class scope. First test method: N
 * successful calls (normal operation with slow leak). Subsequent test methods: EMFILE phase (fd
 * table full, recovery required). Set {@link #successesBeforeFailure} to the number of calls
 * expected before the leak causes saturation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEmfileFailAfter(successesBeforeFailure = 200)
 * class FdLeakExhaustionTest {
 *   @Test
 *   void allProcessManagementPathsAuditFdsAndCloseLeakedDescriptorsOnEmfile(ConnectionInfo info) {
 *     // first 200 process calls succeed; subsequent calls return EMFILE;
 *     // verify /proc/self/fd audit triggered; verify leaked fds identified and closed;
 *     // verify retry succeeds after fd closure; verify EMFILE vs ENFILE classification
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Threshold guidance:</strong> set {@link #successesBeforeFailure} to the number of
 * process-management calls expected before the fd-leak reaches saturation; values 50–500 cover
 * typical leak rates and workload volumes; 0 means the fd table is full from the first call.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every process-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ProcessFailAfterBinding
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Repeatable(ChaosWildcardEmfileFailAfter.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.EMFILE)
public @interface ChaosWildcardEmfileFailAfter {

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
   * @ChaosWildcardEmfileFailAfter(id = "primary",  successesBeforeFailure = 64)
   * @ChaosWildcardEmfileFailAfter(id = "replica",  successesBeforeFailure = 128)
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
    ChaosWildcardEmfileFailAfter[] value();
  }
}
