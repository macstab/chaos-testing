/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for libchaos-io. Curated (op, errno) tuples per operation cover the
 * production-realistic failure surface; Latency annotations span every operation. TORN is
 * IoRule-restricted to WRITE/PWRITE and CORRUPT to READ/PREAD (IoRule.requireCompatible enforces
 * these). PathPrefix is always wildcard at the L1 tier; per-path targeting stays in the
 * imperative AdvancedFilesystemChaos API.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.filesystem.annotation.l1;
