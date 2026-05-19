/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.annotation.l1.fork;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.chaos.core.extension.ChaosL1;
import com.macstab.chaos.core.extension.OnMissingEnv;
import com.macstab.chaos.process.annotation.l1.ProcessFailAfterBinding;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * L1 chaos primitive: let the first {@link #successesBeforeFailure} libchaos-process-intercepted
 * {@code fork} calls succeed, then fail every subsequent call with {@code EAGAIN}.
 *
 * <p><strong>What this simulates:</strong> resource exhaustion at the fork boundary — typical of fd-leak / thread-leak bugs that succeed indefinitely until the resource pool is drained, at which point every further call fails with EAGAIN.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @RedisStandalone
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @ChaosForkEagainFailAfter(successesBeforeFailure = 128)
 * class MyTest { ... }
 * }</pre>
 *
 * <p>FailAfter is libchaos-process's unique counter-gated effect — distinct from ERRNO
 * (probabilistic) and LATENCY (unconditional). The counter resets on every rule re-apply.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.chaos.process.model.ProcessRule#failAfter(ProcessSelector, ProcessErrno, long)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ChaosL1(translator = "com.macstab.chaos.process.annotation.l1.translators.ProcessFailAfterTranslator")
@ProcessFailAfterBinding(selector = ProcessSelector.FORK, errno = ProcessErrno.EAGAIN)
public @interface ChaosForkEagainFailAfter {

  /** @return number of matched calls allowed to succeed before failure begins ({@code >= 0}) */
  long successesBeforeFailure() default 0L;

  /** @return container id to bind to ({@code ""} = every matching container) */
  String id() default "";

  /** @return policy when the active backend cannot honour libchaos-process */
  OnMissingEnv onMissingEnv() default OnMissingEnv.ERROR;
}
