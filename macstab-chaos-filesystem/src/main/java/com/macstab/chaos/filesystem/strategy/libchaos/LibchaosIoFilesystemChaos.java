/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.libchaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.spi.FilesystemChaosStrategy;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.filesystem.api.AdvancedFilesystemChaos;
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

import lombok.extern.slf4j.Slf4j;

/**
 * Syscall-level filesystem-chaos strategy backed by {@code libchaos-io} via {@link
 * LibchaosTransport}.
 *
 * <p>Implements both {@link FilesystemChaosStrategy} (so it composes with the shell strategy) and
 * {@link AdvancedFilesystemChaos} (so it surfaces libchaos-io's full capability set). Pre-flight
 * preparation must happen before {@code container.start()} — see {@link LibchaosTransport#prepare};
 * this strategy does not start anything itself.
 *
 * <p><strong>Routing decisions</strong> live in the composite, not here. The portable verbs {@code
 * fillDisk} and {@code injectPermissionErrors} throw {@link ChaosUnsupportedOperationException}
 * because they model whole-container resource state, not per-syscall fault injection — the
 * composite routes them to the shell strategy.
 *
 * <p><strong>Thread-safety:</strong> safe for concurrent use across containers; per-container
 * mutation goes through {@link RuleRegistry}'s concurrent maps and {@code LibchaosTransport}'s
 * shell-serialised exec.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class LibchaosIoFilesystemChaos
    implements AdvancedFilesystemChaos, FilesystemChaosStrategy {

  private final LibchaosTransport transport;
  private final RuleRegistry registry;
  private final AtomicLong ownerCounter;

  /** Default constructor — uses {@code LibchaosLib.IO}. */
  public LibchaosIoFilesystemChaos() {
    this(new LibchaosTransport(LibchaosLib.IO));
  }

  /**
   * Package-private for testing.
   *
   * @param transport pre-configured transport (typically a mock)
   */
  LibchaosIoFilesystemChaos(final LibchaosTransport transport) {
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
    // No-op: libchaos-io is installed pre-start via LibchaosTransport.prepare(),
    // not at runtime via package install.
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      return;
    }
    removeAll(container);
  }

  // ==================== FilesystemChaosStrategy ====================

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

  // ==================== FilesystemChaos (portable verbs — unsupported) ====================

  @Override
  public void fillDisk(final GenericContainer<?> container, final String size) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-io cannot model whole-container disk exhaustion; the composite must route "
            + "fillDisk() to the shell strategy.");
  }

  @Override
  public void injectPermissionErrors(
      final GenericContainer<?> container, final String path, final double rate) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-io cannot model whole-container chmod; the composite must route "
            + "injectPermissionErrors() to the shell strategy. Use failOpen(EACCES) or "
            + "failWrite(EACCES) for per-path permission faults.");
  }

  // ==================== AdvancedFilesystemChaos: generic ====================

  @Override
  public RuleHandle apply(final GenericContainer<?> container, final IoRule rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    requirePrepared(container);
    return applySingle(container, rule);
  }

  @Override
  public List<RuleHandle> applyAll(final GenericContainer<?> container, final List<IoRule> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    rules.forEach(r -> Objects.requireNonNull(r, "rule must not be null"));
    requirePrepared(container);
    final List<RuleHandle> handles = new ArrayList<>(rules.size());
    for (final IoRule r : rules) {
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

  // ==================== AdvancedFilesystemChaos: convenience ====================

  @Override
  public RuleHandle failOpen(
      final GenericContainer<?> container,
      final PathPrefix path,
      final Errno errno,
      final double probability) {
    return apply(container, IoRule.errno(path, IoOperation.OPEN, errno, probability));
  }

  @Override
  public RuleHandle failWrite(
      final GenericContainer<?> container,
      final PathPrefix path,
      final Errno errno,
      final double probability) {
    return apply(container, IoRule.errno(path, IoOperation.WRITE, errno, probability));
  }

  @Override
  public RuleHandle failRead(
      final GenericContainer<?> container,
      final PathPrefix path,
      final Errno errno,
      final double probability) {
    return apply(container, IoRule.errno(path, IoOperation.READ, errno, probability));
  }

  @Override
  public RuleHandle exhaustFds(final GenericContainer<?> container, final double probability) {
    return apply(
        container,
        IoRule.errno(PathPrefix.wildcard(), IoOperation.OPEN, Errno.EMFILE, probability));
  }

  @Override
  public RuleHandle makeReadOnly(
      final GenericContainer<?> container, final PathPrefix path, final double probability) {
    return apply(container, IoRule.errno(path, IoOperation.WRITE, Errno.EROFS, probability));
  }

  @Override
  public RuleHandle fillQuota(
      final GenericContainer<?> container, final PathPrefix path, final double probability) {
    return apply(container, IoRule.errno(path, IoOperation.WRITE, Errno.EDQUOT, probability));
  }

  @Override
  public RuleHandle tornWrite(
      final GenericContainer<?> container, final PathPrefix path, final double probability) {
    return apply(container, IoRule.torn(path, IoOperation.WRITE, probability));
  }

  @Override
  public RuleHandle corruptRead(
      final GenericContainer<?> container, final PathPrefix path, final double probability) {
    return apply(container, IoRule.corrupt(path, IoOperation.READ, probability));
  }

  @Override
  public RuleHandle slowFsync(
      final GenericContainer<?> container, final PathPrefix path, final Duration delay) {
    return apply(container, IoRule.latency(path, IoOperation.FSYNC, delay));
  }

  @Override
  public RuleHandle failFsync(
      final GenericContainer<?> container,
      final PathPrefix path,
      final Errno errno,
      final double probability) {
    return apply(container, IoRule.errno(path, IoOperation.FSYNC, errno, probability));
  }

  @Override
  public RuleHandle slowOpen(
      final GenericContainer<?> container, final PathPrefix path, final Duration delay) {
    return apply(container, IoRule.latency(path, IoOperation.OPEN, delay));
  }

  @Override
  public RuleHandle failRename(
      final GenericContainer<?> container,
      final PathPrefix path,
      final Errno errno,
      final double probability) {
    return apply(container, IoRule.errno(path, IoOperation.RENAME_FROM, errno, probability));
  }

  // ==================== Internal helpers ====================

  private RuleHandle applySingle(final GenericContainer<?> container, final IoRule rule) {
    final RuleHandle handle = new RuleHandle(nextOwner());
    transport.addRule(container, handle.owner(), IoRuleSerializer.serialize(rule));
    registry.register(container, new RuleRegistry.Entry(handle, rule));
    return handle;
  }

  private String nextOwner() {
    return "r" + ownerCounter.incrementAndGet();
  }

  private void requirePrepared(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      throw new LibchaosNotPreparedException(LibchaosLib.IO.getShortName(), container);
    }
  }
}
