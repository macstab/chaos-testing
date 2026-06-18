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
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates thread-stack allocation failure by injecting {@code ENOMEM} on anonymous {@code
 * mmap} calls at 50% probability. Since {@code pthread_create()} allocates each new thread's stack
 * via {@code mmap(MAP_ANONYMOUS)}, this makes roughly one in two thread-creation attempts fail with
 * {@code ENOMEM} — the same outcome a process hits when it reaches the system's per-process thread
 * limit or when the address space is too fragmented to accommodate a new stack.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code AdvancedMemoryChaos#failThreadCreation(container, 0.5)} via libchaos-memory,
 * which installs a {@code mmap/anon:ERRNO:ENOMEM@0.5} rule. In production thread-stack exhaustion
 * arises when a service creates threads without bounds, when the system's {@code
 * /proc/sys/kernel/threads-max} is reached, or when virtual address space fragmentation prevents
 * allocating a contiguous stack region (default 8 MiB on Linux).
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * At 50% toxicity approximately half of all thread-creation attempts fail. Thread pools that do not
 * handle {@code pthread_create()} failure degrade silently — request queues fill up, worker threads
 * are never replaced after crash, and the service eventually stops accepting new work. Managed
 * runtimes (JVM, .NET CLR) surface this as {@code OutOfMemoryError: unable to create native
 * thread}. Recovery typically requires a restart.
 *
 * <h2>Industry references</h2>
 *
 * <p>Thread-creation failure as a production incident class is documented in the JVM specification
 * ({@code java.lang.OutOfMemoryError}) and in glibc {@code pthread_create(3)}: the call returns
 * {@code EAGAIN} (thread limit) or {@code ENOMEM} (address space / stack allocation). The scenario
 * is a specific sub-case of the "thread pool exhaustion" incident class described in the Google SRE
 * book, chapter 19. Linux stack allocation behaviour is in {@code mmap(2)} and {@code
 * pthread_create(3)}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @SyscallLevelChaos(LibchaosLib.MEMORY)
 * @CompositeChaosThreadStackExhaustion
 * class ThreadPoolResilienceTest {
 *
 *   @Test
 *   void threadPoolDegradesgracefullyOnCreationFailure(GenericContainer<?> app) {
 *     // Service must log the failure and continue with existing workers — not crash
 *     assertThat(app.getLogs()).contains("thread creation failed").doesNotContain("SIGSEGV");
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosThreadStackExhaustion.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.memory.testpack.composers.ThreadStackExhaustionComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosThreadStackExhaustion {

  /**
   * Probability that any intercepted anonymous {@code mmap} call (i.e. thread-stack allocation)
   * returns {@code ENOMEM}. Must be in {@code (0.0, 1.0]}. Default {@code 0.5} (50%).
   */
  double toxicity() default 0.5;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-memory.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosThreadStackExhaustion[] value();
  }
}
