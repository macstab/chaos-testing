/* (C)2026 Christian Schnapka / Macstab GmbH */

/**
 * Cache chaos injection SPI root.
 *
 * <p>This module provides pluggable chaos injection for in-memory cache backends. Each backend
 * ships as its own sub-package and implements the
 * {@link com.macstab.chaos.core.api.CacheChaos} interface from {@code macstab-chaos-core}.
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Supported Backends</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <ul>
 *   <li>{@code redis/} — Redis via Toxiproxy (TCP faults) + redis-cli (data-level faults)</li>
 *   <li>{@code hazelcast/} — Hazelcast (planned)</li>
 *   <li>{@code memcached/} — Memcached (planned)</li>
 * </ul>
 *
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 * <h2>Adding a New Backend</h2>
 * <!-- ═══════════════════════════════════════════════════════════════════ -->
 *
 * <ol>
 *   <li>Create sub-package: {@code com.macstab.chaos.cache.<backend>/}</li>
 *   <li>Implement {@link com.macstab.chaos.core.api.CacheChaos}</li>
 *   <li>Register in {@code META-INF/services/com.macstab.chaos.core.api.CacheChaos}</li>
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.cache;
