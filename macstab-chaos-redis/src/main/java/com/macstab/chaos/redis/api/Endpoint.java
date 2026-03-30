/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.api;

/**
 * Network endpoint (host + port pair).
 *
 * @param host host address (IP or hostname, not null)
 * @param port port number (1-65535)
 * @author Christian Schnapka / Macstab GmbH
 * @since 1.0
 */
public record Endpoint(String host, int port) {

  public Endpoint {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("Host cannot be null or blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
    }
  }

  /**
   * Host:port string representation.
   *
   * @return "host:port"
   */
  @Override
  public String toString() {
    return host + ":" + port;
  }
}
