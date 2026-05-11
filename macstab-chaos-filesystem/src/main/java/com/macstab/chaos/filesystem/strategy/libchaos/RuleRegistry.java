/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.libchaos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.IoRule;

/**
 * Per-container registry of rules applied by {@link LibchaosIoFilesystemChaos}.
 *
 * <p>Tracks {@code (handle, rule)} pairs so that:
 *
 * <ul>
 *   <li>Advanced API removals ({@code remove(handle)}) can verify the handle belongs to this
 *       strategy and recover the underlying rule for diagnostics.
 *   <li>{@code removeAll()} can wipe everything for a container without leaking ownership of
 *       unrelated rules.
 * </ul>
 *
 * <p>The filesystem registry is intentionally simpler than its connection-module counterpart — the
 * connection registry carries a {@code toxicName} tag for Toxiproxy-portable verb attribution, but
 * the filesystem module has no portable advanced verb to attribute, so the entry is just {@code
 * (handle, rule)}.
 *
 * <p>Thread-safe — backed by {@link ConcurrentHashMap}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class RuleRegistry {

  /** A registered rule entry. */
  record Entry(RuleHandle handle, IoRule rule) {
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
