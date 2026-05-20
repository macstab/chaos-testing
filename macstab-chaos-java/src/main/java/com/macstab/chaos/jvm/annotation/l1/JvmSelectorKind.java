/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.annotation.l1;

import java.util.Set;

import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * L1-tier projection of the typed {@link ChaosSelector} hierarchy onto the family of selectors
 * the L1 annotations target. Each enum constant knows how to build its corresponding selector
 * given the operation set encoded in the L1 annotation.
 *
 * <p>Selectors that require additional parameters beyond an operation set (e.g.
 * {@code MethodSelector} which requires a class/method name pattern) are intentionally omitted
 * — those remain in the imperative {@code ChaosControlPlane} API. The L1 surface covers the
 * 16 stateless selector kinds whose only mandatory parameter is the operation set.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public enum JvmSelectorKind {

  /** {@link ChaosSelector#thread(Set, ChaosSelector.ThreadKind)} with {@code ANY} thread kind. */
  THREAD {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.thread(operations, ChaosSelector.ThreadKind.ANY);
    }
  },

  /** {@link ChaosSelector#executor(Set)}. */
  EXECUTOR {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.executor(operations);
    }
  },

  /** {@link ChaosSelector#queue(Set)}. */
  QUEUE {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.queue(operations);
    }
  },

  /** {@link ChaosSelector#async(Set)}. */
  ASYNC {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.async(operations);
    }
  },

  /** {@link ChaosSelector#scheduling(Set)}. */
  SCHEDULING {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.scheduling(operations);
    }
  },

  /** {@link ChaosSelector#shutdown(Set)}. */
  SHUTDOWN {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.shutdown(operations);
    }
  },

  /** {@link ChaosSelector#monitor(Set)}. */
  MONITOR {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.monitor(operations);
    }
  },

  /** {@link ChaosSelector#jvmRuntime(Set)}. */
  JVM_RUNTIME {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.jvmRuntime(operations);
    }
  },

  /** {@link ChaosSelector#nio(Set)}. */
  NIO {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.nio(operations);
    }
  },

  /** {@link ChaosSelector#network(Set)}. */
  NETWORK {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.network(operations);
    }
  },

  /** {@link ChaosSelector#threadLocal(Set)}. */
  THREAD_LOCAL {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.threadLocal(operations);
    }
  },

  /** {@link ChaosSelector#httpClient(Set)}. */
  HTTP_CLIENT {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.httpClient(operations);
    }
  },

  /** {@link ChaosSelector#jdbc(OperationType...)}. */
  JDBC {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.jdbc(operations.toArray(new OperationType[0]));
    }
  },

  /** {@link ChaosSelector#dns(Set)}. */
  DNS {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.dns(operations);
    }
  },

  /** {@link ChaosSelector#ssl(Set)}. */
  SSL {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.ssl(operations);
    }
  },

  /** {@link ChaosSelector#fileIo(Set)}. */
  FILE_IO {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.fileIo(operations);
    }
  },

  /** {@link ChaosSelector#classLoading(Set, com.macstab.chaos.jvm.api.NamePattern)} with {@code any()} pattern. */
  CLASS_LOADING {
    @Override
    public ChaosSelector build(final Set<OperationType> operations) {
      return ChaosSelector.classLoading(operations, com.macstab.chaos.jvm.api.NamePattern.any());
    }
  };

  /**
   * Build the typed {@link ChaosSelector} for this kind given the operations encoded in the L1
   * annotation's {@link JvmInterceptorBinding}.
   *
   * @param operations the operation set declared by the binding (always non-empty)
   * @return the typed selector ready to wrap in a ChaosScenario
   */
  public abstract ChaosSelector build(Set<OperationType> operations);
}
