/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.libchaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.model.ProcessInfo;
import com.macstab.chaos.core.model.Signal;
import com.macstab.chaos.core.spi.ProcessChaosStrategy;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.process.api.AdvancedProcessChaos;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;

import lombok.extern.slf4j.Slf4j;

/**
 * Process-lifecycle chaos strategy backed by {@code libchaos-process} via {@link
 * LibchaosTransport}.
 *
 * <p>Implements both {@link ProcessChaosStrategy} (so it composes with the cgroups strategy) and
 * {@link AdvancedProcessChaos} (so it surfaces libchaos-process's full capability set including the
 * unique {@code FAIL_AFTER} effect). Pre-flight preparation must happen before {@code
 * container.start()}.
 *
 * <p><strong>Portable verbs are unsupported.</strong> The {@link
 * com.macstab.chaos.core.api.ProcessChaos} interface's whole-container verbs ({@code kill}, {@code
 * pause}, {@code limitProcesses}, {@code listProcesses}) target the container runtime and the
 * {@code ps}/signal toolchain — they have no libchaos-process analogue. All four throw {@link
 * ChaosUnsupportedOperationException}; the composite routes them to cgroups.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class LibchaosProcessChaos implements AdvancedProcessChaos, ProcessChaosStrategy {

  private final LibchaosTransport transport;
  private final RuleRegistry registry;
  private final AtomicLong ownerCounter;

  /** Default constructor — uses {@code LibchaosLib.PROCESS}. */
  public LibchaosProcessChaos() {
    this(new LibchaosTransport(LibchaosLib.PROCESS));
  }

  /** Package-private for testing. */
  LibchaosProcessChaos(final LibchaosTransport transport) {
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
    // No-op: libchaos-process is installed pre-start via LibchaosTransport.prepare().
  }

  @Override
  public void reset(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      return;
    }
    removeAll(container);
  }

  // ==================== ProcessChaosStrategy ====================

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

  // ==================== ProcessChaos (portable verbs — unsupported) ====================

  @Override
  public void kill(
      final GenericContainer<?> container, final String processName, final Signal signal) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-process cannot send signals from outside the target process; the composite "
            + "must route kill() to the cgroups strategy.");
  }

  @Override
  public void pause(
      final GenericContainer<?> container, final String processName, final Duration duration) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-process cannot SIGSTOP/SIGCONT processes; the composite must route pause() "
            + "to the cgroups strategy.");
  }

  @Override
  public void limitProcesses(final GenericContainer<?> container, final int maxProcesses) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-process cannot set cgroups pids.max; the composite must route "
            + "limitProcesses() to the cgroups strategy. (For per-syscall thread-pool "
            + "exhaustion semantics, use AdvancedProcessChaos.exhaustThreadPool / "
            + "exhaustProcessLimit instead.)");
  }

  @Override
  public List<ProcessInfo> listProcesses(final GenericContainer<?> container) {
    throw new ChaosUnsupportedOperationException(
        "libchaos-process does not enumerate processes; the composite must route "
            + "listProcesses() to the cgroups strategy.");
  }

  // ==================== AdvancedProcessChaos: generic ====================

  @Override
  public RuleHandle apply(final GenericContainer<?> container, final ProcessRule rule) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    requirePrepared(container);
    return applySingle(container, rule);
  }

  @Override
  public List<RuleHandle> applyAll(
      final GenericContainer<?> container, final List<ProcessRule> rules) {
    Objects.requireNonNull(container, "container must not be null");
    Objects.requireNonNull(rules, "rules must not be null");
    rules.forEach(r -> Objects.requireNonNull(r, "rule must not be null"));
    requirePrepared(container);
    final List<RuleHandle> handles = new ArrayList<>(rules.size());
    for (final ProcessRule r : rules) {
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

  // ==================== AdvancedProcessChaos: raw escape hatches ====================

  @Override
  public RuleHandle errno(
      final GenericContainer<?> container,
      final ProcessSelector selector,
      final ProcessErrno errno,
      final double probability) {
    return apply(container, ProcessRule.errno(selector, errno, probability));
  }

  @Override
  public RuleHandle latency(
      final GenericContainer<?> container, final ProcessSelector selector, final Duration delay) {
    return apply(container, ProcessRule.latency(selector, delay));
  }

  @Override
  public RuleHandle failAfter(
      final GenericContainer<?> container,
      final ProcessSelector selector,
      final ProcessErrno errno,
      final long count) {
    return apply(container, ProcessRule.failAfter(selector, errno, count));
  }

  // ==================== Thread creation ====================

  @Override
  public RuleHandle failThreadCreation(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container,
        ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN, probability));
  }

  @Override
  public RuleHandle failThreadCreation(
      final GenericContainer<?> container, final ProcessErrno errno, final double probability) {
    return apply(container, ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, errno, probability));
  }

  @Override
  public RuleHandle exhaustThreadPool(final GenericContainer<?> container, final long maxThreads) {
    return apply(
        container,
        ProcessRule.failAfter(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN, maxThreads));
  }

  @Override
  public RuleHandle slowThreadCreation(final GenericContainer<?> container, final Duration delay) {
    return apply(container, ProcessRule.latency(ProcessSelector.PTHREAD_CREATE, delay));
  }

  // ==================== Fork / process creation ====================

  @Override
  public RuleHandle failFork(final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN, probability));
  }

  @Override
  public RuleHandle failFork(
      final GenericContainer<?> container, final ProcessErrno errno, final double probability) {
    return apply(container, ProcessRule.errno(ProcessSelector.FORK, errno, probability));
  }

  @Override
  public RuleHandle exhaustProcessLimit(final GenericContainer<?> container, final long maxForks) {
    return apply(
        container, ProcessRule.failAfter(ProcessSelector.FORK, ProcessErrno.EAGAIN, maxForks));
  }

  @Override
  public RuleHandle slowFork(final GenericContainer<?> container, final Duration delay) {
    return apply(container, ProcessRule.latency(ProcessSelector.FORK, delay));
  }

  // ==================== posix_spawn ====================

  @Override
  public RuleHandle failSpawn(final GenericContainer<?> container, final double probability) {
    return apply(
        container,
        ProcessRule.errno(ProcessSelector.POSIX_SPAWN, ProcessErrno.ENOENT, probability));
  }

  @Override
  public RuleHandle failSpawn(
      final GenericContainer<?> container, final ProcessErrno errno, final double probability) {
    return apply(container, ProcessRule.errno(ProcessSelector.POSIX_SPAWN, errno, probability));
  }

  @Override
  public RuleHandle failSpawnByPath(final GenericContainer<?> container, final double probability) {
    return apply(
        container,
        ProcessRule.errno(ProcessSelector.POSIX_SPAWNP, ProcessErrno.ENOENT, probability));
  }

  @Override
  public RuleHandle slowSpawn(final GenericContainer<?> container, final Duration delay) {
    return apply(container, ProcessRule.latency(ProcessSelector.POSIX_SPAWN, delay));
  }

  // ==================== exec ====================

  @Override
  public RuleHandle failExec(final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.EXECVE, ProcessErrno.ENOENT, probability));
  }

  @Override
  public RuleHandle failExec(
      final GenericContainer<?> container, final ProcessErrno errno, final double probability) {
    return apply(container, ProcessRule.errno(ProcessSelector.EXECVE, errno, probability));
  }

  @Override
  public RuleHandle failExecPermission(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.EXECVE, ProcessErrno.EACCES, probability));
  }

  @Override
  public RuleHandle failExecMissingBinary(
      final GenericContainer<?> container, final double probability) {
    return failExec(container, probability);
  }

  @Override
  public RuleHandle failExecTooLarge(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.EXECVE, ProcessErrno.E2BIG, probability));
  }

  @Override
  public RuleHandle failExecFdLimit(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.EXECVE, ProcessErrno.EMFILE, probability));
  }

  @Override
  public RuleHandle failExecRelative(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.EXECVEAT, ProcessErrno.ENOENT, probability));
  }

  @Override
  public RuleHandle slowExec(final GenericContainer<?> container, final Duration delay) {
    return apply(container, ProcessRule.latency(ProcessSelector.EXECVE, delay));
  }

  // ==================== wait ====================

  @Override
  public RuleHandle failWait(final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.WAITPID, ProcessErrno.ECHILD, probability));
  }

  @Override
  public RuleHandle failWait(
      final GenericContainer<?> container, final ProcessErrno errno, final double probability) {
    return apply(container, ProcessRule.errno(ProcessSelector.WAITPID, errno, probability));
  }

  @Override
  public RuleHandle signalInterruptWait(
      final GenericContainer<?> container, final double probability) {
    return apply(
        container, ProcessRule.errno(ProcessSelector.WAITPID, ProcessErrno.EINTR, probability));
  }

  @Override
  public RuleHandle phantomWait(final GenericContainer<?> container, final double probability) {
    return failWait(container, probability);
  }

  @Override
  public RuleHandle slowWait(final GenericContainer<?> container, final Duration delay) {
    return apply(container, ProcessRule.latency(ProcessSelector.WAITPID, delay));
  }

  // ==================== Internal helpers ====================

  private RuleHandle applySingle(final GenericContainer<?> container, final ProcessRule rule) {
    final RuleHandle handle = new RuleHandle(nextOwner());
    transport.addRule(container, handle.owner(), ProcessRuleSerializer.serialize(rule));
    registry.register(container, new RuleRegistry.Entry(handle, rule));
    return handle;
  }

  private String nextOwner() {
    return "r" + ownerCounter.incrementAndGet();
  }

  private void requirePrepared(final GenericContainer<?> container) {
    Objects.requireNonNull(container, "container must not be null");
    if (!transport.isActive(container)) {
      throw new LibchaosNotPreparedException(LibchaosLib.PROCESS.getShortName(), container);
    }
  }
}
