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
 * Injects {@code EMFILE} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe a per-process file-descriptor-limit failure when attempting
 * to establish a file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code EMFILE})
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
 *   <li>When the trial fires, the interposer sets {@code errno = EMFILE} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 24,
 *       {@code strerror}: "Too many open files".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EMFILE} (24); the application
 *       must distinguish this from {@code ENFILE} — {@code EMFILE} is a per-process limit
 *       (solvable by closing file descriptors or raising {@code RLIMIT_NOFILE}); {@code ENFILE}
 *       is a system-wide limit (requires host-level intervention).</li>
 *   <li>Database engines that accumulate many open SST file descriptors (RocksDB, Cassandra
 *       SSTables, ClickHouse parts) must handle {@code EMFILE} by evicting cached fds from their
 *       descriptor pools before retrying; assert that eviction is triggered and the retry
 *       succeeds without data loss.</li>
 *   <li>Assert that the application logs the per-process fd count and the current
 *       {@code RLIMIT_NOFILE} soft limit alongside the error so that operators can tune
 *       the limit without a code change.</li>
 * </ul>
 * Production failure mode: a database engine or log-structured storage system accumulates open
 * file descriptors across compaction and segment-rotation cycles without adequate eviction; as
 * the per-process descriptor count approaches {@code RLIMIT_NOFILE}, subsequent file-backed
 * {@code mmap} calls begin failing with {@code EMFILE}, preventing new segments from being
 * memory-mapped and degrading read throughput to zero.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX specifies {@code EMFILE} when {@code mmap} internally needs to allocate a new file
 * table entry and the per-process count would exceed {@code RLIMIT_NOFILE}. On Linux, the
 * {@code alloc_empty_file} path in {@code do_mmap_pgoff} calls {@code get_unused_fd_flags} to
 * allocate a slot in the file descriptor table; if the slot count is at the soft limit, the
 * kernel returns {@code -EMFILE}.
 *
 * <p>This kernel mechanism differs from the anonymous-mmap case: anonymous mappings never
 * allocate a file descriptor, so they cannot produce real {@code EMFILE} from the kernel.
 * For file-backed mappings, the kernel does increment a reference count on the {@code struct file}
 * but does not consume an additional fd slot from the process's descriptor table — the existing
 * fd passed to {@code mmap} is sufficient. However, some kernel paths and certain filesystem
 * drivers (particularly those implementing custom {@code mmap} file operations) may internally
 * allocate auxiliary fds. This annotation exercises the error path for this scenario.
 *
 * <p>In practice, the most common source of {@code EMFILE} in database engines is not from
 * {@code mmap} directly, but from the {@code open(2)} calls that precede the mapping. A high
 * per-file-segment fd count combined with an inadequate {@code RLIMIT_NOFILE} configuration
 * causes the {@code open} to fail first. This annotation targets the rarer but equally important
 * case where the {@code open} succeeded but the subsequent {@code mmap} fails due to auxiliary
 * resource exhaustion.
 *
 * <p>Compared with {@code ENFILE}: {@code EMFILE} is a per-process soft limit (solvable by
 * {@code setrlimit(RLIMIT_NOFILE)}); {@code ENFILE} is the system-wide global file-table limit
 * set by {@code fs.file-max} and requires host-level kernel parameter changes. Runbooks must
 * distinguish the two: an {@code EMFILE} incident can be mitigated by the container without host
 * access; an {@code ENFILE} incident requires escalation to the platform team.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEmfile(probability = 0.001)
 * class FdLimitTest {
 *   @Test
 *   void appHandlesEmfileOnFileMappings(RedisConnectionInfo info) {
 *     // verify fd eviction fires and retry succeeds without data loss
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-3 to 1e-2 simulates near-limit conditions; rates
 * above 0.1 will prevent shared-library loading and cause process abort.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEmfile.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.EMFILE)
public @interface ChaosMmapFileEmfile {

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
   * @ChaosMmapFileEmfile(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEmfile(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEmfile[] value();
  }
}
