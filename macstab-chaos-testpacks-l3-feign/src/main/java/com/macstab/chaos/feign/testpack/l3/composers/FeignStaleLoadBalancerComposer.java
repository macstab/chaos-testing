/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.feign.testpack.l3.IncidentChaosFeignStaleLoadBalancer;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Composer for {@link IncidentChaosFeignStaleLoadBalancer}.
 *
 * <p>Injects ECONNREFUSED on CONNECT at the configured toxicity to simulate dead pod IPs
 * served by the Spring Cloud LoadBalancer stale cache during a rolling deploy.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class FeignStaleLoadBalancerComposer implements L3Composer<IncidentChaosFeignStaleLoadBalancer> {

    public FeignStaleLoadBalancerComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosFeignStaleLoadBalancer ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosFeignStaleLoadBalancer ann) {
        return List.of(
                "Feign Stale Load Balancer — 30s stale cache serves dead pod IPs after rolling deploy",
                "connection: CONNECT ECONNREFUSED toxicity=" + ann.toxicity() + " (dead pod IPs from stale LB cache)",
                "severity=MODERATE — 30s window of ECONNREFUSED during every rolling deploy");
    }
}
