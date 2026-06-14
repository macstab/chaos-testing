/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cache.testpack.l3.composers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.cache.testpack.l3.IncidentChaosCacheSerializationMismatch;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Composer for {@link IncidentChaosCacheSerializationMismatch}.
 *
 * <p>Injects {@code InvalidClassException} on the cache deserialization layer to reproduce the
 * rolling-deploy scenario where new pods cannot deserialize old Redis entries, causing 100% cache
 * miss until the TTL window expires.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class CacheSerializationMismatchComposer
    implements L3Composer<IncidentChaosCacheSerializationMismatch> {

  public CacheSerializationMismatchComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final IncidentChaosCacheSerializationMismatch ann) {
    final List<Object> handles = new ArrayList<>();

    final String id = JvmPlanAccumulator.instance().mintScenarioId("CacheSerializationMismatch");
    final ChaosScenario sc =
        ChaosScenario.builder(id)
            .description("Cache Serialization Mismatch — rolling deploy deserialization failure")
            .selector(
                ChaosSelector.method(
                    EnumSet.of(OperationType.METHOD_EXIT),
                    NamePattern.prefix(ann.classPattern()),
                    NamePattern.any()))
            .effect(
                ChaosEffect.injectException(
                    "java.io.InvalidClassException", "serialVersionUID mismatch"))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handles.add(JvmPlanAccumulator.instance().addScenario(container, sc));

    return handles;
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    RuleRemover.removeAll(container, handles);
  }

  @Override
  public List<String> describe(final IncidentChaosCacheSerializationMismatch ann) {
    return List.of(
        "Cache Serialization Mismatch — rolling deploy deserialization failure → 100% cache miss",
        "jvm: InvalidClassException injection on '" + ann.classPattern() + "' (METHOD_EXIT)",
        "severity=SEVERE — 100% cache miss on new pods until old entries expire (Spring Boot #38959)");
  }
}
