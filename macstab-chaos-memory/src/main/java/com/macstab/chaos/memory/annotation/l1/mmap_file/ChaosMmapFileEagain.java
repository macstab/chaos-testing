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
 * Injects {@code EAGAIN} into file-backed {@code mmap} calls intercepted by libchaos-memory,
 * causing the calling code to observe a transient resource-unavailable failure when attempting
 * to establish a file-backed memory mapping.
 *
 * <h2>What this annotation is</h2>
 * L1 libchaos-memory primitive — one (selector = {@code MMAP_FILE}, errno = {@code EAGAIN})
 * tuple. The {@code MMAP_FILE} selector intercepts only file-backed {@code mmap} calls, leaving
 * anonymous allocations unaffected. Compile-time safety: invalid combinations have no annotation
 * class.
 *
 * <h2>What chaos this applies</h2>
 * <ol>
 *   <li>{@code LD_PRELOAD} loads {@code libchaos-memory.so} before the container process starts,
 *       interposing the libc {@code mmap} wrapper at the dynamic-linker level.</li>
 *   <li>On each file-backed {@code mmap} call the interposer runs a Bernoulli trial with
 *       probability {@link #probability}.</li>
 *   <li>When the trial fires, the interposer sets {@code errno = EAGAIN} and returns
 *       {@code MAP_FAILED} without issuing the real kernel call.</li>
 *   <li>The calling code receives: {@code MAP_FAILED} return, {@code errno} 11,
 *       {@code strerror}: "Resource temporarily unavailable".</li>
 * </ol>
 *
 * <h2>Observable effects and what to assert in tests</h2>
 * <ul>
 *   <li>{@code mmap} returns {@code MAP_FAILED}; {@code errno = EAGAIN} (11); file-mapping code
 *       should retry with back-off or fall back to conventional read/write I/O.</li>
 *   <li>Applications that use memory-mapped I/O without a fallback path will surface a
 *       transient error — assert that a structured error is returned, not a silent crash.</li>
 *   <li>Assert that retry logic (where present) is bounded and includes a back-off strategy.</li>
 * </ul>
 * Production failure mode: network filesystems under pressure or distributed storage backends
 * that implement {@code mmap} via a kernel module can transiently return {@code EAGAIN} when
 * the backing cluster is temporarily unavailable — causing file-backed mapping operations to
 * fail without an errno that indicates permanent failure.
 *
 * <h2>Deep technical dive</h2>
 * <p>POSIX allows {@code mmap} to return {@code EAGAIN} for file-backed mappings when the
 * mapping cannot be established at this moment but may succeed on retry. On Linux, this is
 * unusual for local filesystems but can occur with network filesystems (NFS, CIFS) when the
 * server is temporarily unreachable and the filesystem is mounted with {@code soft} or
 * {@code intr} options.
 *
 * <p>For local filesystems (ext4, xfs, btrfs), real {@code EAGAIN} from file-backed {@code mmap}
 * is extremely rare. This annotation exercises error-recovery code that is essentially untested
 * in normal integration testing but that will fire in production when network storage degrades.
 *
 * <p>Applications that use memory-mapped files for database storage (RocksDB, LMDB, HaloDB)
 * or for log-structured I/O (Kafka, Chronicle Queue) must handle this case gracefully. The
 * typical fallback is to re-open the file and retry the mapping, or to fall back to
 * {@code pread}/{@code pwrite} for the affected regions.
 *
 * <p>Compared with {@code ENOMEM}: {@code EAGAIN} is transient (retry may succeed);
 * {@code ENOMEM} is structural (retry requires releasing resources). Both prevent the mapping
 * from being established but require different recovery strategies.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapFileEagain(probability = 0.001)
 * class TransientFileMappingTest {
 *   @Test
 *   void appHandlesEagainOnFileMappings(RedisConnectionInfo info) {
 *     // verify retry logic is bounded and the fallback I/O path works correctly
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> 1e-4 to 1e-3 simulates transient pressure; rates
 * above 0.01 exhaust retry budgets and cause cascading failures in database engines.
 * <p><strong>Scope:</strong> {@link #id()} binds this rule to a single container by its declared
 * {@code id}; the default empty string applies the rule to every memory-chaos-capable container in
 * the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMmapFileEagain.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP_FILE, errno = MmapErrno.EAGAIN)
public @interface ChaosMmapFileEagain {

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
   * @ChaosMmapFileEagain(id = "primary",  probability = 0.001)
   * @ChaosMmapFileEagain(id = "replica",  probability = 0.01)
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
    ChaosMmapFileEagain[] value();
  }
}
