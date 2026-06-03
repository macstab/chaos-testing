/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.redis.testpack.l3.IncidentChaosRedisFailoverStorm;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.dns.CompositeDnsChaos;
import com.macstab.chaos.dns.model.DnsRule;
import com.macstab.chaos.dns.model.DnsSelector;
import com.macstab.chaos.dns.model.EaiErrno;
import com.macstab.chaos.time.CompositeTimeChaos;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeRule;

/**
 * Composer for {@link IncidentChaosRedisFailoverStorm}.
 *
 * <p>Applies connection ECONNREFUSED, transient DNS EAI_AGAIN, and a realtime clock skew to
 * reproduce the compound failure profile of a Redis Sentinel election storm with concurrent
 * NTP correction.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisFailoverStormComposer implements L3Composer<IncidentChaosRedisFailoverStorm> {

    public RedisFailoverStormComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosRedisFailoverStorm ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNREFUSED, ann.toxicity())));

        final var dns = CompositeDnsChaos.standard().advanced();
        handles.add(dns.apply(container,
                DnsRule.eai(DnsSelector.anyForward(), EaiErrno.EAI_AGAIN)));

        final var time = CompositeTimeChaos.standard().advanced();
        handles.add(time.apply(container,
                TimeRule.offset(TimeClock.REALTIME, Duration.ofMillis(ann.clockSkewMs()), 1.0)));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosRedisFailoverStorm ann) {
        return List.of(
                "Redis Failover Storm — Sentinel election storm with concurrent NTP correction",
                "connection: CONNECT → ECONNREFUSED, toxicity=" + ann.toxicity(),
                "dns: EAI_AGAIN on every forward lookup",
                "time: REALTIME skew +" + ann.clockSkewMs() + "ms",
                "severity=CRITICAL — full client connectivity loss during election window");
    }
}
