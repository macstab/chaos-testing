/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.command.process;

/**
 * Platform-specific process management command builder.
 *
 * <p>Builds commands for finding, killing, and checking processes.
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@code ProcFsCommandBuilder} - Linux /proc filesystem
 *   <li>{@code PsCommandBuilder} - Portable ps command
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public interface ProcessCommandBuilder {

  /**
   * Build command to find process PIDs by name.
   *
   * <p>Returns command that outputs one PID per line.
   *
   * <p><strong>Linux /proc example:</strong>
   *
   * <pre>
   * for pid in $(grep -l 'toxiproxy' /proc/* /cmdline 2>/dev/null | cut -d/ -f3); do
   *   echo $pid
   * done
   * </pre>
   *
   * @param processName process name to search for
   * @return command string
   */
  String buildFindProcessCommand(String processName);

  /**
   * Build command to kill process by PID.
   *
   * @param pid process ID
   * @return command string
   */
  String buildKillProcessCommand(int pid);

  /**
   * Build command to check if process is running.
   *
   * <p>Returns command that exits 0 if running, non-zero if not.
   *
   * @param pid process ID
   * @return command string
   */
  String buildCheckProcessCommand(int pid);

  /**
   * Build command to kill all processes matching name.
   *
   * <p>Returns command that kills all matching processes.
   *
   * @param processName process name
   * @return command string
   */
  String buildKillAllProcessesCommand(String processName);
}
