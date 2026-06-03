/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.cache.testpack.l3.IncidentChaosCacheWarmingFailure;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Composer for {@link IncidentChaosCacheWarmingFailure}.
 *
 * <p>Injects ECONNREFUSED on cache connections and high RECV latency on surviving backend
 * connections to reproduce the cold-start failure where a backend sized for cached load is
 * overwhelmed when the cache cannot warm up.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class CacheWarmingFailureComposer implements L3Composer<IncidentChaosCacheWarmingFailure> {

    public CacheWarmingFailureComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosCacheWarmingFailure ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosCacheWarmingFailure ann) {
        return List.of(
                "Cache Warming Failure — cold start overwhelms backend sized for cached load",
                "connection: CONNECT ECONNREFUSED toxicity=" + ann.toxicity() + " + RECV latency=" + ann.latencyMs() + "ms",
                "severity=CRITICAL — Netflix re-engineered cold-start flow after this; self-sustaining once triggered");
    }
}
