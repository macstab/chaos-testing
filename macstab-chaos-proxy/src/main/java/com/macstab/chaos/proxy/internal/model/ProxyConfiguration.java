/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.model;

import java.util.Objects;

import lombok.Value;

/**
 * Internal configuration for a TCP proxy.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Value
public class ProxyConfiguration {
  String proxyName;
  int servicePort;
  int proxyPort;
  String containerHostname;

  public ProxyConfiguration(
      final String proxyName,
      final int servicePort,
      final int proxyPort,
      final String containerHostname) {

    this.proxyName = Objects.requireNonNull(proxyName, "proxyName must not be null");
    this.servicePort = validatePort(servicePort, "servicePort");
    this.proxyPort = validatePort(proxyPort, "proxyPort");
    this.containerHostname =
        Objects.requireNonNull(containerHostname, "containerHostname must not be null");
  }

  private static int validatePort(final int port, final String name) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          String.format("%s must be in range [1, 65535], got: %d", name, port));
    }
    return port;
  }
}
