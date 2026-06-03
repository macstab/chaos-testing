/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.testpack.l3.composers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.redis.testpack.l3.IncidentChaosRedisOomEviction;
import com.macstab.chaos.connection.CompositeConnectionChaos;
import com.macstab.chaos.connection.model.Endpoint;
import com.macstab.chaos.connection.model.Errno;
import com.macstab.chaos.connection.model.NetOperation;
import com.macstab.chaos.connection.model.NetRule;
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosRedisOomEviction}.
 *
 * <p>Combines anonymous mmap ENOMEM, connection ECONNRESET, and JVM OutOfMemoryError to
 * reproduce the compound failure profile of a Redis maxmemory eviction storm.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisOomEvictionComposer implements L3Composer<IncidentChaosRedisOomEviction> {

    public RedisOomEvictionComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosRedisOomEviction ann) {
        final List<Object> handles = new ArrayList<>();

        final var mem = CompositeMemoryChaos.standard().advanced();
        handles.add(mem.apply(container,
                MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, ann.probability())));

        final var adv = CompositeConnectionChaos.standard().advanced();
        handles.add(adv.apply(container,
                NetRule.errno(Endpoint.wildcard(), NetOperation.CONNECT, Errno.ECONNRESET, ann.toxicity())));

        final String scenarioId = JvmPlanAccumulator.instance().mintScenarioId("RedisOomEviction");
        final var selector = ChaosSelector.method(
                EnumSet.of(OperationType.METHOD_EXIT),
                NamePattern.prefix("redis"),
                NamePattern.any());
        final var scenario = ChaosScenario.builder(scenarioId)
                .description("Redis OOM eviction — inject OutOfMemoryError on Redis client methods")
                .selector(selector)
                .effect(ChaosEffect.injectException("java.lang.OutOfMemoryError", "Redis maxmemory eviction OOM"))
                .activationPolicy(ActivationPolicy.always())
                .build();
        handles.add(JvmPlanAccumulator.instance().addScenario(container, scenario));

        return handles;
    }

    @Override
    public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
        RuleRemover.removeAll(container, handles);
    }

    @Override
    public List<String> describe(final IncidentChaosRedisOomEviction ann) {
        return List.of(
                "Redis OOM Eviction — maxmemory eviction storm with client disconnect and host OOM",
                "memory: MMAP_ANON → ENOMEM, probability=" + ann.probability(),
                "connection: CONNECT → ECONNRESET, toxicity=" + ann.toxicity(),
                "jvm: OutOfMemoryError(\"Redis maxmemory eviction OOM\") on class prefix 'redis'",
                "severity=SEVERE — combined memory pressure and connection instability");
    }
}
