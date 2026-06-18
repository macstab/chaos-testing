/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.system.testpack.l3.IncidentChaosSystemInodeExhaustion;

/**
 * Composer for {@link IncidentChaosSystemInodeExhaustion}.
 *
 * <p>Injects {@code ENOSPC} on every {@code open()} call to reproduce the compound failure profile
 * of inode exhaustion where {@code df -h} shows plenty of disk space but all file operations fail.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SystemInodeExhaustionComposer
    implements L3Composer<IncidentChaosSystemInodeExhaustion> {

  public SystemInodeExhaustionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosSystemInodeExhaustion ann) {
    final List<Object> handles = new ArrayList<>();

    final var fsAdv = CompositeFilesystemChaos.standard().advanced();
    handles.add(
        fsAdv.apply(
            container,
            IoRule.errno(
                PathPrefix.wildcard(),
                IoOperation.OPEN,
                com.macstab.chaos.filesystem.model.Errno.ENOSPC,
                ann.toxicity())));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosSystemInodeExhaustion ann) {
    return List.of(
        "System Inode Exhaustion — disk 40% used but every file open fails ENOSPC",
        "filesystem: OPEN ENOSPC toxicity="
            + ann.toxicity()
            + " (inode slots exhausted, df looks fine)",
        "severity=CRITICAL — all log writes, temp files, and sockets fail; df -h shows green; requires df -i");
  }
}
