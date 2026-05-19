/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

/**
 * Per-L1-annotation policy controlling what happens when the backend cannot honour the requested
 * primitive (the libchaos {@code .so} is unavailable on the current OS, the JVM agent isn't
 * loaded, the active connection-chaos backend is Toxiproxy and the verb requires libchaos-net,
 * etc.).
 *
 * <p>This enum exists because the framework supports both rigorous CI environments (where every
 * backend can be installed) and lighter local developer environments (where libchaos may not be
 * present). Without an explicit policy the framework would either fail-fast and block local
 * iteration entirely, or silently degrade and produce false-positive passing tests — both
 * unacceptable.
 *
 * <p>{@link #ERROR} is the safe default: tests fail loud and red, forcing the developer to fix
 * their environment. {@link #ABORT} is the opt-in for environments where the chaos library is
 * known to be sometimes-unavailable; tests show as the JUnit 5 ABORTED state (yellow in IDE,
 * distinct in CI reports), which is visible enough that nobody mistakes it for a clean pass.
 *
 * <p><strong>Out of scope:</strong> developer errors (invalid attribute value, missing container
 * annotation, mistyped {@code id}). Those always hard-fail at {@code beforeAll} regardless of this
 * setting — the policy only governs runtime environment-availability mismatches.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see ChaosL1
 */
public enum OnMissingEnv {

  /**
   * Hard fail at {@code beforeAll} with {@code ExtensionConfigurationException}. The test class
   * shows RED in IDE / CI reports. The default — biased toward catching environment regressions
   * rather than tolerating them.
   */
  ERROR,

  /**
   * Abort the test class via {@code TestAbortedException} (from {@code org.opentest4j}). The test
   * class shows YELLOW (the JUnit 5 ABORTED third state, distinct from passed/failed) with a
   * printable reason. Opt-in for tests that must remain runnable on developer machines without
   * the chaos library installed.
   */
  ABORT
}
