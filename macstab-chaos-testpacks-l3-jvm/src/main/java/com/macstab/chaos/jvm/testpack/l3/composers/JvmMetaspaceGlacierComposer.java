/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3.composers;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import com.macstab.chaos.core.extension.L3Composer;
import com.macstab.chaos.jvm.testpack.l3.IncidentChaosJvmMetaspaceGlacier;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Composer for {@link IncidentChaosJvmMetaspaceGlacier}.
 *
 * <p>Applies a MetaspacePressure stressor with retained strong references to reproduce the
 * compound failure profile of a classloader leak exhausting Metaspace over hours while
 * heap metrics remain green.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class JvmMetaspaceGlacierComposer implements L3Composer<IncidentChaosJvmMetaspaceGlacier> {

    public JvmMetaspaceGlacierComposer() {}

    @Override
    public List<Object> apply(final GenericContainer<?> container, final IncidentChaosJvmMetaspaceGlacier ann) {
        final List<Object> handles = new ArrayList<>();

        final String id = JvmPlanAccumulator.instance().mintScenarioId("JvmMetaspaceGlacier");
        final ChaosScenario scenario = ChaosScenario.builder(id)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.METASPACE))
                .effect(ChaosEffect.metaspacePressure(ann.generatedClassCount(), ann.fieldsPerClass()))
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
    public List<String> describe(final IncidentChaosJvmMetaspaceGlacier ann) {
        return List.of(
                "JVM Metaspace Glacier — classloader leak exhausts Metaspace over hours",
                "jvm: MetaspacePressure " + ann.generatedClassCount() + " classes × " + ann.fieldsPerClass() + " fields (strong refs retained)",
                "severity=SEVERE — heap metrics green throughout; discovered only at Metaspace OOM");
    }
}
