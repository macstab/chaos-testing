/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.wildcard;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessErrnoBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * L1 chaos primitive: inject {@code ECHILD} on every libchaos-process-intercepted
 * {@code every interposed process syscall} call inside the container, gated by {@link #probability}.
 *
 * <p><strong>What this simulates:</strong> no child processes — waitpid with no eligible child; typical of double-waits or PID races.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosWildcardEchild(probability = 0.01)
 * class MyTest { ... }
 * }</pre>
 *
 * <p><strong>Probability guidance:</strong> {@code 1e-3} to {@code 1e-2} mirrors realistic
 * production rates for {@code ECHILD} on {@code every interposed process syscall}; {@code 1.0} typically
 * breaks container init or service-start.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.process.model.ProcessRule#errno(ProcessSelector, ProcessErrno, double)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessErrnoTranslator")
@ProcessErrnoBinding(selector = ProcessSelector.WILDCARD, errno = ProcessErrno.ECHILD)
public @interface ChaosWildcardEchild {

  /** @return probability the errno fires when the rule matches, in {@code (0.0, 1.0]} */
  double probability() default 1.0;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-process */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
