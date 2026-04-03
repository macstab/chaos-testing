/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.platform;

/**
 * System tools required by chaos modules.
 *
 * <p>Package names for these tools vary by distribution. Use {@link Platform#getPackageName(Tool)}
 * to get the correct package name for the target platform.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum Tool {
  /** curl - HTTP client (required for Toxiproxy API, port checks) */
  CURL,

  /** iptables - Linux firewall (required for port redirection) */
  IPTABLES,

  /** CA certificates bundle (required for HTTPS downloads) */
  CA_CERTIFICATES,

  /** Process utilities (ps, top, etc.) */
  PROCPS,

  /** IP routing utilities (ip, tc, etc.) */
  IPROUTE,

  /** Python interpreter */
  PYTHON,

  /** Stress testing utility */
  STRESS_NG,

  /** CPU usage limiter (cpulimit binary) */
  CPULIMIT,

  /** CPU affinity tool — sets/gets process CPU core binding (taskset binary, provided by util-linux) */
  TASKSET,

  /** Process priority tool — adjusts scheduler nice value (renice binary, provided by util-linux) */
  RENICE,

  /** Reports number of available CPU cores (nproc binary, provided by coreutils) */
  NPROC
}
