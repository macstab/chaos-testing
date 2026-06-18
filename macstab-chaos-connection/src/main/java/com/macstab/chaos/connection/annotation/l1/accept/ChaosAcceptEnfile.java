/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.annotation.l1.accept;

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
 * Injects {@code ENFILE} into {@code accept(2)}, causing the call to return {@code -1} with {@code
 * errno = ENFILE} as if the system-wide open-file limit has been reached and the kernel cannot
 * allocate a new file table entry for the accepted connection.
 *
 * <h2>What this annotation is</h2>
 *
 * <p>L1 libchaos primitive. Encodes exactly one (operation = {@code ACCEPT}, errno = {@code
 * ENFILE}) tuple. A Bernoulli trial with probability {@link #toxicity} is run on each intercepted
 * {@code accept} call; when it fires the interposer returns {@code -1} with {@code errno = ENFILE}
 * without performing any real kernel operation. No runtime operation-errno validation is needed.
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
 *   <li>On every intercepted {@code accept} call a Bernoulli trial with probability {@link
 *       #toxicity} is conducted; when it fires the interposer returns {@code -1} and sets {@code
 *       errno = ENFILE}.
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 *
 * <ul>
 *   <li>{@code ENFILE} is a system-wide limit, not a per-process limit; the server cannot increase
 *       its own fd limit to recover — only reducing the total number of open fds across all
 *       processes on the host can resolve the condition.
 *   <li>Servers must treat {@code ENFILE} as a severe resource-pressure signal and activate
 *       back-pressure mechanisms, such as stopping the accept loop temporarily or sending a 503
 *       response to queued requests.
 *   <li>Assert that the server correctly distinguishes {@code ENFILE} (system-wide) from {@code
 *       EMFILE} (per-process) and applies the appropriate recovery strategy for each.
 *   <li>Assert that the server emits a metric or alert labelled "system file table full" so that
 *       operations teams can identify the host-level cause.
 * </ul>
 *
 * <p>In production, {@code ENFILE} from {@code accept} occurs on densely packed hosts where many
 * processes share the kernel's file table (controlled by {@code /proc/sys/fs/file-max}). It is
 * rarer than {@code EMFILE} but more severe, since it affects all processes on the host
 * simultaneously.
 *
 * <h2>Deep technical dive</h2>
 *
 * <p>{@code ENFILE} is the system-wide counterpart to {@code EMFILE}. The kernel maintains a global
 * file table that stores file descriptions shared across all processes; when this table is full, no
 * process on the host can open new fds regardless of its individual {@code RLIMIT_NOFILE}. The
 * limit is controlled by {@code /proc/sys/fs/file-max} and defaults to a value computed from
 * available memory at boot.
 *
 * <p>In containerised environments, the file table limit is shared between the container and the
 * host kernel (cgroups do not isolate this limit). A container that generates a very high rate of
 * short-lived connections can exhaust the system file table, causing other unrelated processes on
 * the same host to fail to open files. This injection tests the application's handling of that
 * host-wide resource pressure condition without requiring a real host to be stressed.
 *
 * <p>The operational difference between {@code EMFILE} and {@code ENFILE} is significant: on {@code
 * EMFILE} the process can recover by closing its own fds; on {@code ENFILE} no action within the
 * process can resolve the condition. Servers that apply the same recovery strategy to both errors
 * will fail to recover from {@code ENFILE} — this injection makes that logic visible.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.NET)
 * @ChaosAcceptEnfile(toxicity = 0.001)
 * class AcceptEnfileTest {
 *   @Test
 *   void serverEmitsSystemFileLimitAlertOnEnfile(ConnectionInfo info) {
 *     // assert that the server emits a metric labelled "system file table full"
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosAcceptEmfile
 * @see ChaosAcceptEconnreset
 * @see com.macstab.chaos.connection.annotation.l1.ConnectionErrnoBinding
 */
@Repeatable(ChaosAcceptEnfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(
    translator = "com.macstab.chaos.connection.annotation.l1.translators.ConnectionErrnoTranslator")
@ConnectionErrnoBinding(operation = NetOperation.ACCEPT, errno = Errno.ENFILE)
public @interface ChaosAcceptEnfile {

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
   * @ChaosAcceptEnfile(id = "primary",  probability = 0.001)
   * @ChaosAcceptEnfile(id = "replica",  probability = 0.01)
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
    ChaosAcceptEnfile[] value();
  }
}
