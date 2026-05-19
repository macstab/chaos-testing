/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for the {@code every interposed VM} VM syscall family. Each annotation encodes
 * exactly one (selector, errno) tuple — invalid POSIX combinations have no annotation class,
 * making the selector x errno matrix compile-time impossible to violate.
 *
 * <p>See {@link com.macstab.chaos.memory.annotation.l1} for the full L1 surface and
 * {@link com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding} for the per-annotation
 * meta-annotation that drives translator-time rule construction.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.memory.annotation.l1.wildcard;
