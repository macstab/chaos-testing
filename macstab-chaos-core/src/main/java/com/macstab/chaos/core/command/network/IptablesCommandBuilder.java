/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.network;

/**
 * Linux iptables network command builder.
 *
 * <p>Builds iptables commands for port redirection using NAT chains.
 *
 * <p><strong>Dual-chain redirect:</strong>
 *
 * <ul>
 *   <li>PREROUTING: Redirects external traffic entering container
 *   <li>OUTPUT: Redirects internal traffic (localhost → hostname)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class IptablesCommandBuilder implements NetworkCommandBuilder {

  /** Creates an iptables command builder instance. */
  public IptablesCommandBuilder() {}

  @Override
  public String buildAddRedirectCommand(final int fromPort, final int toPort) {
    validatePort(fromPort, "fromPort");
    validatePort(toPort, "toPort");

    // CRITICAL: Both PREROUTING and OUTPUT chains required
    // PREROUTING: external traffic (host → container)
    // OUTPUT: internal traffic (localhost → hostname within container)
    return String.format(
        "iptables -t nat -A PREROUTING -p tcp --dport %d -j REDIRECT --to-port %d 2>&1 && "
            + "iptables -t nat -A OUTPUT -p tcp --dport %d -j REDIRECT --to-port %d 2>&1",
        fromPort, toPort, fromPort, toPort);
  }

  @Override
  public String buildRemoveRedirectCommand(final int fromPort, final int toPort) {
    validatePort(fromPort, "fromPort");
    validatePort(toPort, "toPort");

    return String.format(
        "iptables -t nat -D PREROUTING -p tcp --dport %d -j REDIRECT --to-port %d 2>&1 || true",
        fromPort, toPort);
  }

  @Override
  public String buildClearRedirectsCommand() {
    // Clear both PREROUTING and OUTPUT chains
    return "iptables -t nat -F PREROUTING 2>&1 && iptables -t nat -F OUTPUT 2>&1";
  }

  @Override
  public String buildPortCheckCommand(final int port) {
    validatePort(port, "port");

    // Use curl (universal, installed as dependency)
    // Exit 0 or 52 (empty reply) = port listening
    // Exit 7 = connection refused
    return String.format(
        "curl -s --connect-timeout 1 --max-time 1 http://localhost:%d >/dev/null 2>&1; "
            + "test $? -eq 0 -o $? -eq 52",
        port);
  }

  private void validatePort(final int port, final String paramName) {
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          String.format("%s must be in range [1, 65535], got: %d", paramName, port));
    }
  }
}
