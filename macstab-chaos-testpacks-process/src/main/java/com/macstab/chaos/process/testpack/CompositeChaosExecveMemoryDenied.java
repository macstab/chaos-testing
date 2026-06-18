/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.testpack;

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
 * <p>Every {@code execve()} call fails with {@code ENOMEM}, simulating the kernel failing to
 * allocate the memory required to load the new binary — page tables, bss sections, and the initial
 * stack. Unlike {@code EACCES} (permission problem) or {@code ENOENT} (missing binary), {@code
 * ENOMEM} from {@code execve} indicates an OOM condition at exec time: the node has insufficient
 * free memory to map the process image. Applications that launch helpers or external utilities will
 * fail to exec them even though the binaries exist and are executable.
 *
 * <h2>How it's created</h2>
 *
 * <p>Applies {@code ProcessRule.errno(ProcessSelector.EXECVE, ProcessErrno.ENOMEM, toxicity)} via
 * libchaos-process. In production this happens when a large application binary (JVM, Electron, LLVM
 * toolchain) is exec'd on a node under severe memory pressure, when huge pages cannot be allocated
 * for a binary that requires them, or when the OOM killer is already active and kernel allocation
 * for a new address space fails.
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * At {@code toxicity = 0.5} half of exec operations fail with OOM. Any feature relying on
 * exec-based subprocess execution — log archiving, certificate renewal, schema migration scripts —
 * will fail for roughly half of invocations. Unlike permission errors, exec-ENOMEM is transient:
 * once memory pressure subsides the same exec succeeds. Applications that do not distinguish
 * exec-ENOMEM from exec-EACCES may permanently disable the affected feature.
 *
 * <h2>Industry references</h2>
 *
 * <p>{@code ENOMEM} from {@code execve} is documented in the Linux {@code execve(2)} man-page:
 * "Insufficient kernel memory was available." The pattern of large binary exec failure under memory
 * pressure appears in production post-mortems from Java and .NET deployments where JVM re-exec
 * (HotSpot fork+exec for JVM args application) fails under memory pressure.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @SyscallLevelChaos(LibchaosLib.PROCESS)
 * @CompositeChaosExecveMemoryDenied(toxicity = 0.5)
 * class ExecveMemoryDeniedTest {
 *   @Test
 *   void execEnomemIsDistinguishedFromPermErrorAndRetried() {
 *     // assert: ENOMEM logged separately from EACCES; retry attempted; metric emitted
 *   }
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(CompositeChaosExecveMemoryDenied.List.class)
@ChaosL2(
    composer = "com.macstab.chaos.process.testpack.composers.ExecveMemoryDeniedComposer",
    severity = Severity.SEVERE)
public @interface CompositeChaosExecveMemoryDenied {

  /**
   * Probability in {@code (0.0, 1.0]} that {@code ENOMEM} fires on each {@code execve()} call.
   * Defaults to {@code 0.5} (half of exec attempts fail with OOM).
   */
  double toxicity() default 0.5;

  /**
   * Container id to target. Empty string (the default) applies the scenario to all containers
   * prepared with libchaos-process.
   */
  String id() default "";

  /** Repeatable container. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    CompositeChaosExecveMemoryDenied[] value();
  }
}
