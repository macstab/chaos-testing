/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.kubernetes.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.kubernetes.testpack.l3.IncidentChaosK8sRollingUpdateRst;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Composer for {@link IncidentChaosK8sRollingUpdateRst}.
 *
 * <p>Injects RECV ECONNRESET at the configured toxicity to reproduce the TCP RST window caused
 * by iptables endpoint propagation lag during Kubernetes rolling updates.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class K8sRollingUpdateRstComposer implements L3Composer<IncidentChaosK8sRollingUpdateRst> {

    public K8sRollingUpdateRstComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosK8sRollingUpdateRst ann) {
        final List<Object> handles = new ArrayList<>();
        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container, NetRule.errno(Endpoint.wildcard(), NetOperation.RECV, Errno.ECONNRESET, ann.toxicity())));
        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosK8sRollingUpdateRst ann) {
        return List.of(
                "K8s Rolling Update RST — iptables lag sends TCP RST on in-flight requests",
                "connection: RECV ECONNRESET toxicity=" + ann.toxicity(),
                "severity=CRITICAL — affects every rolling deploy");
    }
}
