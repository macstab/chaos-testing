/* (C)2026 Christian Schnapka / Macstab GmbH */
/**
 * L3 incident annotations for JVM-level compound failures.
 *
 * <p>These scenarios compose Java agent stressors with syscall-level domain chaos to reproduce
 * production incidents that are invisible to standard monitoring: JIT code cache exhaustion,
 * virtual thread carrier starvation, safepoint cascades, and off-heap memory leaks.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.chaos.jvm.testpack.l3;
