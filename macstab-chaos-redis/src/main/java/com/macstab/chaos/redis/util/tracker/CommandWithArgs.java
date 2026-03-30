/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed Redis command with its arguments extracted from a MONITOR output line.
 *
 * <p><strong>MONITOR line format:</strong>
 *
 * <pre>
 * 1234567890.123456 [0 172.17.0.1:54321] "SET" "user:123" "john" "EX" "3600"
 * </pre>
 *
 * <p>This class parses the quoted tokens from a MONITOR line into a structured object.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * CommandWithArgs cmd = CommandWithArgs.parse(line);
 * if (cmd != null) {
 *   System.out.println(cmd.getCommand()); // "SET"
 *   System.out.println(cmd.getKey());     // "user:123"
 *   System.out.println(cmd.getValue());   // "john"
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class CommandWithArgs {

  private final String command;
  private final List<String> args;

  private CommandWithArgs(final String command, final List<String> args) {
    this.command = command;
    this.args = List.copyOf(args);
  }

  /**
   * Parses a MONITOR output line into a {@link CommandWithArgs}.
   *
   * <p>Extracts all quoted tokens from the line. The first token is the command name; subsequent
   * tokens are arguments.
   *
   * @param line MONITOR output line
   * @return parsed command, or {@code null} if the line is malformed or contains no quoted tokens
   */
  public static CommandWithArgs parse(final String line) {
    if (line == null) {
      return null;
    }
    final List<String> tokens = new ArrayList<>();
    int pos = line.indexOf('"');
    while (pos != -1) {
      final int endPos = line.indexOf('"', pos + 1);
      if (endPos == -1) {
        break;
      }
      tokens.add(line.substring(pos + 1, endPos));
      pos = line.indexOf('"', endPos + 1);
    }
    if (tokens.isEmpty()) {
      return null;
    }
    final String command = tokens.get(0);
    final List<String> args = tokens.subList(1, tokens.size());
    return new CommandWithArgs(command, args);
  }

  /**
   * Returns the Redis command name.
   *
   * @return command name (e.g., "GET", "SET") — never null
   */
  public String getCommand() {
    return command;
  }

  /**
   * Returns all command arguments.
   *
   * @return immutable list of arguments (empty if none)
   */
  public List<String> getArgs() {
    return args;
  }

  /**
   * Returns the key (first argument).
   *
   * @return key or {@code null} if no arguments
   */
  public String getKey() {
    return args.isEmpty() ? null : args.get(0);
  }

  /**
   * Returns the value (second argument, for SET/HSET etc.).
   *
   * @return value or {@code null} if fewer than 2 arguments
   */
  public String getValue() {
    return args.size() < 2 ? null : args.get(1);
  }

  @Override
  public String toString() {
    return command + " " + String.join(" ", args);
  }
}
