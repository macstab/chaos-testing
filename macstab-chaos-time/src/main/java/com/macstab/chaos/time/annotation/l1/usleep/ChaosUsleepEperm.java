/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.annotation.l1.usleep;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.time.annotation.l1.TimeErrnoBinding;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeSelector;

/**
 * Injects {@code EPERM} into {@code usleep(3)}, causing the call to return {@code -1} with {@code
 * errno = EPERM} as if the process lacked permission to use the underlying sleep mechanism.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (selector = {@code USLEEP}, errno = {@code EPERM})
 * tuple. The tuple is safe by construction — {@code EPERM} is a valid POSIX error indicating that a
 * privileged operation was denied. No runtime selector-errno validation is needed.
 *
 * <h2>What chaos this applies</h2>
 *
 * <ol>
 *   <li>{@code @SyscallLevelChaos(LibchaosLib.TIME)} on the container definition causes the
 *       extension to upload {@code libchaos-time.so} into the container and prepend it to {@code
 *       LD_PRELOAD} before the process starts.
 *   <li>The shared library interposes {@code clock_gettime}, {@code nanosleep}, and {@code usleep}
 *       at the dynamic-linker level.
 *   <li>On every intercepted {@code usleep} call a Bernoulli trial with probability {@link
 *       #probability} is conducted.
 *   <li>When the trial fires the interposer returns {@code -1} and sets {@code errno = EPERM}
 *       without sleeping — the sleep is denied immediately.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>The sleep is skipped; callers without comprehensive error handling will proceed without
 *       back-off, potentially overwhelming downstream services or entering a busy-loop.
 *   <li>Code paths that treat any non-zero return as an {@code EINTR} case and immediately retry
 *       will loop indefinitely when the error is {@code EPERM}.
 *   <li>Assert that the application treats {@code EPERM} as a non-retriable error, logs the denial,
 *       and applies a safe static fallback delay.
 * </ul>
 *
 * <p>In production, {@code EPERM} from {@code usleep} is an unusual signal; it most commonly
 * appears in seccomp-filtered environments that block the underlying {@code nanosleep} syscall, or
 * in mandatory access control environments that restrict sleep operations.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>Standard Linux kernels do not return {@code EPERM} from {@code nanosleep} (the backend of
 * {@code usleep}) for ordinary processes. The injection simulates the behavior of stricter access
 * control environments and seccomp profiles that block the sleep syscall. Code that uses {@code
 * usleep} without considering permission failures will expose this as a production gap when
 * deployed to hardened container environments.
 *
 * <p>C libraries such as libcurl, librdkafka, and OpenSSL use {@code usleep} internally in their
 * retry backoff logic. A seccomp profile that blocks {@code nanosleep} on a container where these
 * libraries are used will cause their retry loops to fail silently, producing retry storms.
 *
 * <p>Sibling annotations: {@link ChaosUsleepEintr} targets signal interruption; {@link
 * ChaosNanosleepEperm} applies the equivalent injection to the modern interface.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.TIME)
 * @ChaosUsleepEperm(probability = 0.01)
 * class UsleepEpermTest {
 *   @Test
 *   void clientLibraryAppliesStaticFallbackOnPermissionDenied(ConnectionInfo info) {
 *     // assert that the library does not enter an unbound busy-loop on EPERM
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosUsleepEintr
 * @see ChaosNanosleepEperm
 * @see com.macstab.chaos.time.annotation.l1.TimeErrnoBinding
 */
@Repeatable(ChaosUsleepEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.time.annotation.l1.translators.TimeErrnoTranslator")
@TimeErrnoBinding(selector = TimeSelector.USLEEP, errno = TimeErrno.EPERM)
public @interface ChaosUsleepEperm {

  /**
   * @return probability the errno fires when matched, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-time
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosUsleepEperm(id = "primary",  probability = 0.001)
   * @ChaosUsleepEperm(id = "replica",  probability = 0.01)
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
    ChaosUsleepEperm[] value();
  }
}
