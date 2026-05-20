/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for the JVM agent. Annotations are split into per-selector sub-packages
 * (jdbc, http_client, network, nio, thread, executor, queue, async, ...) for IDE autocomplete
 * sanity, plus a dedicated stressors package for the 15 self-driving stressor effects.
 *
 * <p>Each annotation carries:
 *
 * <ul>
 *   <li>{@code @ChaosL1(translator = "...")} — names the per-effect translator (DelayTranslator,
 *       RejectTranslator, ExceptionInjectionTranslator, ClockSkewTranslator, ...).
 *   <li>{@code @JvmInterceptorBinding(selectorKind, operationType)} — names the (selector family,
 *       OperationType) tuple the L1 targets (interceptor annotations only; stressors don't carry
 *       a binding because the StressSelector is keyed by StressTarget).
 *   <li>Effect-specific attributes — {@code delayMs} / {@code maxDelayMs} for Delay,
 *       {@code message} for Reject, {@code exceptionClassName} + {@code message} for
 *       InjectException, {@code skewMs} + {@code mode} for ClockSkew, etc.
 * </ul>
 *
 * <p><strong>Lifecycle.</strong> Multiple JVM L1s on a test class accumulate into a single
 * {@code ChaosPlan} per container via {@link com.macstab.chaos.jvm.annotation.l1.JvmPlanAccumulator},
 * which re-serialises and pushes the merged plan after every change. Cleanup
 * ({@code afterEach}/{@code afterAll}) filters the scenario out and re-pushes (or calls
 * {@code clearPlan} when the active set goes empty).
 *
 * <p>The legacy {@link com.macstab.chaos.jvm.annotation.l1.ChaosJvmPlan} escape-hatch annotation
 * remains for hand-written plan JSON not yet covered by a typed L1.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.jvm.annotation.l1;
