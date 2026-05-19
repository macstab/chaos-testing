/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for the {@code fork} libchaos-process syscall family. Each
 * annotation encodes exactly one (selector, errno) tuple for ERRNO and FAIL_AFTER effects, or
 * one selector for LATENCY effect — invalid POSIX combinations have no annotation class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.process.annotation.l1.fork;
