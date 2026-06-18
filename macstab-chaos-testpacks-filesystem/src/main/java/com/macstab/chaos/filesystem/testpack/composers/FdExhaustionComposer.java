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
import com.macstab.chaos.filesystem.testpack.CompositeChaosFdExhaustion;

/** L2 composer for {@link CompositeChaosFdExhaustion}. */
public final class FdExhaustionComposer implements L2Composer<CompositeChaosFdExhaustion> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public FdExhaustionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosFdExhaustion annotation) {
    final PathPrefix path = resolvePath(annotation.path());
    final RuleHandle handle =
        CompositeFilesystemChaos.standard()
            .advanced()
            .apply(
                container,
                IoRule.errno(path, IoOperation.OPEN, Errno.EMFILE, annotation.toxicity()));
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
  public List<String> describe(final CompositeChaosFdExhaustion annotation) {
    return List.of(
        "fd exhaustion (EMFILE on open) — fd leak, connection-pool overrun, or cgroup prlimit",
        "path=" + annotation.path(),
        "toxicity=" + annotation.toxicity(),
        "severity=SEVERE — no new fds can be opened; process restart or ulimit increase required");
  }

  private static PathPrefix resolvePath(final String path) {
    if ("*".equals(path)) {
      return PathPrefix.wildcard();
    }
    return PathPrefix.path(path);
  }
}
