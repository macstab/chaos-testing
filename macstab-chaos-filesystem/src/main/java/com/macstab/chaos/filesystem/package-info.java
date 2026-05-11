/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * Two-tier filesystem chaos: shell-level coarse verbs plus libchaos-io syscall-level precision.
 *
 * <h2>Two backends, one facade</h2>
 *
 * <p>{@link com.macstab.chaos.filesystem.CompositeFilesystemChaos} aggregates two strategies behind
 * a unified {@link com.macstab.chaos.core.api.FilesystemChaos} interface. Callers do not need to
 * know which backend produced the fault — every cleanup verb removes faults from whichever strategy
 * added them.
 *
 * <table>
 *   <caption>Backend comparison</caption>
 *   <tr>
 *     <th></th>
 *     <th>{@link com.macstab.chaos.filesystem.strategy.shell.ShellFilesystemChaos
 *       ShellFilesystemChaos}</th>
 *     <th>{@link com.macstab.chaos.filesystem.strategy.libchaos.LibchaosIoFilesystemChaos
 *       LibchaosIoFilesystemChaos}</th>
 *   </tr>
 *   <tr>
 *     <td><strong>Mechanism</strong></td>
 *     <td>In-container {@code dd}, {@code chmod}, {@code rm}</td>
 *     <td>{@code LD_PRELOAD} hook intercepting libc syscalls</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Installation</strong></td>
 *     <td>Lazy (no preparation needed)</td>
 *     <td>Pre-start (must be wired before {@code container.start()})</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Granularity</strong></td>
 *     <td>Whole container — fill the disk or {@code chmod 000} a directory</td>
 *     <td>Per-path × per-syscall × per-errno × per-probability</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Effects</strong></td>
 *     <td>Resource exhaustion, permission denial</td>
 *     <td>{@code ERRNO}, {@code LATENCY}, {@code TORN} write, {@code CORRUPT} read</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Errno palette</strong></td>
 *     <td>Whatever the kernel naturally returns (typically {@code ENOSPC}, {@code EACCES})</td>
 *     <td>8 file errnos: {@code EIO}, {@code ENOSPC}, {@code EDQUOT}, {@code EROFS}, {@code EACCES},
 *         {@code EMFILE}, {@code ENFILE}, {@code ENOENT}</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Reversibility</strong></td>
 *     <td>Cleanup via {@code rm}/{@code chmod}; some faults survive {@code reset}
 *         if the application persisted them</td>
 *     <td>Surgical: remove a single rule by handle, or wipe everything via {@code removeAll}</td>
 *   </tr>
 * </table>
 *
 * <h2>Capability uplift via {@code advanced()}</h2>
 *
 * <p>{@link com.macstab.chaos.filesystem.CompositeFilesystemChaos#advanced()} returns the
 * libchaos-io strategy as a {@link com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos}. This
 * exposes verbs the shell strategy cannot model:
 *
 * <ul>
 *   <li>{@code failOpen} / {@code failRead} / {@code failWrite} — inject a specific errno on a
 *       single syscall family at a path prefix
 *   <li>{@code tornWrite} — model successful short writes (the bytes-returned-{@literal
 *       <}-bytes-requested case)
 *   <li>{@code corruptRead} — single-bit corruption applied to a successful read's returned buffer
 *   <li>{@code slowFsync} / {@code failFsync} — latency and failure on durability barriers
 *   <li>{@code exhaustFds} — {@code open()} returns {@code EMFILE} (per-process FD limit)
 *   <li>{@code makeReadOnly} / {@code fillQuota} — pretend the filesystem is read-only or quota
 *       exhausted
 *   <li>{@code failRename} — atomic-replace failure paths
 * </ul>
 *
 * <h2>Verb-to-backend routing</h2>
 *
 * <table>
 *   <caption>Which strategy handles which verb</caption>
 *   <tr><th>Verb</th><th>libchaos-io</th><th>shell</th></tr>
 *   <tr><td>{@code fillDisk}</td><td>—</td><td>✓</td></tr>
 *   <tr><td>{@code injectPermissionErrors}</td><td>—</td><td>✓</td></tr>
 *   <tr><td>{@code apply} / {@code applyAll} / {@code remove} / {@code removeAll}</td><td>✓</td><td>—</td></tr>
 *   <tr><td>All advanced verbs ({@code failOpen}, {@code tornWrite}, …)</td><td>✓</td><td>—</td></tr>
 * </table>
 *
 * <p>The composite routes each verb to the first strategy whose {@code supports()} returns {@code
 * true}; a {@link com.macstab.chaos.core.exception.ChaosUnsupportedOperationException} from the
 * first applicable strategy falls through to the next one.
 *
 * <h2>Pre-flight requirements</h2>
 *
 * <p>Advanced verbs require the container to be prepared with libchaos-io <em>before</em> {@code
 * container.start()} — the {@code .so} is hooked via {@code LD_PRELOAD}, which the dynamic loader
 * only honours at process launch. Annotate the test class with
 * {@code @SyscallLevelChaos(LibchaosLib.IO)}; {@code ChaosTestingExtension} reads the annotation
 * and drives {@code LibchaosTransport.prepare()} into the pre-start window.
 *
 * <p>Without preparation, advanced verbs raise {@link
 * com.macstab.chaos.core.exception.LibchaosNotPreparedException} loudly at the call site. There is
 * no silent fallback by design: the lifecycle contract is non-recoverable once the container is
 * running, and a clear error beats opaque misbehaviour.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/IO.md">libchaos-io
 *     technical reference</a>
 */
package com.macstab.chaos.filesystem;
