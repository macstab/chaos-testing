/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L1 chaos primitives for the {@code method} selector family. Unlike the other selector packages,
 * method annotations require the user to supply {@code classPattern} and/or {@code
 * methodNamePattern} — MethodSelector rejects the all-{@code ANY} combination to prevent accidental
 * JVM-wide instrumentation. Patterns are prefix-matched.
 *
 * <p>Method interception also requires application-side wiring: the agent does not auto-rewrite
 * arbitrary user methods. The selected {@code METHOD_ENTER} / {@code METHOD_EXIT} events must be
 * raised from inside an existing interceptor (Spring AOP / AspectJ / Micronaut / Quarkus / a custom
 * ByteBuddy advice) calling {@code chaosRuntime.beforeMethodEnter} / {@code
 * chaosRuntime.afterMethodExit}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.jvm.annotation.l1.method;
