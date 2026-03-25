/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.model.ProcessInfo;
import com.macstab.chaos.core.model.Signal;

/**
 * Process-level chaos injection using Linux signals and cgroups.
 *
 * <p>Simulates process crashes, freezes, and fork bombs by sending signals (SIGKILL, SIGSTOP) and
 * limiting process count via cgroups.
 *
 * <h2>How It Works: Linux Signals</h2>
 *
 * <p>ProcessChaos uses {@code kill(pid, signal)} to control processes:
 *
 * <pre>
 * Application running
 *         ↓
 * Signal sent (SIGTERM/SIGKILL/SIGSTOP)
 *         ↓
 * Kernel delivers signal
 *         ↓
 * Process terminates/pauses
 * </pre>
 *
 * <p><strong>Signal types:</strong>
 *
 * <ul>
 *   <li><strong>SIGTERM (15):</strong> Graceful shutdown (process can cleanup)
 *   <li><strong>SIGKILL (9):</strong> Immediate termination (cannot be caught)
 *   <li><strong>SIGSTOP (19):</strong> Pause (freeze process, resume with SIGCONT)
 * </ul>
 *
 * <h2>Complete Example: Database Crash Resilience</h2>
 *
 * <pre>{@code
 * @Testcontainers
 * class DatabaseCrashTest {
 *
 *   @Container
 *   GenericContainer<?> postgres = new GenericContainer<>("postgres:16")
 *       .withExposedPorts(5432)
 *       .withEnv("POSTGRES_PASSWORD", "test");
 *
 *   ProcessChaos chaos = new ProcessChaosProvider();
 *
 *   @Test
 *   @DisplayName("Application should reconnect after database crash")
 *   void shouldReconnectAfterDatabaseCrash() {
 *     // Setup
 *     DataSource ds = createDataSource(postgres);
 *
 *     // Baseline: Verify connection works
 *     assertThat(ds.getConnection()).isNotNull();
 *
 *     // Inject chaos: Kill postgres process (simulates crash)
 *     chaos.kill(postgres, "postgres", Signal.SIGKILL);
 *
 *     // Wait for restart (postgres auto-restarts in container)
 *     await().atMost(Duration.ofSeconds(10))
 *         .until(() -> {
 *           try {
 *             ds.getConnection();
 *             return true;
 *           } catch (SQLException e) {
 *             return false;
 *           }
 *         });
 *
 *     // Verify: Connection restored
 *     assertThat(ds.getConnection()).isNotNull();
 *   }
 *
 *   @Test
 *   @DisplayName("Application should queue requests during database freeze")
 *   void shouldQueueRequestsDuringFreeze() {
 *     // Pause postgres for 5 seconds (freeze, not crash)
 *     chaos.pause(postgres, "postgres", Duration.ofSeconds(5));
 *
 *     // Application should queue writes, not fail
 *     CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
 *       repository.save(entity);  // Will block during pause
 *     });
 *
 *     // Should complete after pause ends
 *     await().atMost(Duration.ofSeconds(7))
 *         .until(() -> write.isDone());
 *
 *     assertThat(write).isCompletedWithin(Duration.ofSeconds(7));
 *   }
 *
 *   @Test
 *   @DisplayName("Application should handle fork bomb (process limit)")
 *   void shouldHandleForkBomb() {
 *     // Limit to 50 processes (prevent fork bomb)
 *     chaos.limitProcesses(postgres, 50);
 *
 *     // Attempt to create 100 processes (simulate fork bomb)
 *     assertThatThrownBy(() -> {
 *       for (int i = 0; i < 100; i++) {
 *         postgres.execInContainer("sh", "-c", "sleep 10 &");
 *       }
 *     }).hasMessageContaining("Resource limit exceeded");
 *   }
 * }
 * }</pre>
 *
 * <h2>Chaos Types</h2>
 *
 * <table border="1">
 *   <caption>Process Chaos Operations</caption>
 *   <tr>
 *     <th>Method</th>
 *     <th>Signal/Mechanism</th>
 *     <th>Real-World Scenario</th>
 *   </tr>
 *   <tr>
 *     <td>{@link #kill(GenericContainer, String, Signal)}</td>
 *     <td>SIGTERM/SIGKILL</td>
 *     <td>Process crashes, OOM kills</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #pause}</td>
 *     <td>SIGSTOP + SIGCONT</td>
 *     <td>CPU starvation, unresponsive process</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #limitProcesses}</td>
 *     <td>cgroups pids.max</td>
 *     <td>Fork bombs, runaway thread creation</td>
 *   </tr>
 * </table>
 *
 * <h2>Testing Patterns</h2>
 *
 * <h3>Pattern 1: Crash + Auto-Recovery</h3>
 *
 * <pre>{@code
 * @Test
 * void testAutoRecovery() {
 *   // Baseline
 *   assertThat(service.isHealthy()).isTrue();
 *
 *   // Crash
 *   chaos.kill(container, "myapp", Signal.SIGKILL);
 *
 *   // Wait for restart (systemd/supervisor auto-restart)
 *   await().atMost(Duration.ofSeconds(10))
 *       .until(() -> service.isHealthy());
 *
 *   // Verify recovery
 *   assertThat(service.isHealthy()).isTrue();
 * }
 * }</pre>
 *
 * <h3>Pattern 2: Graceful vs Forced Shutdown</h3>
 *
 * <pre>{@code
 * @Test
 * void testGracefulShutdown() {
 *   // SIGTERM (graceful)
 *   chaos.kill(container, "myapp", Signal.SIGTERM);
 *
 *   // Verify: Cleanup completed (flush logs, close connections)
 *   assertThat(logFile).contains("Shutdown complete");
 *
 *   // SIGKILL (forced, no cleanup)
 *   chaos.kill(container, "myapp", Signal.SIGKILL);
 *
 *   // Verify: No cleanup (immediate termination)
 *   assertThat(logFile).doesNotContain("Shutdown complete");
 * }
 * }</pre>
 *
 * <h3>Pattern 3: Process Freeze (Pause/Resume)</h3>
 *
 * <pre>{@code
 * @Test
 * void testFreezeDetection() {
 *   long start = System.currentTimeMillis();
 *
 *   // Freeze for 5 seconds
 *   chaos.pause(container, "redis-server", Duration.ofSeconds(5));
 *
 *   // Client should detect freeze (timeout)
 *   assertThatThrownBy(() -> client.get("key"))
 *       .isInstanceOf(TimeoutException.class);
 *
 *   long duration = System.currentTimeMillis() - start;
 *   assertThat(duration).isGreaterThan(5000);  // Blocked during pause
 * }
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 *
 * <table border="1">
 *   <caption>Process Scenarios</caption>
 *   <tr>
 *     <th>Scenario</th>
 *     <th>Configuration</th>
 *   </tr>
 *   <tr>
 *     <td>Database crash</td>
 *     <td>kill("postgres", SIGKILL)</td>
 *   </tr>
 *   <tr>
 *     <td>Graceful shutdown</td>
 *     <td>kill("myapp", SIGTERM)</td>
 *   </tr>
 *   <tr>
 *     <td>Process freeze (CPU starvation)</td>
 *     <td>pause("myapp", Duration.ofSeconds(10))</td>
 *   </tr>
 *   <tr>
 *     <td>Fork bomb prevention</td>
 *     <td>limitProcesses(container, 100)</td>
 *   </tr>
 *   <tr>
 *     <td>OOM kill simulation</td>
 *     <td>kill(container, "java", SIGKILL)</td>
 *   </tr>
 * </table>
 *
 * <h2>Common Pitfalls</h2>
 *
 * <ul>
 *   <li>❌ Killing process without waiting for restart (test fails immediately)
 *   <li>❌ Using SIGKILL instead of SIGTERM (no graceful shutdown testing)
 *   <li>❌ Not testing auto-recovery (kill without verifying restart)
 *   <li>❌ Pausing for too long (test timeouts, not freeze detection)
 *   <li>❌ Process limit too low (normal operations fail)
 * </ul>
 *
 * <h2>Troubleshooting</h2>
 *
 * <p><strong>Problem:</strong> Process doesn't die after SIGKILL
 *
 * <p><strong>Solution:</strong> Check process name matches exactly
 *
 * <pre>{@code
 * // Wrong: Partial name
 * chaos.kill(container, "postgres", SIGKILL);  // May not match "postgres: writer"
 *
 * // Correct: List processes first
 * List<ProcessInfo> processes = chaos.listProcesses(container);
 * processes.forEach(p -> System.out.println(p.getName()));
 *
 * // Use exact name
 * chaos.kill(container, "postgres: checkpointer", SIGKILL);
 * }</pre>
 *
 * <p><strong>Problem:</strong> Container stops after killing main process
 *
 * <p><strong>Solution:</strong> Use PID 1 check or supervisor/systemd
 *
 * <pre>{@code
 * // Docker containers exit when PID 1 dies
 * // Use --init or supervisor to auto-restart
 * GenericContainer<?> container = new GenericContainer<>("myapp:latest")
 *     .withCommand("sh", "-c", "while true; do myapp; done");  // Auto-restart loop
 * }</pre>
 *
 * <p><strong>Problem:</strong> Pause doesn't affect application
 *
 * <p><strong>Solution:</strong> Verify process name and increase pause duration
 *
 * <pre>{@code
 * // Too short (may not be noticeable)
 * chaos.pause(container, "redis-server", Duration.ofMillis(100));
 *
 * // Long enough to detect
 * chaos.pause(container, "redis-server", Duration.ofSeconds(5));
 * }</pre>
 *
 * <h2>Signal Reference</h2>
 *
 * <table border="1">
 *   <caption>Linux Signals</caption>
 *   <tr>
 *     <th>Signal</th>
 *     <th>Number</th>
 *     <th>Effect</th>
 *     <th>Can Be Caught?</th>
 *   </tr>
 *   <tr>
 *     <td>SIGTERM</td>
 *     <td>15</td>
 *     <td>Graceful shutdown</td>
 *     <td>Yes (cleanup possible)</td>
 *   </tr>
 *   <tr>
 *     <td>SIGKILL</td>
 *     <td>9</td>
 *     <td>Immediate termination</td>
 *     <td>No (kernel enforced)</td>
 *   </tr>
 *   <tr>
 *     <td>SIGSTOP</td>
 *     <td>19</td>
 *     <td>Pause (freeze)</td>
 *     <td>No (kernel enforced)</td>
 *   </tr>
 *   <tr>
 *     <td>SIGCONT</td>
 *     <td>18</td>
 *     <td>Resume</td>
 *     <td>N/A (resume from SIGSTOP)</td>
 *   </tr>
 * </table>
 *
 * <h2>Thread Safety</h2>
 *
 * <p><strong>ProcessChaos implementations are thread-safe.</strong> Multiple threads can call
 * methods concurrently on the same instance.
 *
 * <p><strong>Container isolation:</strong> Each container has independent process namespace. Chaos
 * on container A does not affect container B.
 *
 * <h2>Performance</h2>
 *
 * <ul>
 *   <li><strong>Overhead:</strong> ~1ms to send signal
 *   <li><strong>Process termination:</strong> Immediate (SIGKILL) or <1s (SIGTERM cleanup)
 *   <li><strong>Pause/resume:</strong> Immediate (kernel-level operation)
 * </ul>
 *
 * <h2>Platform Requirements</h2>
 *
 * <ul>
 *   <li>Linux kernel with signal support (all distributions)
 *   <li>cgroups v1 or v2 (for limitProcesses)
 *   <li>CAP_KILL capability (default in Testcontainers)
 * </ul>
 *
 * <h2>Implementation</h2>
 *
 * <p>Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-process:1.0.0")
 * }</pre>
 *
 * <p>Instantiate:
 *
 * <pre>{@code
 * ProcessChaos chaos = new ProcessChaosProvider();
 * }</pre>
 *
 * @see Signal
 * @see ProcessInfo
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ProcessChaos extends ChaosProvider {

  /**
   * Kill process by sending Linux signal.
   *
   * <p>Uses {@code kill -<signal> <pid>} or {@code killall -<signal> <name>}.
   *
   * <h3>Example: Test Database Crash Recovery</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldRecoverAfterDatabaseCrash() {
   *   // Kill postgres (simulate crash)
   *   chaos.kill(postgres, "postgres", Signal.SIGKILL);
   *
   *   // Application should reconnect automatically
   *   await().atMost(Duration.ofSeconds(10))
   *       .until(() -> dataSource.getConnection() != null);
   *
   *   assertThat(dataSource.getConnection()).isNotNull();
   * }
   * }</pre>
   *
   * <h3>Signal Types</h3>
   *
   * <ul>
   *   <li><strong>SIGTERM:</strong> Graceful shutdown (process can cleanup, flush logs)
   *   <li><strong>SIGKILL:</strong> Immediate termination (cannot be caught, no cleanup)
   *   <li><strong>SIGSTOP:</strong> Pause (see {@link #pause} for auto-resume)
   * </ul>
   *
   * <h3>Process Name Matching</h3>
   *
   * <p>Name can be exact ("redis-server") or pattern ("postgres*"). Use {@link #listProcesses} to
   * find exact names.
   *
   * @param container target container
   * @param processName process name or pattern (e.g., "redis-server", "postgres*")
   * @param signal signal to send (SIGTERM for graceful, SIGKILL for immediate)
   * @throws IllegalArgumentException if processName is empty
   * @throws com.macstab.chaos.core.exception.ChaosOperationFailedException if process not found
   */
  void kill(GenericContainer<?> container, String processName, Signal signal);

  /**
   * Pause process (freeze execution) for a duration.
   *
   * <p>Sends SIGSTOP to freeze process, waits for duration, then sends SIGCONT to resume.
   *
   * <p>Simulates CPU starvation or unresponsive processes.
   *
   * <h3>Example: Test Timeout Detection</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldDetectFrozenService() {
   *   // Freeze service for 10 seconds
   *   chaos.pause(service, "myapp", Duration.ofSeconds(10));
   *
   *   // Client should timeout (not hang forever)
   *   assertThatThrownBy(() -> client.makeRequest())
   *       .isInstanceOf(TimeoutException.class);
   * }
   * }</pre>
   *
   * <h3>Use Cases</h3>
   *
   * <ul>
   *   <li>Test timeout handling (client detects frozen service)
   *   <li>Test request queuing (verify requests complete after resume)
   *   <li>Simulate CPU starvation (process gets no CPU time)
   * </ul>
   *
   * @param container target container
   * @param processName process name
   * @param duration how long to pause (e.g., Duration.ofSeconds(5))
   * @throws IllegalArgumentException if duration is negative
   */
  void pause(GenericContainer<?> container, String processName, Duration duration);

  /**
   * Limit maximum processes in container using cgroups.
   *
   * <p>Sets {@code pids.max} in cgroups to prevent fork bombs and runaway thread creation.
   *
   * <h3>Example: Test Fork Bomb Protection</h3>
   *
   * <pre>{@code
   * @Test
   * void shouldPreventForkBomb() {
   *   // Limit to 50 processes
   *   chaos.limitProcesses(container, 50);
   *
   *   // Attempt to create 100 processes (fork bomb)
   *   assertThatThrownBy(() -> {
   *     for (int i = 0; i < 100; i++) {
   *       container.execInContainer("sh", "-c", "sleep 60 &");
   *     }
   *   }).hasMessageContaining("Resource limit exceeded");
   * }
   * }</pre>
   *
   * <h3>Recommended Limits</h3>
   *
   * <ul>
   *   <li><strong>100-200:</strong> Normal applications (web server, database)
   *   <li><strong>50-100:</strong> Constrained environments (embedded, IoT)
   *   <li><strong>10-50:</strong> Testing fork bomb handling
   * </ul>
   *
   * @param container target container
   * @param maxProcesses maximum processes allowed (must be &gt; 0)
   * @throws IllegalArgumentException if maxProcesses &lt;= 0
   */
  void limitProcesses(GenericContainer<?> container, int maxProcesses);

  /**
   * List all running processes in container.
   *
   * <p>Uses {@code ps aux} to get process list.
   *
   * <h3>Example: Find Process Name for Killing</h3>
   *
   * <pre>{@code
   * @Test
   * void killSpecificProcess() {
   *   // List all processes
   *   List<ProcessInfo> processes = chaos.listProcesses(postgres);
   *
   *   // Find checkpointer process
   *   ProcessInfo checkpointer = processes.stream()
   *       .filter(p -> p.getName().contains("checkpointer"))
   *       .findFirst()
   *       .orElseThrow();
   *
   *   // Kill exact process
   *   chaos.kill(postgres, checkpointer.getName(), Signal.SIGKILL);
   * }
   * }</pre>
   *
   * @param container target container
   * @return list of running processes (name + PID)
   */
  List<ProcessInfo> listProcesses(GenericContainer<?> container);
}
