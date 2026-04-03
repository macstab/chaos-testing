/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.shell;

/**
 * Shell feature capabilities for conditional command generation.
 *
 * <p>Command builders may query {@link Shell#supports(ShellCapability)} to emit
 * shell-compatible commands. For example, a builder targeting bash can use process
 * substitution ({@code <()}), while one targeting ash must use temporary files.
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see Shell#supports(ShellCapability)
 */
public enum ShellCapability {

  /** POSIX {@code $()} command substitution — supported by all POSIX shells. */
  COMMAND_SUBSTITUTION,

  /** Bash {@code /dev/tcp/host/port} pseudo-device for TCP connections. */
  DEV_TCP,

  /** Bash/zsh {@code <()} and {@code >()} process substitution. */
  PROCESS_SUBSTITUTION,

  /** Bash/zsh {@code {1..10}} brace expansion. */
  BRACE_EXPANSION,

  /** Bash/zsh {@code [[ ]]} extended test with pattern matching. */
  EXTENDED_TEST,

  /** Bash/zsh {@code declare -a} indexed arrays. */
  ARRAYS,

  /** Bash/zsh {@code declare -A} associative arrays. */
  ASSOCIATIVE_ARRAYS
}
