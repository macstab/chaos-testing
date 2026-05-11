/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.api;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.api.FilesystemChaos;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

/**
 * Capability-tier interface exposing libchaos-io's syscall-level fault-injection surface.
 *
 * <p><strong>Pre-flight contract.</strong> Every method on this interface requires that the target
 * container has been prepared with libchaos-io <em>before</em> {@code container.start()} — the
 * {@code .so} is hooked via {@code LD_PRELOAD}, which the dynamic loader only honours at process
 * launch. Skipping preparation and then invoking any verb here raises {@link
 * LibchaosNotPreparedException} loudly: there is no silent fallback by design. The sanctioned way
 * to satisfy preparation is the {@code @SyscallLevelChaos(LibchaosLib.IO)} annotation on the test
 * class, which {@code ChaosTestingExtension} reads to drive {@code LibchaosTransport.prepare()}.
 *
 * <p><strong>Capability uplift over {@link FilesystemChaos}.</strong> The portable parent interface
 * ({@code fillDisk}, {@code injectPermissionErrors}) covers the coarse, container-wide shell verbs
 * that need no preparation. This interface adds operations the shell backend literally cannot
 * model: per-path/per-syscall granularity, the full file-errno palette (EIO, ENOSPC, EDQUOT, EROFS,
 * EACCES, EMFILE, ENFILE, ENOENT), torn writes, single-bit read corruption, latency on durability
 * barriers (fsync/fdatasync), and direct {@link IoRule} application.
 *
 * <p><strong>Lifecycle.</strong> Returned {@link RuleHandle}s identify the applied rule for later
 * surgical removal via {@link #remove}. {@link #removeAll} clears every rule this strategy has
 * applied to the container.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.IO)
 * class MyTest {
 *   @Test
 *   void writeAheadLogSurvivesTornWrites(FilesystemChaos chaos, GenericContainer<?> app) {
 *     AdvancedFilesystemChaos adv = (AdvancedFilesystemChaos) chaos;
 *     RuleHandle h = adv.tornWrite(app, "/srv/wal", 0.1);
 *     // ... exercise the application ...
 *     adv.remove(app, h);
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface AdvancedFilesystemChaos extends FilesystemChaos {

  // ==================== Generic rule API ====================

  /**
   * Apply a single libchaos-io rule.
   *
   * @param container target container (must be prepared with libchaos-io)
   * @param rule rule to apply
   * @return handle identifying the rule for later removal
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-io is not active on {@code container}
   */
  RuleHandle apply(GenericContainer<?> container, IoRule rule);

  /**
   * Apply a batch of libchaos-io rules in a single round-trip.
   *
   * <p>Implementations <strong>must</strong> validate every rule before committing any of them
   * (fail-fast semantics). On success, returns one handle per input rule, in the same order.
   *
   * @param container target container (must be prepared with libchaos-io)
   * @param rules non-null, possibly empty list of rules
   * @return list of handles, one per rule, in the same order as {@code rules}; empty when {@code
   *     rules} is empty
   * @throws NullPointerException if any argument or rule is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-io is not active on {@code container}
   */
  List<RuleHandle> applyAll(GenericContainer<?> container, List<IoRule> rules);

  /**
   * Surgically remove a single previously-applied rule.
   *
   * <p>Idempotent — silently no-op if the handle is unknown to this strategy (e.g. already
   * removed).
   *
   * @param container target container (must be prepared with libchaos-io)
   * @param handle handle returned by a previous {@link #apply} or {@link #applyAll} call
   * @throws NullPointerException if any argument is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-io is not active on {@code container}
   */
  void remove(GenericContainer<?> container, RuleHandle handle);

  /**
   * Remove every rule this strategy has applied to {@code container}. Shell-side state (disk-fill
   * files, chmod'd directories) is untouched — use {@link FilesystemChaos#reset} for that.
   *
   * @param container target container (must be prepared with libchaos-io)
   * @throws NullPointerException if {@code container} is {@code null}
   * @throws LibchaosNotPreparedException if libchaos-io is not active on {@code container}
   */
  void removeAll(GenericContainer<?> container);

  // ==================== Convenience verbs ====================

  /**
   * Fail {@code open()} on paths under {@code path} with the given errno.
   *
   * @param container target container
   * @param path path prefix where opens should fail
   * @param errno errno to inject (typically {@link Errno#ENOENT} or {@link Errno#EACCES})
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failOpen(
      GenericContainer<?> container, PathPrefix path, Errno errno, double probability);

  /**
   * Fail {@code write()}/{@code writev()}/{@code sendfile()}/{@code copy_file_range()} on paths
   * under {@code path} with the given errno.
   *
   * @param container target container
   * @param path path prefix where writes should fail
   * @param errno errno to inject (typically {@link Errno#EIO} or {@link Errno#ENOSPC})
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failWrite(
      GenericContainer<?> container, PathPrefix path, Errno errno, double probability);

  /**
   * Fail {@code read()}/{@code readv()} on paths under {@code path} with the given errno.
   *
   * @param container target container
   * @param path path prefix where reads should fail
   * @param errno errno to inject (typically {@link Errno#EIO})
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failRead(
      GenericContainer<?> container, PathPrefix path, Errno errno, double probability);

  /**
   * Simulate file-descriptor exhaustion: {@code open()} returns {@link Errno#EMFILE} on every path
   * at the given probability.
   *
   * @param container target container
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle exhaustFds(GenericContainer<?> container, double probability);

  /**
   * Force the container to observe paths under {@code path} as read-only — {@code open()} for write
   * and every modifying syscall returns {@link Errno#EROFS}.
   *
   * @param container target container
   * @param path path prefix to render read-only
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle makeReadOnly(GenericContainer<?> container, PathPrefix path, double probability);

  /**
   * Simulate quota exhaustion under {@code path}: {@code write()} returns {@link Errno#EDQUOT}.
   *
   * @param container target container
   * @param path path prefix where the quota is exhausted
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle fillQuota(GenericContainer<?> container, PathPrefix path, double probability);

  /**
   * Successful short write — the wrapper reduces the byte count before delegating to libc. Models
   * the case where a {@code write()} returns fewer bytes than requested even though it didn't fail.
   *
   * @param container target container
   * @param path path prefix on which writes can be torn
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle tornWrite(GenericContainer<?> container, PathPrefix path, double probability);

  /**
   * Single-bit corruption of a successful read's returned buffer — applied after libc returns,
   * before the application observes the data.
   *
   * @param container target container
   * @param path path prefix on which reads can be corrupted
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle corruptRead(GenericContainer<?> container, PathPrefix path, double probability);

  /**
   * Add latency to {@code fsync()}/{@code fdatasync()} on paths under {@code path}.
   *
   * @param container target container
   * @param path path prefix whose durability calls should be slowed
   * @param delay non-negative delay
   * @return handle for later removal
   */
  RuleHandle slowFsync(GenericContainer<?> container, PathPrefix path, Duration delay);

  /**
   * Fail {@code fsync()}/{@code fdatasync()} on paths under {@code path} with the given errno —
   * exercises durability-barrier failure paths.
   *
   * @param container target container
   * @param path path prefix whose durability calls should fail
   * @param errno errno to inject (typically {@link Errno#EIO})
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   */
  RuleHandle failFsync(
      GenericContainer<?> container, PathPrefix path, Errno errno, double probability);

  /**
   * Add latency to {@code open()}/{@code openat()} on paths under {@code path}.
   *
   * @param container target container
   * @param path path prefix whose opens should be slowed
   * @param delay non-negative delay
   * @return handle for later removal
   */
  RuleHandle slowOpen(GenericContainer<?> container, PathPrefix path, Duration delay);

  /**
   * Fail {@code renameat()} when the source path matches {@code path} — exercises atomic-replace
   * failure paths.
   *
   * @param container target container
   * @param path source-path prefix for the rename
   * @param errno errno to inject (typically {@link Errno#EACCES} or {@link Errno#EIO})
   * @param probability probability in {@code (0.0, 1.0]}
   * @return handle for later removal
   * @see IoOperation#RENAME_FROM
   */
  RuleHandle failRename(
      GenericContainer<?> container, PathPrefix path, Errno errno, double probability);
}
