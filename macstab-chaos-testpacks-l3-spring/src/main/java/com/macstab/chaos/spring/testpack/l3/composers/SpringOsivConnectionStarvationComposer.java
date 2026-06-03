/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.spring.testpack.l3.IncidentChaosSpringOsivConnectionStarvation;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Composer for {@link IncidentChaosSpringOsivConnectionStarvation}.
 *
 * <p>Applies RECV latency on all connections to reproduce the connection pool exhaustion caused
 * by Spring's Open Session In View default — each connection is held open during HTTP response
 * serialization, draining the pool at traffic spikes.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class SpringOsivConnectionStarvationComposer implements L3Composer<IncidentChaosSpringOsivConnectionStarvation> {

    public SpringOsivConnectionStarvationComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosSpringOsivConnectionStarvation ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosSpringOsivConnectionStarvation ann) {
        return List.of(
                "Spring OSIV Connection Starvation — connection held during HTTP response serialization exhausts pool",
                "connection: RECV latency=" + ann.latencyMs() + "ms (OSIV extends connection lifespan)",
                "severity=SEVERE — default Spring Boot config triggers this at traffic spikes; often mistaken for DB slowness");
    }
}
