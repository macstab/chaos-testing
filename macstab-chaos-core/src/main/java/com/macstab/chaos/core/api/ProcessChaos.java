/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.api;

import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.model.ProcessInfo;
import com.macstab.chaos.core.model.Signal;

/**
 * Process chaos injection interface.
 *
 * <p>Kill, pause, or limit processes in containers.
 *
 * <p><strong>Real Implementation:</strong> Add dependency:
 *
 * <pre>{@code
 * testImplementation("com.macstab.chaos:macstab-chaos-process:1.0.0")
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ProcessChaos extends ChaosProvider {

  /**
   * Kill process by name.
   *
   * @param container target container
   * @param processName process name ("redis-server")
   * @param signal kill signal (SIGTERM, SIGKILL, SIGSTOP)
   */
  void kill(GenericContainer<?> container, String processName, Signal signal);

  /**
   * Pause process for duration (SIGSTOP → wait → SIGCONT).
   *
   * @param container target container
   * @param processName process name
   * @param duration pause duration
   */
  void pause(GenericContainer<?> container, String processName, Duration duration);

  /**
   * Limit max processes in container (cgroups pids.max).
   *
   * @param container target container
   * @param maxProcesses maximum processes allowed
   */
  void limitProcesses(GenericContainer<?> container, int maxProcesses);

  /**
   * Get list of running processes.
   *
   * @param container target container
   * @return list of process names + PIDs
   */
  List<ProcessInfo> listProcesses(GenericContainer<?> container);
}
