/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.mmap;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * L1 chaos primitive: inject {@code EMFILE} on every libchaos-memory-intercepted
 * {@code mmap} call inside the container, gated by {@link #probability}.
 *
 * <p><strong>What this simulates:</strong> per-process file-descriptor limit reached — typical of fd-leaks in connection pools.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMmapEmfile(probability = 0.001)
 * class MyTest {
 *   @Test
 *   void appHandlesAllocationFailure(RedisConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> {@code 1e-4} to {@code 1e-3} mirrors realistic
 * production rates for {@code EMFILE} on {@code mmap}; {@code 1.0} produces
 * ungrokable failures during container init.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds to a single container; the default empty
 * string applies the rule to every container in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MMAP, errno = MmapErrno.EMFILE)
public @interface ChaosMmapEmfile {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container in the test class)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-memory ({@code ERROR}
   *     fails at {@code beforeAll}; {@code ABORT} marks the test class YELLOW/aborted)
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
