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
import com.macstab.chaos.memory.testpack.CompositeChaosJitCompilationFailure;

/** L2 composer for {@link CompositeChaosJitCompilationFailure}. */
public final class JitCompilationFailureComposer
    implements L2Composer<CompositeChaosJitCompilationFailure> {

  /** Public no-arg constructor required by the L2 composer contract. */
  public JitCompilationFailureComposer() {}

  @Override
  public List<Object> apply(
      final GenericContainer<?> container, final CompositeChaosJitCompilationFailure annotation) {
    final AdvancedMemoryChaos adv = CompositeMemoryChaos.standard().advanced();
    final RuleHandle handle = adv.failJitCompilation(container, annotation.toxicity());
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
  public List<String> describe(final CompositeChaosJitCompilationFailure annotation) {
    return List.of(
        "JIT compilation failure — EACCES on mprotect at rate "
            + annotation.toxicity()
            + " (JIT code page protection denied)",
        "toxicity="
            + annotation.toxicity()
            + " — simulates SELinux execheap denial or W^X enforcement; forces interpreter fallback",
        "severity=SEVERE — JIT-capable runtimes (JVM, V8, LuaJIT, PCRE2) must fall back to interpreted execution");
  }
}
