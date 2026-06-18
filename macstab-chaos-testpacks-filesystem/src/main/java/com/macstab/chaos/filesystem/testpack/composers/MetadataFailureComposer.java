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
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.filesystem.testpack.CompositeChaosMetadataFailure;

/** L2 composer for {@link CompositeChaosMetadataFailure}. */
public final class MetadataFailureComposer implements L2Composer<CompositeChaosMetadataFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public MetadataFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosMetadataFailure annotation) {
    final PathPrefix path = resolvePath(annotation.path());
    final RuleHandle handle =
        CompositeFilesystemChaos.standard()
            .advanced()
            .apply(
                container, IoRule.errno(path, IoOperation.OPEN, Errno.EIO, annotation.toxicity()));
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
  public List<String> describe(final CompositeChaosMetadataFailure annotation) {
    return List.of(
        "metadata failure (EIO on open) — inode block error, journal replay damage, or degraded RAID member",
        "path=" + annotation.path(),
        "toxicity=" + annotation.toxicity(),
        "severity=MODERATE — partial open failures; applications should retry with backoff");
  }

  private static PathPrefix resolvePath(final String path) {
    if ("*".equals(path)) {
      return PathPrefix.wildcard();
    }
    return PathPrefix.path(path);
  }
}
