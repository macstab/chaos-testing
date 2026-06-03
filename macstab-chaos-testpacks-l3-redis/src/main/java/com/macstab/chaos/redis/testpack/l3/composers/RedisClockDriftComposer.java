/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.redis.testpack.l3.IncidentChaosRedisClockDrift;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeRule;

/**
 * Composer for {@link IncidentChaosRedisClockDrift}.
 *
 * <p>Applies a realtime clock skew and SEND-path latency to simulate the TTL drift and
 * WATCH() CAS failure storms caused by clock divergence between application and Redis nodes.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisClockDriftComposer implements L3Composer<IncidentChaosRedisClockDrift> {

    public RedisClockDriftComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosRedisClockDrift ann) {
        final List<Object> handles = new ArrayList<>();

        final var time = CompositeTimeChaos.standard().advanced();
        handles.add(time.apply(container,
                TimeRule.offset(TimeClock.REALTIME, Duration.ofMillis(ann.skewMs()), ann.probability())));

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.SEND, Duration.ofMillis(ann.latencyMs()), 1.0)));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosRedisClockDrift ann) {
        return List.of(
                "Redis Clock Drift — TTL corruption and WATCH() CAS failures under clock skew",
                "time: REALTIME skew +" + ann.skewMs() + "ms, probability=" + ann.probability(),
                "connection: SEND latency " + ann.latencyMs() + "ms",
                "severity=MODERATE — elevated CAS failure rate and key expiry divergence");
    }
}
