/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.connection.strategy.libchaos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.connection.api.RuleHandle;
import com.macstab.chaos.connection.model.NetRule;

/**
 * Per-container registry of rules applied by {@link LibchaosNetConnectionChaos}.
 *
 * <p>Tracks {@code (handle, rule, toxicName)} triples so that:
 *
 * <ul>
 *   <li>Advanced API removals ({@code remove(handle)}) can verify the handle belongs to this
 *       strategy and recover the underlying rule for diagnostics.
 *   <li>Portable {@link com.macstab.chaos.core.api.ConnectionChaos#removeToxic} can match by
 *       toxic-name tag (the tag is non-null only for portable-verb-applied rules).
 *   <li>{@code reset()} / {@code removeAll()} can wipe everything for a container without leaking
 *       ownership of unrelated rules.
 * </ul>
 *
 * <p>Thread-safe — backed by {@link ConcurrentHashMap}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class RuleRegistry {

  /** A registered rule entry. {@code toxicName} is {@code null} for advanced-API applications. */
  record Entry(RuleHandle handle, NetRule rule, String toxicName) {
    Entry {
      Objects.requireNonNull(handle, "handle must not be null");
      Objects.requireNonNull(rule, "rule must not be null");
      // toxicName intentionally nullable
    }
  }

  private final Map<GenericContainer<?>, Map<RuleHandle, Entry>> byContainer =
      new ConcurrentHashMap<>();

  void register(final GenericContainer<?> container, final Entry entry) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(entry, "entry must not be null");
    byContainer
        .computeIfAbsent(container, k -> new ConcurrentHashMap<>())
        .put(entry.handle(), entry);
  }

  Optional<Entry> remove(final GenericContainer<?> container, final RuleHandle handle) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(handle, "handle must not be null");
    final Map<RuleHandle, Entry> inner = byContainer.get(container);
    if (inner == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(inner.remove(handle));
  }

  List<Entry> removeAll(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    final Map<RuleHandle, Entry> inner = byContainer.remove(container);
    if (inner == null) {
      return List.of();
    }
    return new ArrayList<>(inner.values());
  }

  /**
   * Removes and returns every entry tagged with {@code toxicName} for the given container. Untagged
   * entries (advanced-API applications) are not affected.
   */
  List<Entry> removeByToxicName(final GenericContainer<?> container, final String toxicName) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(toxicName, "toxicName must not be null");
    final Map<RuleHandle, Entry> inner = byContainer.get(container);
    if (inner == null) {
      return List.of();
    }
    final List<Entry> removed = new ArrayList<>();
    inner
        .values()
        .removeIf(
            entry -> {
              if (toxicName.equals(entry.toxicName())) {
                removed.add(entry);
                return true;
              }
              return false;
            });
    return removed;
  }

  /**
   * @return immutable snapshot of the entries for a container (never null)
   */
  List<Entry> snapshot(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    final Map<RuleHandle, Entry> inner = byContainer.get(container);
    if (inner == null) {
      return List.of();
    }
    return List.copyOf(inner.values());
  }
}
