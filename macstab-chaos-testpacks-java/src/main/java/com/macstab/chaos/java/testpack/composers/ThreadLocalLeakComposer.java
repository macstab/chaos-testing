/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.java.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.java.testpack.CompositeChaosThreadLocalLeak;
import com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

import lombok.extern.slf4j.Slf4j;

/** L2 composer for {@link CompositeChaosThreadLocalLeak}. */
@Slf4j
public final class ThreadLocalLeakComposer implements L2Composer<CompositeChaosThreadLocalLeak> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public ThreadLocalLeakComposer() {}

  private static final int ENTRIES_PER_THREAD = 10;
  private static final int VALUE_SIZE_BYTES = 64 * 1024; // 64 KB per entry

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosThreadLocalLeak annotation) {
    final String id =
        JvmPlanAccumulator.instance()
            .mintScenarioId(CompositeChaosThreadLocalLeak.class.getSimpleName());
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .description(
                "L2: thread-local leak — "
                    + annotation.threadCount()
                    + " threads with "
                    + ENTRIES_PER_THREAD
                    + " × "
                    + VALUE_SIZE_BYTES
                    + " B entries each")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.THREAD_LOCAL_LEAK))
            .effect(ChaosEffect.threadLocalLeak(ENTRIES_PER_THREAD, VALUE_SIZE_BYTES))
            .activationPolicy(ActivationPolicy.always())
            .build();
    final String scenarioId = JvmPlanAccumulator.instance().addScenario(container, scenario);
    return List.of(scenarioId);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof String scenarioId) {
        try {
          JvmPlanAccumulator.instance().removeScenario(container, scenarioId);
        } catch (final Exception e) {
          log.warn("ThreadLocalLeakComposer.removeAll: failed to remove {}", scenarioId, e);
        }
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosThreadLocalLeak annotation) {
    return List.of(
        "thread-local leak — large byte arrays stored in ThreadLocal maps across "
            + annotation.threadCount()
            + " threads",
        "severity=MODERATE — gradual heap accumulation; OOM after extended run without GC pressure");
  }
}
