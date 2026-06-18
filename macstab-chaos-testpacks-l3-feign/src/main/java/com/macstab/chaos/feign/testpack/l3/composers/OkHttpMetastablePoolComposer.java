/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.feign.testpack.l3.IncidentChaosOkHttpMetastablePool;

/**
 * Composer for {@link IncidentChaosOkHttpMetastablePool}.
 *
 * <p>Injects RECV latency at 100% toxicity to make connections slow but surviving, causing the
 * OkHttp connection pool to bias toward those slow connections — reproducing the metastable
 * feedback loop described in OkHttp #8244 and the HotOS 2021 metastable failures paper.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class OkHttpMetastablePoolComposer
    implements L3Composer<IncidentChaosOkHttpMetastablePool> {

  public OkHttpMetastablePoolComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosOkHttpMetastablePool ann) {
    final List<Object> handles = new ArrayList<>();

    final var adv = CompositeConnectionChaos.standard().advanced();
    handles.add(
        adv.apply(
            container,
            NetRule.latency(
                Endpoint.wildcard(), NetOperation.RECV, Duration.ofMillis(ann.latencyMs()), 1.0)));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosOkHttpMetastablePool ann) {
    return List.of(
        "OkHttp Metastable Pool — pool biases toward slow connections → self-reinforcing slowdown",
        "connection: RECV latency="
            + ann.latencyMs()
            + "ms (slow connections survive idle cleanup)",
        "severity=SEVERE — self-sustaining: slow connections accumulate, amplify slowdown (OkHttp #8244, HotOS 2021)");
  }
}
