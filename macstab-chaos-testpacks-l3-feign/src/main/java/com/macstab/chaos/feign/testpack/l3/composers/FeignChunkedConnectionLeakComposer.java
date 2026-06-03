/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3.composers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.feign.testpack.l3.IncidentChaosFeignChunkedConnectionLeak;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Composer for {@link IncidentChaosFeignChunkedConnectionLeak}.
 *
 * <p>Injects a RECV timeout that hangs the chunked response indefinitely, preventing the
 * connection from ever being returned to the Apache HttpClient pool — reproducing the silent
 * pool-drain described in OpenFeign issue #1474.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class FeignChunkedConnectionLeakComposer implements L3Composer<IncidentChaosFeignChunkedConnectionLeak> {

    public FeignChunkedConnectionLeakComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosFeignChunkedConnectionLeak ann) {
        final List<Object> handles = new ArrayList<>();

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.latency(Endpoint.wildcard(), NetOperation.RECV, Duration.ofDays(1), 1.0)));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosFeignChunkedConnectionLeak ann) {
        return List.of(
                "Feign Chunked Connection Leak — chunked response never closed → pool drains silently",
                "connection: RECV timeout (chunked response hangs, connection not returned to pool)",
                "severity=SEVERE — silent pool drain; no exception; pod eventually OOMs (OpenFeign #1474)");
    }
}
