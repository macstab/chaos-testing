/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.testpack.composers;

import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.extension.L2Composer;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.memory.CompositeMemoryChaos;
import com.macstab.chaos.memory.api.AdvancedMemoryChaos;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.testpack.CompositeChaosLibraryLoadFailure;

/** L2 composer for {@link CompositeChaosLibraryLoadFailure}. */
public final class LibraryLoadFailureComposer
    implements L2Composer<CompositeChaosLibraryLoadFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public LibraryLoadFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosLibraryLoadFailure annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.failLibraryLoad(container, annotation.toxicity());
    return List.of(handle);
  }

  @Override
  public void removeAll(final GenericContainer<?> container, final List<Object> handles) {
    for (final Object h : handles) {
      if (h instanceof RuleHandle ruleHandle) {
        new LibchaosTransport(LibchaosLib.MEMORY).removeRules(container, ruleHandle.owner());
      }
    }
  }

  @Override
  public List<String> describe(final CompositeChaosLibraryLoadFailure annotation) {
    return List.of(
        "Library load failure — EBADF at 100% on file-backed mmap calls (dlopen / ELF segment mapping fails)",
        "toxicity=" + annotation.toxicity() + " — file-backed mmap returns EBADF at this rate; dlopen() affected proportionally",
        "severity=SEVERE — plugin loaders, JNI libraries, and lazy-loaded shared objects will fail to load");
  }
}
