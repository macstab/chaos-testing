/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.libchaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.model.MemoryPressureInfo;
import com.macstab.chaos.core.spi.MemoryChaosStrategy;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

import lombok.extern.slf4j.Slf4j;

/**
 * VM-syscall-level memory-chaos strategy backed by {@code libchaos-memory} via {@link
 * LibchaosTransport}.
 *
 * <p>Implements both {@link MemoryChaosStrategy} (so it composes with the cgroups strategy) and
 * {@link AdvancedMemoryChaos} (so it surfaces libchaos-memory's full capability set). Pre-flight
 * preparation must happen before {@code container.start()} — this strategy does not start anything
 * itself.
 *
 * <p><strong>Portable verbs are unsupported.</strong> The {@link
 * com.macstab.chaos.core.api.MemoryChaos} interface's whole-container verbs ({@code setLimit},
 * {@code setPressure}, {@code stress}, {@code getCurrentUsage}, {@code getPressure}) target cgroups
 * and have no libchaos-memory analogue — they all throw {@link ChaosUnsupportedOperationException}.
 * The composite routes them to the cgroups strategy.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class LibchaosMemoryChaos implements AdvancedMemoryChaos, MemoryChaosStrategy {

  private final LibchaosTransport transport;
  private final RuleRegistry registry;
  private final AtomicLong ownerCounter;

  /** Default constructor — uses {@code LibchaosLib.MEMORY}. */
  public LibchaosMemoryChaos() {
    this(new LibchaosTransport(LibchaosLib.MEMORY));
  }

  /** Package-private for testing. */
  LibchaosMemoryChaos(final LibchaosTransport transport) {
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.registry = new RuleRegistry();
    this.ownerCounter = new AtomicLong(0L);
  }

  // ==================== ChaosProvider ====================

  @Override
  public boolean isSupported() {
    return true;
  }

  @Override
  public void installTools(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    // No-op: libchaos-memory is installed pre-start via LibchaosTransport.prepare().
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      return;
    }
    removeAll(container);
  }

  // ==================== MemoryChaosStrategy ====================

  @Override
  public boolean supports(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    try {
      return transport.isActive(container);
    } catch (final RuntimeException ex) {
      log.debug("isActive probe failed; reporting unsupported", ex);
      return false;
    }
  }

  // ==================== MemoryChaos (portable verbs — unsupported) ====================

  @Override
  public void setLimit(final GenericContainer<?> container, final String limit) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-memory cannot model whole-container cgroups memory limits; the composite must "
            + "route setLimit() to the cgroups strategy.");
  }

  @Override
  public void setPressure(final GenericContainer<?> container, final String threshold) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-memory cannot model cgroups memory.high; the composite must route "
            + "setPressure() to the cgroups strategy.");
  }

  @Override
  public void stress(final GenericContainer<?> container, final String size) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-memory does not run stress-ng; the composite must route stress() to the "
            + "cgroups strategy.");
  }

  @Override
  public long getCurrentUsage(final GenericContainer<?> container) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-memory does not expose memory.current; the composite must route "
            + "getCurrentUsage() to the cgroups strategy.");
  }

  @Override
  public MemoryPressureInfo getPressure(final GenericContainer<?> container) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-memory does not expose memory.pressure (PSI); the composite must route "
            + "getPressure() to the cgroups strategy.");
  }

  // ==================== AdvancedMemoryChaos: generic ====================

  @Override
  public RuleHandle apply(final GenericContainer<?> container, final MemoryRule rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    requirePrepared(container);
    return applySingle(container, rule);
  }

  @Override
  public List<RuleHandle> applyAll(
      final GenericContainer<?> container, final List<MemoryRule> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    rules.forEach(r -> Objects.requireNonNull(r, "rule must not be null"));
    requirePrepared(container);
    final List<RuleHandle> handles = new ArrayList<>(rules.size());
    for (final MemoryRule r : rules) {
      handles.add(applySingle(container, r));
    }
    return handles;
  }

  @Override
  public void remove(final GenericContainer<?> container, final RuleHandle handle) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(handle, "handle must not be null");
    requirePrepared(container);
    registry
        .remove(container, handle)
        .ifPresent(entry -> transport.removeRules(container, entry.handle().owner()));
  }

  @Override
  public void removeAll(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    requirePrepared(container);
    for (final RuleRegistry.Entry e : registry.removeAll(container)) {
      try {
        transport.removeRules(container, e.handle().owner());
      } catch (final ChaosOperationFailedException ex) {
        log.warn("Failed to remove rule {} during removeAll; continuing", e.handle().owner(), ex);
      }
    }
  }

  // ==================== AdvancedMemoryChaos: raw escape hatches ====================

  @Override
  public RuleHandle errno(
      final GenericContainer<?> container,
      final MemorySelector selector,
      final MmapErrno errno,
      final double probability) {
    return apply(container, MemoryRule.errno(selector, errno, probability));
  }

  @Override
  public RuleHandle latency(
      final GenericContainer<?> container, final MemorySelector selector, final Duration delay) {
    return apply(container, MemoryRule.latency(selector, delay));
  }

  // ==================== Heap / allocation ====================

  @Override
  public RuleHandle failHeapAllocation(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, probability));
  }

  @Override
  public RuleHandle failLargeAllocation(
      final GenericContainer<?> container, final double probability) {
    return apply(container, MemoryRule.errno(MemorySelector.MMAP, MmapErrno.ENOMEM, probability));
  }

  @Override
  public RuleHandle simulateOomKiller(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.WILDCARD, MmapErrno.ENOMEM, probability));
  }

  @Override
  public RuleHandle simulateMemoryPressure(
      final GenericContainer<?> container, final double probability) {
    return apply(container, MemoryRule.errno(MemorySelector.MMAP, MmapErrno.ENOMEM, probability));
  }

  @Override
  public RuleHandle slowHeapAllocation(final GenericContainer<?> container, final Duration delay) {
    return apply(container, MemoryRule.latency(MemorySelector.MMAP_ANON, delay));
  }

  // ==================== File mapping / dlopen ====================

  @Override
  public RuleHandle failFileMapping(final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.MMAP_FILE, MmapErrno.ENOMEM, probability));
  }

  @Override
  public RuleHandle failFileMapping(
      final GenericContainer<?> container, final MmapErrno errno, final double probability) {
    return apply(container, MemoryRule.errno(MemorySelector.MMAP_FILE, errno, probability));
  }

  @Override
  public RuleHandle failLibraryLoad(final GenericContainer<?> container, final double probability) {
    return failFileMapping(container, probability);
  }

  @Override
  public RuleHandle failPluginLoad(final GenericContainer<?> container, final double probability) {
    return failFileMapping(container, probability);
  }

  @Override
  public RuleHandle slowFileMapping(final GenericContainer<?> container, final Duration delay) {
    return apply(container, MemoryRule.latency(MemorySelector.MMAP_FILE, delay));
  }

  // ==================== Thread / stack ====================

  @Override
  public RuleHandle failThreadCreation(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, probability));
  }

  @Override
  public RuleHandle failGuardPageSetup(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.MPROTECT, MmapErrno.ENOMEM, probability));
  }

  // ==================== Page permission (mprotect) ====================

  @Override
  public RuleHandle failPermissionChange(
      final GenericContainer<?> container, final MmapErrno errno, final double probability) {
    return apply(container, MemoryRule.errno(MemorySelector.MPROTECT, errno, probability));
  }

  @Override
  public RuleHandle failJitCompilation(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.MPROTECT, MmapErrno.EACCES, probability));
  }

  @Override
  public RuleHandle slowPermissionChange(
      final GenericContainer<?> container, final Duration delay) {
    return apply(container, MemoryRule.latency(MemorySelector.MPROTECT, delay));
  }

  // ==================== Kernel hints (madvise) ====================

  @Override
  public RuleHandle failMadvise(
      final GenericContainer<?> container, final MmapErrno errno, final double probability) {
    return apply(container, MemoryRule.errno(MemorySelector.MADVISE, errno, probability));
  }

  @Override
  public RuleHandle failHugepageHint(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.MADVISE, MmapErrno.EINVAL, probability));
  }

  @Override
  public RuleHandle failPagePurge(final GenericContainer<?> container, final double probability) {
    return apply(
        container, MemoryRule.errno(MemorySelector.MADVISE, MmapErrno.ENOMEM, probability));
  }

  @Override
  public RuleHandle slowMadvise(final GenericContainer<?> container, final Duration delay) {
    return apply(container, MemoryRule.latency(MemorySelector.MADVISE, delay));
  }

  // ==================== Cleanup ====================

  @Override
  public RuleHandle failUnmap(final GenericContainer<?> container, final double probability) {
    return apply(container, MemoryRule.errno(MemorySelector.MUNMAP, MmapErrno.EINVAL, probability));
  }

  @Override
  public RuleHandle simulateLeak(final GenericContainer<?> container, final double probability) {
    return failUnmap(container, probability);
  }

  // ==================== Internal helpers ====================

  private RuleHandle applySingle(final GenericContainer<?> container, final MemoryRule rule) {
    final RuleHandle handle = new RuleHandle(nextOwner());
    transport.addRule(container, handle.owner(), MemoryRuleSerializer.serialize(rule));
    registry.register(container, new RuleRegistry.Entry(handle, rule));
    return handle;
  }

  private String nextOwner() {
    return "r" + ownerCounter.incrementAndGet();
  }

  private void requirePrepared(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      throw new LibchaosNotPreparedException(LibchaosLib.MEMORY.getShortName(), container);
    }
  }
}
