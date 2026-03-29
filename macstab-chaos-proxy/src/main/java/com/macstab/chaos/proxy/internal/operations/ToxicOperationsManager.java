/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.proxy.internal.operations;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import com.macstab.chaos.proxy.api.ToxiproxyApiClient;
import com.macstab.chaos.proxy.api.ToxiproxyApiClientImpl;
import com.macstab.chaos.proxy.config.ToxiproxyConfig;
import com.macstab.chaos.proxy.internal.ContainerContext;
import com.macstab.chaos.proxy.internal.operations.toxic.ToxicConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of toxic CRUD operations.
 *
 * <p>Receives a pre-resolved {@link ContainerContext} on every call — no platform detection
 * inside this class.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class ToxicOperationsManager implements ToxicOperations {

  private final ToxiproxyApiClient apiClient;

  /**
   * Create toxic operations manager with default components.
   *
   * @param config Toxiproxy configuration
   */
  public ToxicOperationsManager(final ToxiproxyConfig config) {
    Objects.requireNonNull(config, "config must not be null");
    this.apiClient = new ToxiproxyApiClientImpl(config.apiUrl());
  }

  /**
   * Create toxic operations manager with custom components (for testing).
   *
   * @param config Toxiproxy configuration (validated; apiUrl carried by apiClient)
   * @param apiClient API client instance
   */
  public ToxicOperationsManager(final ToxiproxyConfig config, final ToxiproxyApiClient apiClient) {
    Objects.requireNonNull(config, "config must not be null");
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient must not be null");
  }

  @Override
  public void addToxic(
      final ContainerContext ctx, final String proxyName, final ToxicConfig config)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(config, "config must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    if (apiClient.toxicExists(ctx, proxyName, config.name())) {
      log.debug("Toxic '{}' already exists on proxy '{}', skipping", config.name(), proxyName);
      return;
    }

    apiClient.addToxic(ctx, proxyName, config.name(), config.type(), config.toJson(),
        config.toxicity());

    log.info("Added toxic '{}' to proxy '{}' (type={}, toxicity={})",
        config.name(), proxyName, config.type(), config.toxicity());
  }

  @Override
  public void removeToxic(
      final ContainerContext ctx, final String proxyName, final String toxicName)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    apiClient.deleteToxic(ctx, proxyName, toxicName);
    log.info("Removed toxic '{}' from proxy '{}'", toxicName, proxyName);
  }

  @Override
  public boolean toxicExists(
      final ContainerContext ctx, final String proxyName, final String toxicName) {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");

    if (!ctx.container().isRunning()) {
      return false;
    }

    try {
      return apiClient.toxicExists(ctx, proxyName, toxicName);
    } catch (final Exception e) {
      log.trace("Failed to check toxic existence: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public void removeAllToxics(final ContainerContext ctx, final String proxyName)
      throws IOException {

    Objects.requireNonNull(ctx, "ctx must not be null");
    Objects.requireNonNull(proxyName, "proxyName must not be null");

    if (!ctx.container().isRunning()) {
      throw new IllegalStateException("Container must be running");
    }

    final List<String> toxicNames = apiClient.listToxics(ctx, proxyName);

    for (final String toxicName : toxicNames) {
      apiClient.deleteToxic(ctx, proxyName, toxicName);
      log.debug("Deleted toxic '{}' from proxy '{}'", toxicName, proxyName);
    }

    log.info("Removed {} toxic(s) from proxy '{}'", toxicNames.size(), proxyName);
  }
}
