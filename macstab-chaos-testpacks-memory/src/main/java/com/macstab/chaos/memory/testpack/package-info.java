/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L2 chaos scenario annotations for the memory module.
 *
 * <p>This package contains eight named, pre-tuned {@code @CompositeChaos<X>} annotations covering
 * the canonical VM-syscall memory failure modes encountered in cloud-native production systems.
 * Each annotation composes one or more libchaos-memory rules via a {@link
 * com.macstab.chaos.core.extension.L2Composer} implementation and ships with industry-canonical
 * documentation, severity classification, and sane probability defaults.
 *
 * <h2>Scenario catalogue</h2>
 *
 * <table>
 *   <caption>Memory L2 scenarios by severity</caption>
 *   <tr><th>Annotation</th><th>Severity</th><th>What it simulates</th></tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosOomKill}</td>
 *     <td>CRITICAL</td>
 *     <td>100% mmap/mprotect/madvise ENOMEM — OOM-kill regime, every allocation fails</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosThreadStackExhaustion}</td>
 *     <td>SEVERE</td>
 *     <td>50% anonymous mmap ENOMEM — pthread_create() stack allocation fails</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosJitCompilationFailure}</td>
 *     <td>SEVERE</td>
 *     <td>80% mprotect EACCES — JIT code page protection fails, interpreter fallback</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosLibraryLoadFailure}</td>
 *     <td>SEVERE</td>
 *     <td>100% file-backed mmap EBADF — dlopen() / shared library loading fails</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosMemoryPressure}</td>
 *     <td>MODERATE</td>
 *     <td>5% mmap ENOMEM — intermittent allocation failure, noisy-neighbour pressure</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosMemoryLeak}</td>
 *     <td>MODERATE</td>
 *     <td>0.1% mmap ENOMEM — very low rate, gradual resource degradation over soak runs</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosJvmHeapPressure}</td>
 *     <td>MODERATE</td>
 *     <td>10% anonymous mmap ENOMEM — JVM heap expansion fails, OutOfMemoryError path</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.macstab.chaos.memory.testpack.CompositeChaosHugepageFailure}</td>
 *     <td>MILD</td>
 *     <td>30% madvise ENOMEM — hugepage advisory failure, allocator base-page fallback</td>
 *   </tr>
 * </table>
 *
 * <h2>Prerequisites</h2>
 *
 * <p>All scenarios in this package require a container prepared with libchaos-memory before {@code
 * container.start()}. Annotate the test class with {@code @SyscallLevelChaos(LibchaosLib.MEMORY)};
 * {@code ChaosTestingExtension} drives preparation automatically.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.memory.testpack;
