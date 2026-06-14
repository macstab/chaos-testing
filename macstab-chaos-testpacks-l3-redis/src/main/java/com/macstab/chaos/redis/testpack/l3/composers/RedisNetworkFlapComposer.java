/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.redis.testpack.l3.IncidentChaosRedisNetworkFlap;

/**
 * Composer for {@link IncidentChaosRedisNetworkFlap}.
 *
 * <p>Applies ECONNRESET on both CONNECT and RECV to reproduce rapid TCP reset cycling that triggers
 * Sentinel election storms where master appears to change every 200ms.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisNetworkFlapComposer implements L3Composer<IncidentChaosRedisNetworkFlap> {

  public RedisNetworkFlapComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosRedisNetworkFlap ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNRESET, ann.toxicity())));
    handles.add(
        adv.apply(
            container,
            NetRule.errno(
                Endpoint.wildcard(), NetOperation.RECV, Errno.ECONNRESET, ann.toxicity())));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosRedisNetworkFlap ann) {
    return List.of(
        "Redis Network Flap — rapid TCP reset cycling triggers Sentinel election storm",
        "connection: CONNECT+RECV ECONNRESET toxicity=" + ann.toxicity(),
        "severity=CRITICAL — clients see master change every 200ms; write chaos");
  }
}
