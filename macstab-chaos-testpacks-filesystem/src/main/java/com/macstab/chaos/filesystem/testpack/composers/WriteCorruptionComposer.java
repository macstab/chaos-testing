/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.testpack.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.filesystem.testpack.CompositeChaosWriteCorruption;

/** L2 composer for {@link CompositeChaosWriteCorruption}. */
public final class WriteCorruptionComposer implements L2Composer<CompositeChaosWriteCorruption> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public WriteCorruptionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosWriteCorruption annotation) {
    final PathPrefix path = resolvePath(annotation.path());
    final RuleHandle handle =
        CompositeFilesystemChaos.standard()
            .advanced()
            .apply(container, IoRule.torn(path, IoOperation.WRITE, annotation.toxicity()));
    final List<Object> handles = new ArrayList<>();
    handles.add(handle);
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
  public List<String> describe(final CompositeChaosWriteCorruption annotation) {
    return List.of(
        "write corruption (torn write — short byte count returned) — power-fail mid-sector or NVMe controller reset",
        "path=" + annotation.path(),
        "toxicity=" + annotation.toxicity(),
        "severity=CRITICAL — silent partial write; WAL and SSTable integrity require checksum verification");
  }

  private static PathPrefix resolvePath(final String path) {
    if ("*".equals(path)) {
      return PathPrefix.wildcard();
    }
    return PathPrefix.path(path);
  }
}
