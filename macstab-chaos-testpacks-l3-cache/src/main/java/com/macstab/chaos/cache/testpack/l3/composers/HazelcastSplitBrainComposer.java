/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.cache.testpack.l3.IncidentChaosHazelcastSplitBrain;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;

/**
 * Composer for {@link IncidentChaosHazelcastSplitBrain}.
 *
 * <p>Applies RECV ECONNRESET to disrupt member heartbeats and EAI_AGAIN DNS errors to prevent
 * member discovery, reproducing the network partition that causes two Hazelcast partitions to
 * operate independently and produce silent data loss on healing.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class HazelcastSplitBrainComposer implements L3Composer<IncidentChaosHazelcastSplitBrain> {

    public HazelcastSplitBrainComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosHazelcastSplitBrain ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.errno(Endpoint.wildcard(), NetOperation.RECV, Errno.ECONNRESET, ann.toxicity())));

        final var dns = CompositeDnsChaos.standard().advanced();
        handles.add(dns.apply(container,
                DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosHazelcastSplitBrain ann) {
        return List.of(
                "Hazelcast Split-Brain — network partition creates two partitions with split writes",
                "connection: RECV ECONNRESET toxicity=" + ann.toxicity() + " (member disconnect)",
                "dns: EAI_AGAIN (member discovery failure)",
                "severity=CRITICAL — silent data loss on partition healing; no exception visible to application");
    }
}
