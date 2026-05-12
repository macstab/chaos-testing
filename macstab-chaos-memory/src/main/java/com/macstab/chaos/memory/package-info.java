/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Two-tier memory chaos: cgroups whole-container governors plus libchaos-memory VM-syscall
 * precision.
 *
 * <h2>Two backends, one facade</h2>
 *
 * <p>{@link com.macstab.chaos.memory.CompositeMemoryChaos} aggregates two strategies behind a
 * unified {@link com.macstab.chaos.core.api.MemoryChaos} interface.
 *
 * <table>
 *   <caption>Backend comparison</caption>
 *   <tr>
 *     <th></th>
 *     <th>{@link com.macstab.chaos.memory.strategy.cgroups.CgroupsMemoryChaos CgroupsMemoryChaos}</th>
 *     <th>{@link com.macstab.chaos.memory.strategy.libchaos.LibchaosMemoryChaos
 *       LibchaosMemoryChaos}</th>
 *   </tr>
 *   <tr>
 *     <td><strong>Mechanism</strong></td>
 *     <td>cgroups v2 {@code memory.max} / {@code memory.high} / {@code memory.pressure} +
 *         {@code stress-ng}</td>
 *     <td>{@code LD_PRELOAD} hook of {@code mmap} / {@code munmap} / {@code mprotect} /
 *         {@code madvise}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Installation</strong></td>
 *     <td>Lazy — requires {@code stress-ng} apt install</td>
 *     <td>Pre-start — must be wired before {@code container.start()}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Granularity</strong></td>
 *     <td>Whole container — hard/soft RAM limit, PSI readings</td>
 *     <td>Per VM-syscall × per-errno × per-probability</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Errno palette</strong></td>
 *     <td>Whatever the kernel returns under genuine OOM</td>
 *     <td>10 errnos: {@code EACCES}, {@code EAGAIN}, {@code EBADF}, {@code EINVAL}, {@code ENFILE},
 *         {@code ENODEV}, {@code ENOMEM}, {@code EOVERFLOW}, {@code EPERM}, {@code ETXTBSY},
 *         {@code EIO}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Test capability</strong></td>
 *     <td>"Process gets OOM-killed when over limit"</td>
 *     <td>"This specific malloc returns NULL", "pthread_create fails", "dlopen fails",
 *         "JIT mprotect fails", "madvise(DONTNEED) fails", "munmap leaks"</td>
 *   </tr>
 * </table>
 *
 * <h2>Capability uplift via {@code advanced()}</h2>
 *
 * <p>{@link com.macstab.chaos.memory.CompositeMemoryChaos#advanced()} returns the libchaos-memory
 * strategy as an {@link com.macstab.chaos.memory.api.AdvancedMemoryChaos}. This exposes 25+ typed
 * verbs grouped by failure class:
 *
 * <ul>
 *   <li><strong>Heap allocation</strong> — {@code failHeapAllocation}, {@code failLargeAllocation},
 *       {@code simulateOomKiller}, {@code simulateMemoryPressure}, {@code slowHeapAllocation}
 *   <li><strong>File mapping / dlopen</strong> — {@code failFileMapping}, {@code failLibraryLoad},
 *       {@code failPluginLoad}, {@code slowFileMapping}
 *   <li><strong>Thread / stack</strong> — {@code failThreadCreation}, {@code failGuardPageSetup}
 *   <li><strong>Page permission</strong> — {@code failPermissionChange}, {@code
 *       failJitCompilation}, {@code slowPermissionChange}
 *   <li><strong>Kernel hints</strong> — {@code failMadvise}, {@code failHugepageHint}, {@code
 *       failPagePurge}, {@code slowMadvise}
 *   <li><strong>Cleanup</strong> — {@code failUnmap}, {@code simulateLeak}
 *   <li><strong>Raw escape hatches</strong> — {@code errno}, {@code latency}, {@code apply}, {@code
 *       applyAll}, {@code remove}, {@code removeAll}
 * </ul>
 *
 * <h2>Verb-to-backend routing</h2>
 *
 * <table>
 *   <caption>Which strategy handles which verb</caption>
 *   <tr><th>Verb</th><th>libchaos-memory</th><th>cgroups</th></tr>
 *   <tr><td>{@code setLimit} / {@code setPressure}</td><td>—</td><td>✓</td></tr>
 *   <tr><td>{@code stress}</td><td>—</td><td>✓ (via stress-ng)</td></tr>
 *   <tr><td>{@code getCurrentUsage} / {@code getPressure}</td><td>—</td><td>✓</td></tr>
 *   <tr><td>All advanced verbs (failHeapAllocation, failJitCompilation, …)</td><td>✓</td><td>—</td></tr>
 * </table>
 *
 * <p>The composite routes each portable verb to the first strategy whose {@code supports()} returns
 * {@code true}; if that strategy throws {@link
 * com.macstab.chaos.core.exception.ChaosUnsupportedOperationException}, the composite falls through
 * to the next applicable strategy. This is what lets libchaos-memory and cgroups coexist: libchaos
 * has no answer to {@code setLimit}/{@code setPressure}/{@code stress}/{@code
 * getCurrentUsage}/{@code getPressure} (which need cgroups), and cgroups has no answer to per-
 * syscall fault injection (which needs libchaos).
 *
 * <h2>Pre-flight requirements</h2>
 *
 * <p>Advanced verbs require the container to be prepared with libchaos-memory <em>before</em>
 * {@code container.start()}. Annotate the test class with
 * {@code @SyscallLevelChaos(LibchaosLib.MEMORY)} — {@code ChaosTestingExtension} reads the
 * annotation and drives {@code LibchaosTransport.prepare()} into the pre-start window.
 *
 * <p>Without preparation, advanced verbs raise {@link
 * com.macstab.chaos.core.exception.LibchaosNotPreparedException} loudly at the call site — there is
 * no silent fallback.
 *
 * <h2>Probability guidance</h2>
 *
 * <p>libchaos-memory rules accept a per-rule probability via the {@code @<probability>} suffix
 * (defaults to {@code 1.0}). Many rules need a low probability to avoid breaking the container
 * itself — e.g. {@code mmap/anon:ERRNO:ENOMEM@1.0} prevents any new thread/heap allocation, which
 * breaks SSH, the package installer, JVM startup, etc. For sustained chaos use {@code 0.001}–
 * {@code 0.01}; for deterministic single-shot tests use {@code 1.0}.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/MEMORY.md">libchaos-memory
 *     technical reference</a>
 */
package com.macstab.chaos.memory;
