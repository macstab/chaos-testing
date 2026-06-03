/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.system.testpack.l3.IncidentChaosSystemTcpTimeWaitStorm;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Composer for {@link IncidentChaosSystemTcpTimeWaitStorm}.
 *
 * <p>Injects {@code ECONNREFUSED} on outbound {@code connect()} calls to reproduce the
 * compound failure profile of TCP TIME_WAIT ephemeral port exhaustion.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SystemTcpTimeWaitStormComposer implements L3Composer<IncidentChaosSystemTcpTimeWaitStorm> {

    public SystemTcpTimeWaitStormComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosSystemTcpTimeWaitStorm ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT,
                        com.macstab.chaos.connection.model.Errno.ECONNREFUSED, ann.toxicity())));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosSystemTcpTimeWaitStorm ann) {
        return List.of(
                "System TCP TIME_WAIT Storm — ephemeral port exhaustion from high connection churn",
                "connection: CONNECT ECONNREFUSED toxicity=" + ann.toxicity() + " (ephemeral port range exhausted)",
                "severity=SEVERE — new outbound connections fail with EADDRNOTAVAIL; existing connections unaffected; restarts don't help (OkHttp #4354)");
    }
}
