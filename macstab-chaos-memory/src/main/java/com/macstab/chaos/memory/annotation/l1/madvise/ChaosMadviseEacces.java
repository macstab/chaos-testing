/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.annotation.l1.madvise;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.memory.annotation.l1.MemoryErrnoBinding;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

/**
 * L1 chaos primitive: inject {@code EACCES} on every libchaos-memory-intercepted {@code madvise}
 * call inside the container, gated by {@link #probability}.
 *
 * <p><strong>What this simulates:</strong> permission denied — typical of selinux / apparmor /
 * capability restrictions in the request path.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @ChaosMadviseEacces(probability = 0.001)
 * class MyTest {
 *   @Test
 *   void appHandlesAllocationFailure(RedisConnectionInfo info) { ... }
 * }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> {@code 1e-4} to {@code 1e-3} mirrors realistic
 * production rates for {@code EACCES} on {@code madvise}; {@code 1.0} produces ungrokable failures
 * during container init.
 *
 * <p><strong>Scope:</strong> {@link #id()} binds to a single container; the default empty string
 * applies the rule to every container in the test class.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see MemoryErrnoBinding
 * @see com.macstab.chaos.memory.model.MemoryRule#errno(MemorySelector, MmapErrno, double)
 */
@Repeatable(ChaosMadviseEacces.Repeatable.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@ChaosL1(translator = "com.macstab.chaos.memory.annotation.l1.translators.MemoryErrnoTranslator")
@MemoryErrnoBinding(selector = MemorySelector.MADVISE, errno = MmapErrno.EACCES)
public @interface ChaosMadviseEacces {

  /**
   * @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]}
   */
  double probability() default 1.0;

  /**
   * @return container id to bind to ({@code ""} = every matching container in the test class)
   */
  String id() default "";

  /**
   * @return policy when the active backend cannot honour libchaos-memory ({@code ERROR} fails at
   *     {@code beforeAll}; {@code ABORT} marks the test class YELLOW/aborted)
   */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;

  /**
   * Container that enables repeating this annotation on the same element. Do not use directly —
   * Java adds it automatically when the annotation appears more than once on the same target.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @ChaosMadviseEacces(id = "primary",  probability = 0.001)
   * @ChaosMadviseEacces(id = "replica",  probability = 0.01)
   * class MultiContainerTest { ... }
   * }</pre>
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
    java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD,
    java.lang.annotation.ElementType.FIELD
  })
  @interface Repeatable {
    ChaosMadviseEacces[] value();
  }
}
