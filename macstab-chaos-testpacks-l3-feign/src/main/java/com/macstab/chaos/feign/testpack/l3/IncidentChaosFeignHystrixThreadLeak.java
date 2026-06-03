/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.feign.testpack.l3;

import java.lang.annotation.*;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 * <h2>What this is</h2>
 * <p>Simulates the Feign + Hystrix thread-leak failure: the Hystrix fallback fires on timeout,
 * but the underlying thread remains blocked in {@code socketRead0()} indefinitely. The thread
 * pool drains one thread per timed-out request; no recovery is possible without a pod restart.
 *
 * <h2>Composed of</h2>
 * <ul>
 *   <li>Connection: RECV latency of {@code latencyMs} ms at 100% toxicity — makes every Feign
 *       call exceed the Hystrix timeout threshold
 *   <li>JVM: HystrixTimeoutException injected at METHOD_EXIT on class prefix {@code classPattern}
 *       — triggers the fallback while the real thread remains stuck
 * </ul>
 *
 * <h2>How bad it is</h2>
 * <p>Severity: <strong>CRITICAL</strong><br>The pool drains thread-by-thread. Once exhausted,
 * every new request is rejected immediately. No recovery without a restart.
 *
 * <h2>Industry references</h2>
 * <p>Netflix/Hystrix issue #1240 — thread leak when Hystrix timeout fires but underlying
 * socket read is still blocked in native code.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos({LibchaosLib.NET})
 * @IncidentChaosFeignHystrixThreadLeak(latencyMs = 10000L)
 * class HystrixThreadLeakTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosFeignHystrixThreadLeak.List.class)
@ChaosL3(composer = "com.macstab.chaos.feign.testpack.l3.composers.FeignHystrixThreadLeakComposer", severity = Severity.CRITICAL)
public @interface IncidentChaosFeignHystrixThreadLeak {

    /** Container filter id; empty string matches all containers. */
    String id() default "";

    /** RECV latency in milliseconds injected on all connections (Hystrix timeout trigger). */
    long latencyMs() default 10000L;

    /** Class name prefix used to match Feign/Hystrix methods for exception injection. */
    String classPattern() default "feign";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {
        IncidentChaosFeignHystrixThreadLeak[] value();
    }
}
