/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.testpack.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.filesystem.testpack.CompositeChaosReadOnlyFilesystem;

/** L2 composer for {@link CompositeChaosReadOnlyFilesystem}. */
public final class ReadOnlyFilesystemComposer
    implements L2Composer<CompositeChaosReadOnlyFilesystem> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ReadOnlyFilesystemComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosReadOnlyFilesystem annotation) {
    final PathPrefix path = resolvePath(annotation.path());
    final double toxicity = annotation.toxicity();
    final AdvancedFilesystemChaos adv = CompositeFilesystemChaos.standard().advanced();
    final List<Object> handles = new ArrayList<>();
    handles.add(
        adv.apply(container, IoRule.errno(path, IoOperation.WRITE, Errno.EACCES, toxicity)));
    handles.add(
        adv.apply(container, IoRule.errno(path, IoOperation.RENAME_FROM, Errno.EACCES, toxicity)));
    handles.add(
        adv.apply(container, IoRule.errno(path, IoOperation.UNLINK, Errno.EACCES, toxicity)));
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
  public List<String> describe(final CompositeChaosReadOnlyFilesystem annotation) {
    return List.of(
        "read-only filesystem (EACCES on write/rename/unlink) — kernel remounted filesystem read-only after media error",
        "path=" + annotation.path(),
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — all writes fail; operator must remount read-write to restore service");
  }

  private static PathPrefix resolvePath(final String path) {
    if ("*".equals(path)) {
      return PathPrefix.wildcard();
    }
    return PathPrefix.path(path);
  }
}
