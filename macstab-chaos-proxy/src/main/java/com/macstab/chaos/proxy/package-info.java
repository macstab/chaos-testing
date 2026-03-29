/**
 * Public API for proxy-based chaos injection via Toxiproxy.
 *
 * <h2>Entry Point</h2>
 *
 * <p>{@link com.macstab.chaos.proxy.ProxyChaosProvider} is the single public entry point.
 * Registered as the {@code NetworkChaos} SPI implementation via {@code
 * META-INF/services/com.macstab.chaos.core.api.ProxyChaos}.
 *
 * <h2>Package Boundary</h2>
 *
 * <p>Only this package and {@code com.macstab.chaos.proxy.config} are part of the public API. All
 * classes under {@code com.macstab.chaos.proxy.internal} are implementation details and
 * <strong>must not</strong> be used by external consumers.
 *
 * <p><em>Note:</em> JPMS ({@code module-info.java}) enforcement is deferred pending full JPMS
 * support in Testcontainers and Mockito. The {@code internal} package naming convention serves as
 * the access boundary until then.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.proxy;
