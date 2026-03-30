/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Extracts structured {@link CommandWithArgs} objects from captured MONITOR lines.
 *
 * <p><strong>Purpose:</strong> Provides typed access to command arguments for debugging and
 * analysis. Filters by command name and parses each matching line.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * CommandParser parser = new CommandParser(capturedCommands);
 * List<CommandWithArgs> sets = parser.getCommandsWithArguments("SET");
 * sets.forEach(cmd -> System.out.println("Key: " + cmd.getKey() + ", Value: " + cmd.getValue()));
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class CommandParser {

  private final List<String> capturedCommands;

  /**
   * Creates a command parser over the given captured MONITOR lines.
   *
   * @param capturedCommands captured MONITOR output lines — must not be null
   */
  public CommandParser(final List<String> capturedCommands) {
    this.capturedCommands = Objects.requireNonNull(capturedCommands, "capturedCommands");
  }

  /**
   * Returns all occurrences of a command with their parsed arguments.
   *
   * @param command Redis command name (e.g., "SET") — must not be null
   * @return list of parsed commands with arguments (never null, may be empty)
   */
  public List<CommandWithArgs> getCommandsWithArguments(final String command) {
    Objects.requireNonNull(command, "command");
    final String quotedCommand = "\"" + command.toUpperCase() + "\"";
    return capturedCommands.stream()
        .filter(line -> line.contains(quotedCommand))
        .map(CommandWithArgs::parse)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  /**
   * Extracts the command name (first quoted token) from a MONITOR output line.
   *
   * <p>MONITOR format: {@code 1234.567 [0 127.0.0.1:12345] "COMMAND" "arg1" "arg2"}
   *
   * @param line MONITOR output line
   * @return command name in uppercase, or {@code null} if line is malformed
   */
  public static String extractCommandName(final String line) {
    final int firstQuote = line.indexOf('"');
    if (firstQuote == -1) {
      return null;
    }
    final int secondQuote = line.indexOf('"', firstQuote + 1);
    if (secondQuote == -1) {
      return null;
    }
    return line.substring(firstQuote + 1, secondQuote).toUpperCase();
  }
}
