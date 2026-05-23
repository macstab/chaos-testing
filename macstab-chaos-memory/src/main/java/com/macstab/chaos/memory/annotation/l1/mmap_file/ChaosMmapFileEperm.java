/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap_file;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * Injects {@code EPERM} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe an operation-not-permitted failure when attempting to
 * establish a file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code EPERM})
 * tuple. The {@code MMAP_FILE} selector intercepts only file-backed {@code mmap} calls (those
 * without {@code MAP_ANONYMOUS}), leaving anonymous allocations unaffected. Compile-time
 * safety: invalid selector/errno combinations have no annotation class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EPERM} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 1,
 *       {@code strerror}: "Operation not permitted".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EPERM} (1); the operation is
 *       structurally disallowed for this process class — retrying without a policy change will
 *       not succeed.</li>
 *   <li>Applications that use file-backed mappings for write-ahead logs, crash-safe journals,
 *       or SST files must fall back to conventional {@code pread}/{@code pwrite} I/O when
 *       {@code EPERM} is received; assert that the fallback path is exercised and produces
 *       correct results.</li>
 *   <li>Assert that the application surfaces a diagnostic that names "Operation not permitted"
 *       and distinguishes it from {@code EACCES} — the two require different remediation paths
 *       (policy change vs credentials change).</li>
 * </ul>
 * Production failure mode: a Kubernetes Pod Security Admission policy change tightens the
 * allowed filesystem types or disables the {@code mmap} operation for a specific volume type;
 * containers that rely on file-backed memory mappings for database or log I/O begin receiving
 * {@code EPERM} on every mapping attempt, causing cascading failures that persist until the
 * policy is reverted or a compatible volume type is configured.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EPERM} for file-backed {@code mmap} when the operation is
 * structurally disallowed regardless of credentials. On Linux this arises from two mechanisms:
 * (1) a noexec filesystem mount causes the kernel to return {@code EPERM} when {@code PROT_EXEC}
 * is requested on a file-backed mapping; (2) a sealed {@code memfd} (created with
 * {@code MFD_ALLOW_SEALING} and subsequently sealed with {@code F_SEAL_WRITE}) causes the kernel
 * to return {@code EPERM} when a write-shared mapping is requested against the sealed fd.
 *
 * <p>The noexec path is significant for processes that use file-backed executable mappings for
 * JIT-compiled code or native library loading from non-standard paths. A database engine that
 * compiles user-defined functions to native code and loads them via memory-mapped files will
 * fail with {@code EPERM} if the working directory is on a noexec-mounted volume. This is
 * increasingly common in containerised environments where tmpfs-backed work directories are
 * mounted with {@code noexec} for security compliance.
 *
 * <p>The sealed-memfd path is relevant for inter-process communication designs that use
 * {@code memfd_create} to pass read-only data between processes with zero copy. If the sealing
 * sequence is applied before the recipient attempts to establish a write-shared mapping, the
 * mapping fails with {@code EPERM}. This is a correct use of the sealing API — but callers must
 * be prepared for {@code EPERM} and fall back to a read-only mapping with explicit copy semantics
 * if write sharing was intended.
 *
 * <p>Compared with {@code EACCES}: {@code EPERM} is a structural check (the operation type is
 * disallowed for this process class on this resource, regardless of credentials); {@code EACCES}
 * is a credentials check (the current process does not have the right to access this specific
 * resource in this mode). Both are non-transient; neither resolves on retry without an external
 * policy or configuration change. Recovery for {@code EPERM} requires either remounting the
 * filesystem without {@code noexec} or choosing a different volume type; recovery for
 * {@code EACCES} requires changing file ownership or permissions.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEperm(probability = 0.001)
 * class FileMappingPolicyTest {
 *   @Test
 *   void appHandlesEpermOnFileMappings(RedisConnectionInfo info) {
 *     // verify fallback to pread/pwrite and correct diagnostic with EPERM vs EACCES distinction
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2; policy failures are rare in
 * well-configured environments but surface when security posture is hardened — low probability
 * is sufficient to exercise error-handling and diagnostic quality.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEperm.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.EPERM)
public @interface ChaosMmapFileEperm {

  /**
   * Probability that the fault fires when the rule matches, in the range {@code (0.0, 1.0]}. A
   * value of {@code 1.0} makes every matching call fail; {@code 0.001} fails one call in a
   * thousand. Values outside the range {@code (0.0, 1.0]} are rejected at rule construction time.
   */
  double probability() default 1.0;

  /**
   * Container id to bind this rule to. The value must match the {@code id} attribute of a container
   * annotation (e.g. {@code @RedisStandalone(id = "primary")}) on the same test class. The default
   * empty string {@code ""} applies the rule to every memory-chaos-capable container in the test
   * class. A non-empty id that does not match any declared container causes an {@code
   * ExtensionConfigurationException} at {@code beforeAll}.
   */
  String id() default "";

  /**
   * Policy applied when the active backend cannot honour the libchaos-memory requirement. {@link
   * OnMissingEnv#ERROR} (the default) fails the test class with an {@code
   * ExtensionConfigurationException} at {@code beforeAll}. {@link OnMissingEnv#ABORT} raises a
   * {@code TestAbortedException} instead, which most CI systems report as YELLOW (skipped/aborted)
   * rather than RED (failed), keeping the build clean in environments where libchaos is
   * unavailable.
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMmapFileEperm(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEperm(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEperm[] value();
  }
}
