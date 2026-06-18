/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Selector for a libchaos-memory rule — identifies which VM-syscall family the rule applies to.
 *
 * <p>libchaos-memory hooks four POSIX/Linux memory-management entry points at the libc boundary:
 * {@code mmap}, {@code munmap}, {@code mprotect}, {@code madvise}. The two {@link #MMAP_ANON} /
 * {@link #MMAP_FILE} sub-selectors discriminate within {@code mmap} based on the {@code
 * MAP_ANONYMOUS} flag — anonymous mappings cover heap large-allocations / thread stacks / shared
 * memory, while file-backed mappings cover {@code dlopen()} / ELF segment loading / memory-mapped
 * I/O.
 *
 * <p><strong>Errno × selector compatibility</strong> (validated at {@link MemoryRule}
 * construction):
 *
 * <table>
 *   <caption>Errnos accepted per selector — from POSIX.1-2017 and the Linux man-pages</caption>
 *   <tr>
 *     <th>Selector</th>
 *     <th>Valid errnos</th>
 *   </tr>
 *   <tr>
 *     <td>{@link #MMAP}, {@link #MMAP_ANON}, {@link #MMAP_FILE}</td>
 *     <td>EACCES, EAGAIN, EBADF, EFAULT, EINVAL, EMFILE, ENFILE, ENODEV, ENOMEM, EPERM</td>
 *   </tr>
 *   <tr><td>{@link #MUNMAP}</td><td>EFAULT, EINVAL</td></tr>
 *   <tr><td>{@link #MPROTECT}</td><td>EACCES, EFAULT, EINVAL, ENOMEM</td></tr>
 *   <tr><td>{@link #MADVISE}</td><td>EACCES, EAGAIN, EBADF, EFAULT, EINVAL, ENOMEM, ENOSYS, EPERM</td></tr>
 *   <tr>
 *     <td>{@link #WILDCARD}</td>
 *     <td>intersection of all: EINVAL (the one errno valid on every interposed call)</td>
 *   </tr>
 * </table>
 *
 * <p>The wildcard's strict-intersection policy is defensive: a wider rule such as {@code
 * *:ERRNO:EAGAIN} would silently no-op on {@code munmap} calls, which is surprising behaviour. Use
 * a specific selector (e.g. {@code mmap:ERRNO:EAGAIN}) if the wider errno is intended.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a
 *     href="https://github.com/macstab/chaos-testing-libraries/blob/main/docs/MEMORY.md">libchaos-memory
 *     rule grammar</a>
 */
public enum MemorySelector {

  /** {@code mmap()} — any mapping regardless of {@code MAP_ANONYMOUS}. */
  MMAP("mmap"),

  /**
   * {@code mmap(MAP_ANONYMOUS)} — anonymous mappings. Covers:
   *
   * <ul>
   *   <li>glibc {@code malloc()} for sizes ≥ {@code MMAP_THRESHOLD} (default 128 KiB)
   *   <li>musl {@code malloc()} for every allocation (mallocng uses anonymous mmap)
   *   <li>{@code pthread_create()} thread stacks
   *   <li>{@code PROT_NONE} guard pages
   *   <li>{@code MAP_SHARED | MAP_ANONYMOUS} for parent-child IPC
   * </ul>
   */
  MMAP_ANON("mmap/anon"),

  /**
   * {@code mmap()} without {@code MAP_ANONYMOUS} — file-backed mappings. Covers:
   *
   * <ul>
   *   <li>{@code dlopen()} of shared libraries
   *   <li>Dynamic-linker ELF segment loading
   *   <li>Memory-mapped file I/O ({@code MAP_SHARED} or {@code MAP_PRIVATE} with fd ≥ 0)
   *   <li>POSIX shared memory ({@code shm_open})
   * </ul>
   */
  MMAP_FILE("mmap/file"),

  /** {@code munmap()} — release a mapping. */
  MUNMAP("munmap"),

  /** {@code mprotect()} — change page permissions; used by JIT compilers and stack guards. */
  MPROTECT("mprotect"),

  /** {@code madvise()} — kernel hints (DONTNEED, WILLNEED, HUGEPAGE, FREE, …). */
  MADVISE("madvise"),

  /** Wildcard — matches every interposed call. Use sparingly. */
  WILDCARD("*");

  private final String wireForm;

  MemorySelector(final String wireForm) {
    this.wireForm = wireForm;
  }

  /**
   * @return the libchaos-memory selector token (e.g. {@code "mmap/anon"}, {@code "munmap"})
   */
  public String wireForm() {
    return wireForm;
  }

  // ==================== Errno compatibility ====================

  private static final Set<MmapErrno> MMAP_ERRNOS =
      EnumSet.of(
          MmapErrno.EACCES,
          MmapErrno.EAGAIN,
          MmapErrno.EBADF,
          MmapErrno.EFAULT,
          MmapErrno.EINVAL,
          MmapErrno.EMFILE,
          MmapErrno.ENFILE,
          MmapErrno.ENODEV,
          MmapErrno.ENOMEM,
          MmapErrno.EPERM);

  private static final Set<MmapErrno> MUNMAP_ERRNOS =
      EnumSet.of(MmapErrno.EFAULT, MmapErrno.EINVAL);

  private static final Set<MmapErrno> MPROTECT_ERRNOS =
      EnumSet.of(MmapErrno.EACCES, MmapErrno.EFAULT, MmapErrno.EINVAL, MmapErrno.ENOMEM);

  private static final Set<MmapErrno> MADVISE_ERRNOS =
      EnumSet.of(
          MmapErrno.EACCES,
          MmapErrno.EAGAIN,
          MmapErrno.EBADF,
          MmapErrno.EFAULT,
          MmapErrno.EINVAL,
          MmapErrno.ENOMEM,
          MmapErrno.ENOSYS,
          MmapErrno.EPERM);

  /** Intersection of every per-symbol set above — the one errno valid on every interposed call. */
  private static final Set<MmapErrno> WILDCARD_ERRNOS = EnumSet.of(MmapErrno.EINVAL);

  /**
   * @return the immutable set of {@link MmapErrno}s that libchaos-memory accepts for this selector
   */
  public Set<MmapErrno> validErrnos() {
    return switch (this) {
      case MMAP, MMAP_ANON, MMAP_FILE -> MMAP_ERRNOS;
      case MUNMAP -> MUNMAP_ERRNOS;
      case MPROTECT -> MPROTECT_ERRNOS;
      case MADVISE -> MADVISE_ERRNOS;
      case WILDCARD -> WILDCARD_ERRNOS;
    };
  }

  /**
   * @param errno errno to test
   * @return {@code true} iff {@code errno} is in {@link #validErrnos()} for this selector
   */
  public boolean accepts(final MmapErrno errno) {
    return validErrnos().contains(errno);
  }
}
