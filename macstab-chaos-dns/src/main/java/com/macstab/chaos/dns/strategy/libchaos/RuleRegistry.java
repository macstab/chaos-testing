/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.dns.strategy.libchaos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.dns.api.RuleHandle;
import com.macstab.chaos.dns.model.DnsRule;

/**
 * Per-container registry of rules applied by {@link LibchaosDnsChaos}.
 *
 * <p>Thread-safe — backed by {@link ConcurrentHashMap}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class RuleRegistry {

  /** A registered rule entry. */
  record Entry(RuleHandle handle, DnsRule rule) {
    Entry {
      Objects.requireNonNull(handle, "handle must not be null");
      Objects.requireNonNull(rule, "rule must not be null");
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
