/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.network;

/**
 * Platform-specific network command builder.
 *
 * <p>Builds commands for port redirection, firewall rules, etc.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@code IptablesCommandBuilder} - Linux iptables
 *   <li>{@code NftablesCommandBuilder} - Linux nftables (future)
 *   <li>{@code PfCommandBuilder} - BSD/macOS pf (future)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface NetworkCommandBuilder {

  /**
   * Build command to add port redirect.
   *
   * <p>Redirects traffic from {@code fromPort} to {@code toPort}.
   *
   * <p><strong>Linux iptables example:</strong>
   *
   * <pre>
   * iptables -t nat -A PREROUTING -p tcp --dport 6379 -j REDIRECT --to-port 16379 &&
   * iptables -t nat -A OUTPUT -p tcp --dport 6379 -j REDIRECT --to-port 16379
   * </pre>
   *
   * @param fromPort source port
   * @param toPort destination port
   * @return command string
   */
  String buildAddRedirectCommand(int fromPort, int toPort);

  /**
   * Build command to remove port redirect.
   *
   * @param fromPort source port
   * @param toPort destination port
   * @return command string
   */
  String buildRemoveRedirectCommand(int fromPort, int toPort);

  /**
   * Build command to clear all redirect rules.
   *
   * @return command string
   */
  String buildClearRedirectsCommand();

  /**
   * Build command to check if port is listening.
   *
   * <p>Returns command that exits 0 if port is listening, non-zero otherwise.
   *
   * @param port port to check
   * @return command string
   */
  String buildPortCheckCommand(int port);
}
