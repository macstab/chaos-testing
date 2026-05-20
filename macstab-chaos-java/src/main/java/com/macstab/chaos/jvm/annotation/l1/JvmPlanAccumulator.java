/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.testcontainers.containers.GenericContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.macstab.chaos.jvm.CompositeJavaChaos;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-container accumulator that maintains the active JVM chaos plan across multiple L1
 * annotations on a test class.
 *
 * <p><strong>Why this exists.</strong> The cross-container wire to the JVM agent is wholesale
 * plan replacement ({@link CompositeJavaChaos#applyPlan} writes a JSON file the agent's poller
 * hot-reloads), not per-scenario activate/deactivate. So when a test class declares multiple L1
 * annotations (e.g. {@code @ChaosJdbcExecuteDelay} + {@code @ChaosHttpClientSendInjectException}),
 * the framework can't push them as independent rules — it has to maintain the current set of
 * active scenarios per container and re-push the merged plan on every change.
 *
 * <p><strong>Lifecycle contract.</strong> Each L1 translator's {@code apply()} calls
 * {@link #addScenario}, which inserts the scenario into the container's active set and re-pushes
 * the merged plan. {@code remove()} calls {@link #removeScenario}, which filters the scenario
 * out and re-pushes. When the active set goes empty, {@link CompositeJavaChaos#clearPlan} is
 * called instead of pushing an empty plan (the agent treats both equivalently, but clearPlan
 * is cheaper).
 *
 * <p><strong>Thread safety.</strong> The accumulator is a singleton with synchronised access to
 * the per-container scenario map. The map uses {@link WeakHashMap} keyed by container identity so
 * a stopped container's state is GC'd automatically. Scenario IDs are mint-once via {@link
 * AtomicLong}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class JvmPlanAccumulator {

  /** Singleton instance — translators get this via {@link #instance()}. */
  private static final JvmPlanAccumulator INSTANCE = new JvmPlanAccumulator();

  /** @return the singleton accumulator */
  public static JvmPlanAccumulator instance() {
    return INSTANCE;
  }

  private final Object lock = new Object();
  private final Map<GenericContainer<?>, Map<String, ChaosScenario>> state = new WeakHashMap<>();
  private final AtomicLong scenarioCounter = new AtomicLong();
  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final CompositeJavaChaos chaos = new CompositeJavaChaos();

  private JvmPlanAccumulator() {}

  /**
   * Add {@code scenario} to {@code container}'s active set, re-serialise the merged plan, and
   * push via {@link CompositeJavaChaos#applyPlan}.
   *
   * <p><strong>Rollback semantics.</strong> If the push fails (mock container in tests; agent
   * not loaded; network error writing the plan file), the scenario is removed from the active
   * set <em>before</em> the exception propagates — the accumulator's state always reflects
   * what the agent has been told, never what we tried to tell it. Without rollback the next
   * caller would re-push a plan containing the failed scenario and either repeat the error or
   * silently activate it once the underlying issue clears.
   *
   * @param container the running container the JVM agent is attached to
   * @param scenario the scenario to activate
   * @return the scenario's id (used as the opaque handle for {@link #removeScenario})
   */
  public String addScenario(final GenericContainer<?> container, final ChaosScenario scenario) {
    synchronized (lock) {
      final Map<String, ChaosScenario> active =
          state.computeIfAbsent(container, k -> new LinkedHashMap<>());
      active.put(scenario.id(), scenario);
      try {
        pushMergedPlan(container, active);
      } catch (final RuntimeException push) {
        // Roll back the addition so the in-memory state matches the agent's view.
        active.remove(scenario.id());
        if (active.isEmpty()) {
          state.remove(container);
        }
        throw push;
      }
    }
    return scenario.id();
  }

  /**
   * Remove the scenario identified by {@code scenarioId} from {@code container}'s active set
   * and re-push the resulting plan (or {@link CompositeJavaChaos#clearPlan} when the set goes
   * empty).
   *
   * @param container the container previously seeded by {@link #addScenario}
   * @param scenarioId the id returned from {@link #addScenario}
   */
  public void removeScenario(final GenericContainer<?> container, final String scenarioId) {
    synchronized (lock) {
      final Map<String, ChaosScenario> active = state.get(container);
      if (active == null) {
        return;
      }
      active.remove(scenarioId);
      if (active.isEmpty()) {
        state.remove(container);
        try {
          chaos.clearPlan(container);
        } catch (final Exception e) {
          log.warn("clearPlan failed on container; treating as best-effort cleanup", e);
        }
      } else {
        pushMergedPlan(container, active);
      }
    }
  }

  /** Mint a fresh unique scenario id derived from the L1 annotation simple name. */
  public String mintScenarioId(final String annotationSimpleName) {
    return annotationSimpleName + "-" + scenarioCounter.incrementAndGet();
  }

  private void pushMergedPlan(
      final GenericContainer<?> container, final Map<String, ChaosScenario> active) {
    final List<ChaosScenario> scenarios = new ArrayList<>(active.values());
    final ChaosPlan plan = new ChaosPlan(null, null, scenarios);
    try {
      final String json = mapper.writeValueAsString(plan);
      chaos.applyPlan(container, json);
    } catch (final Exception e) {
      throw new IllegalStateException(
          "Failed to serialise / push JVM chaos plan with "
              + scenarios.size()
              + " scenarios. Container: "
              + container.getDockerImageName(),
          e);
    }
  }

  // ==================== test-only introspection ====================

  /**
   * Active scenario ids for a container — exposed for unit-test assertions. Returns an immutable
   * snapshot.
   */
  Map<String, ChaosScenario> activeScenarios(final GenericContainer<?> container) {
    synchronized (lock) {
      final Map<String, ChaosScenario> active = state.get(container);
      return active == null
          ? Collections.emptyMap()
          : Collections.unmodifiableMap(new LinkedHashMap<>(active));
    }
  }
}
