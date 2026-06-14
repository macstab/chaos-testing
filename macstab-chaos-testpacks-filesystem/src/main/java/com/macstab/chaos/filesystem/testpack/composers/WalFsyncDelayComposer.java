/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.testpack.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.filesystem.testpack.CompositeChaosWalFsyncDelay;

/** L2 composer for {@link CompositeChaosWalFsyncDelay}. */
public final class WalFsyncDelayComposer implements L2Composer<CompositeChaosWalFsyncDelay> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public WalFsyncDelayComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosWalFsyncDelay annotation) {
    final PathPrefix path = resolvePath(annotation.path());
    final Duration delay = Duration.ofMillis(annotation.latencyMs());
    final AdvancedFilesystemChaos adv = CompositeFilesystemChaos.standard().advanced();
    final List<Object> handles = new ArrayList<>();
    handles.add(adv.apply(container, IoRule.latency(path, IoOperation.FSYNC, delay)));
    handles.add(adv.apply(container, IoRule.latency(path, IoOperation.FDATASYNC, delay)));
    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle rh) {
        new LibchaosTransport(LibchaosLib.IO).removeRules(container, rh.owner());
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosWalFsyncDelay annotation) {
    return List.of(
        "WAL fsync delay ("
            + annotation.latencyMs()
            + "ms on fsync/fdatasync) — EBS burst-credit exhaustion",
        "path=" + annotation.path(),
        "severity=MODERATE — transaction throughput collapses; service recovers when disk pressure lifts");
  }

  private static PathPrefix resolvePath(final String path) {
    if ("*".equals(path)) {
      return PathPrefix.wildcard();
    }
    return PathPrefix.path(path);
  }
}
