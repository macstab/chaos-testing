/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.network;

import java.io.IOException;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.shell.Shell;

/**
 * Network port redirection operations using iptables.
 *
 * <p>Provides transparent port redirection to route traffic through Toxiproxy. Clients connect to
 * the original service port, but traffic is redirected to the proxy port.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface NetworkRedirect {

  /**
   * Setup port redirect (service port → proxy port).
   *
   * <p>Uses iptables PREROUTING and OUTPUT chains to transparently redirect all traffic.
   *
   * @param container container
   * @param shell shell for command execution
   * @param servicePort original service port
   * @param proxyPort proxy listening port
   * @throws IOException if redirect setup fails
   */
  void setupRedirect(GenericContainer<?> container, Shell shell, int servicePort, int proxyPort)
      throws IOException;

  /**
   * Remove specific port redirect.
   *
   * @param container container
   * @param shell shell
   * @param servicePort service port
   * @param proxyPort proxy port
   * @throws IOException if removal fails
   */
  void removeRedirect(GenericContainer<?> container, Shell shell, int servicePort, int proxyPort)
      throws IOException;

  /**
   * Clear all port redirects.
   *
   * @param container container
   * @param shell shell
   * @throws IOException if clear fails
   */
  void clearAllRedirects(GenericContainer<?> container, Shell shell) throws IOException;
}
