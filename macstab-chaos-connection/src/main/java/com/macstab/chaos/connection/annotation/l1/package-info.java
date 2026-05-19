/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for libchaos-net. Curated (op, errno) tuples per operation cover the
 * production-realistic failure surface; Latency annotations span every operation. CORRUPT and
 * TIMEOUT are libchaos-net-grammar-restricted to RECV and POLL respectively (NetRule enforces
 * these), so each has exactly one L1 annotation. Endpoint is always wildcard at the L1 tier —
 * per-endpoint targeting remains in AdvancedConnectionChaos.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.connection.annotation.l1;
