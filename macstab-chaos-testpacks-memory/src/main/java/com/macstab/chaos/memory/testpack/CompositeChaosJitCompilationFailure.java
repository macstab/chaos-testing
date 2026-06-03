/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.testpack;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL2;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 *
 * <p>Simulates JIT compiler code-page protection failure by injecting {@code EACCES} on
 * {@code mprotect} calls at 80% probability. JIT compilers — including the JVM HotSpot C1/C2
 * compiler, V8 (used by Node.js), LuaJIT, PCRE2, and .NET CLR — generate machine code into writable
 * buffers, then call {@code mprotect(PROT_EXEC)} to mark the pages executable before jumping into
 * them. If {@code mprotect} fails with {@code EACCES}, a robust runtime falls back to the
 * interpreter; a fragile one crashes with a SIGSEGV or throws an unhandled
 * {@code InternalError}.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#failJitCompilation(container, 0.8)} via libchaos-memory,
 * which installs a {@code mprotect:ERRNO:EACCES@0.8} rule. In production {@code mprotect} failures
 * arise from SELinux/AppArmor policies that deny {@code PROT_EXEC} on heap pages
 * ({@code execheap} / {@code execmem} denials), from seccomp profiles that block mprotect, or from
 * kernel patches that enforce W^X (write-XOR-execute) strictly.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * At 80% toxicity most JIT compilation attempts fail. A JVM that cannot JIT-compile hot methods will
 * fall back to interpreted execution — CPU usage spikes and throughput drops substantially. Runtimes
 * that treat JIT failure as fatal (some V8 configurations, early LuaJIT versions) will terminate the
 * process. Services that have never exercised the "JIT disabled" path will exhibit unexpected
 * behaviour changes; manual intervention is typically required to tune the runtime or the
 * security policy.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code mprotect(PROT_EXEC)} as the JIT code-page protection mechanism is documented in
 * {@code mprotect(2)} and in HotSpot internals ({@code CodeCache} allocation in
 * {@code src/hotspot/share/code/codeCache.cpp}). The interaction with SELinux is covered in the Red
 * Hat Security Guide and the {@code execmem} boolean documentation. V8's handling of JIT failures is
 * in the V8 design documents. PCRE2 JIT is documented in the PCRE2 man-page ({@code pcre2jit(3)}).
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosJitCompilationFailure
 * class JitFallbackTest {
 *
 *   @Test
 *   void jvmFallsBackToInterpreterOnJitFailure(GenericContainer<?> javaApp) {
 *     // Service must remain responsive even without JIT-compiled code
 *     assertThat(client.request()).isSuccessful();
 *     assertThat(javaApp.getLogs()).doesNotContain("Internal Error");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosJitCompilationFailure.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.JitCompilationFailureComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosJitCompilationFailure {

  /**
   * Probability that any intercepted {@code mprotect} call returns {@code EACCES}. Must be in
   * {@code (0.0, 1.0]}. Default {@code 0.8} (80%) — high enough to force most JIT paths through the
   * fallback interpreter.
   */
  double toxicity() default 0.8;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosJitCompilationFailure[] value();
  }
}
