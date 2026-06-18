/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.filesystem.CompositeFilesystemChaos;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;
import com.macstab.chaos.system.testpack.l3.IncidentChaosSystemFdExhaustion;

/**
 * Composer for {@link IncidentChaosSystemFdExhaustion}.
 *
 * <p>Injects {@code EMFILE} on file opens and {@code ECONNREFUSED} on new socket connections to
 * reproduce the compound failure profile of per-process file descriptor limit exhaustion: health
 * probes pass, the pod is never restarted, but every new operation fails.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SystemFdExhaustionComposer
    implements L3Composer<IncidentChaosSystemFdExhaustion> {

  public SystemFdExhaustionComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosSystemFdExhaustion ann) {
    final List<Object> handles = new ArrayList<>();

    final var fsAdv = CompositeFilesystemChaos.standard().advanced();
    handles.add(
        fsAdv.apply(
            container,
            IoRule.errno(
                PathPrefix.wildcard(),
                IoOperation.OPEN,
                com.macstab.chaos.filesystem.model.Errno.EMFILE,
                ann.toxicity())));

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(),
                NetOperation.CONNECT,
                com.macstab.chaos.connection.model.Errno.ECONNREFUSED,
                ann.toxicity())));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosSystemFdExhaustion ann) {
    final String fdErrno = com.macstab.chaos.filesystem.model.Errno.EMFILE.name();
    return List.of(
        "System FD Exhaustion — fd limit hit: new files and sockets fail, health checks pass",
        "filesystem: OPEN " + fdErrno + " toxicity=" + ann.toxicity() + " (fd slot exhaustion)",
        "connection: CONNECT ECONNREFUSED toxicity="
            + ann.toxicity()
            + " (new socket creation fails)",
        "severity=CRITICAL — health probe succeeds (uses existing socket); pod never restarted; only new operations fail");
  }
}
