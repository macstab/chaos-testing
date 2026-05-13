/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.strategy.libchaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.spi.TimeChaosStrategy;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.time.api.AdvancedTimeChaos;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

import lombok.extern.slf4j.Slf4j;

/**
 * Time-syscall chaos strategy backed by {@code libchaos-time} via {@link LibchaosTransport}.
 *
 * <p>Implements both {@link TimeChaosStrategy} (so it composes with the libfaketime strategy) and
 * {@link AdvancedTimeChaos} (so it surfaces libchaos-time's full capability set including the
 * unique per-clock {@code OFFSET} effect). Pre-flight preparation must happen before {@code
 * container.start()}.
 *
 * <p><strong>Portable verbs are unsupported.</strong> The {@link
 * com.macstab.chaos.core.api.TimeChaos} interface's whole-container verbs ({@code shift}, {@code
 * drift}) model global wall-clock manipulation via libfaketime — they have no libchaos-time
 * analogue. Both throw {@link ChaosUnsupportedOperationException}; the composite routes them to
 * libfaketime.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class LibchaosTimeChaos implements AdvancedTimeChaos, TimeChaosStrategy {

  private final LibchaosTransport transport;
  private final RuleRegistry registry;
  private final AtomicLong ownerCounter;

  /** Default constructor — uses {@code LibchaosLib.TIME}. */
  public LibchaosTimeChaos() {
    this(new LibchaosTransport(LibchaosLib.TIME));
  }

  /** Package-private for testing. */
  LibchaosTimeChaos(final LibchaosTransport transport) {
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
    // No-op: libchaos-time is installed pre-start via LibchaosTransport.prepare().
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      return;
    }
    removeAll(container);
  }

  // ==================== TimeChaosStrategy ====================

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

  // ==================== TimeChaos (portable verbs — unsupported) ====================

  @Override
  public void shift(final GenericContainer<?> container, final Duration offset) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-time cannot shift wall-clock time container-wide; the composite must route "
            + "shift() to the libfaketime strategy. (For per-clock skew use "
            + "AdvancedTimeChaos.skewClock(container, clock, delta).)");
  }

  @Override
  public void drift(final GenericContainer<?> container, final double speedMultiplier) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-time cannot make clocks tick faster/slower; that requires libfaketime's "
            + "x<factor> mode. The composite must route drift() to the libfaketime strategy.");
  }

  // ==================== AdvancedTimeChaos: generic ====================

  @Override
  public RuleHandle apply(final GenericContainer<?> container, final TimeRule rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    requirePrepared(container);
    return applySingle(container, rule);
  }

  @Override
  public List<RuleHandle> applyAll(
      final GenericContainer<?> container, final List<TimeRule> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    rules.forEach(r -> Objects.requireNonNull(r, "rule must not be null"));
    requirePrepared(container);
    final List<RuleHandle> handles = new ArrayList<>(rules.size());
    for (final TimeRule r : rules) {
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

  // ==================== AdvancedTimeChaos: raw escape hatches ====================

  @Override
  public RuleHandle errno(
      final GenericContainer<?> container,
      final TimeSelector selector,
      final TimeErrno errno,
      final double probability) {
    return apply(container, TimeRule.errno(selector, errno, probability));
  }

  @Override
  public RuleHandle latency(
      final GenericContainer<?> container, final TimeSelector selector, final Duration delay) {
    return apply(container, TimeRule.latency(selector, delay));
  }

  @Override
  public RuleHandle offset(
      final GenericContainer<?> container, final TimeClock clock, final Duration delta) {
    return apply(container, TimeRule.offset(clock, delta));
  }

  @Override
  public RuleHandle offset(
      final GenericContainer<?> container,
      final TimeClock clock,
      final Duration delta,
      final double probability) {
    return apply(container, TimeRule.offset(clock, delta, probability));
  }

  // ==================== clock_gettime ====================

  @Override
  public RuleHandle failClockGet(final GenericContainer<?> container, final double probability) {
    return apply(
        container, TimeRule.errno(TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL, probability));
  }

  @Override
  public RuleHandle failClockGetWithErrno(
      final GenericContainer<?> container, final TimeErrno errno, final double probability) {
    return apply(container, TimeRule.errno(TimeSelector.CLOCK_GETTIME, errno, probability));
  }

  @Override
  public RuleHandle slowClockGet(final GenericContainer<?> container, final Duration delay) {
    return apply(container, TimeRule.latency(TimeSelector.CLOCK_GETTIME, delay));
  }

  @Override
  public RuleHandle skewClock(
      final GenericContainer<?> container, final TimeClock clock, final Duration delta) {
    return apply(container, TimeRule.offset(clock, delta));
  }

  // ==================== nanosleep ====================

  @Override
  public RuleHandle failNanosleep(final GenericContainer<?> container, final double probability) {
    return apply(
        container, TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR, probability));
  }

  @Override
  public RuleHandle slowNanosleep(final GenericContainer<?> container, final Duration delay) {
    return apply(container, TimeRule.latency(TimeSelector.NANOSLEEP, delay));
  }

  @Override
  public RuleHandle signalInterruptSleep(
      final GenericContainer<?> container, final double probability) {
    return failNanosleep(container, probability);
  }

  // ==================== usleep ====================

  @Override
  public RuleHandle failUsleep(final GenericContainer<?> container, final double probability) {
    return apply(container, TimeRule.errno(TimeSelector.USLEEP, TimeErrno.EINTR, probability));
  }

  @Override
  public RuleHandle slowUsleep(final GenericContainer<?> container, final Duration delay) {
    return apply(container, TimeRule.latency(TimeSelector.USLEEP, delay));
  }

  @Override
  public RuleHandle signalInterruptMicrosleep(
      final GenericContainer<?> container, final double probability) {
    return failUsleep(container, probability);
  }

  // ==================== Internal helpers ====================

  private RuleHandle applySingle(final GenericContainer<?> container, final TimeRule rule) {
    final RuleHandle handle = new RuleHandle(nextOwner());
    transport.addRule(container, handle.owner(), TimeRuleSerializer.serialize(rule));
    registry.register(container, new RuleRegistry.Entry(handle, rule));
    return handle;
  }

  private String nextOwner() {
    return "r" + ownerCounter.incrementAndGet();
  }

  private void requirePrepared(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      throw new LibchaosNotPreparedException(LibchaosLib.TIME.getShortName(), container);
    }
  }
}
