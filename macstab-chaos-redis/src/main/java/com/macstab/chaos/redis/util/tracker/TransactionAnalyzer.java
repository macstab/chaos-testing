/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Analyzes Redis transactions (MULTI/EXEC/DISCARD) from captured MONITOR output.
 *
 * <p>Tracks transaction completion rates, discard rates, and identifies commands outside
 * transactions. Useful for verifying transactional behavior in test scenarios.
 *
 * <p><strong>Transaction States:</strong>
 *
 * <ul>
 *   <li>MULTI — opens a transaction context
 *   <li>EXEC — closes and commits the transaction (counted as "completed")
 *   <li>DISCARD — closes and aborts the transaction (counted as "discarded")
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * TransactionAnalyzer analyzer = new TransactionAnalyzer(capturedCommands);
 * assertThat(analyzer.getCompletedTransactionCount()).isEqualTo(5);
 * assertThat(analyzer.getDiscardedTransactionCount()).isEqualTo(0);
 * assertThat(analyzer.getAverageCommandsPerTransaction()).isGreaterThan(2.0);
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @since 1.0
 */
public final class TransactionAnalyzer {

  /**
   * Summary of transaction statistics.
   *
   * @param completedTransactions number of transactions closed with EXEC
   * @param discardedTransactions number of transactions closed with DISCARD
   * @param averageCommandsPerTransaction average commands per completed transaction
   */
  public record TransactionSummary(
      int completedTransactions, int discardedTransactions, double averageCommandsPerTransaction) {}

  private final List<String> capturedCommands;
  private TransactionAnalysisResult cachedResult = null;

  /**
   * Creates a transaction analyzer over captured MONITOR output.
   *
   * @param capturedCommands captured MONITOR lines — must not be null
   * @throws NullPointerException if capturedCommands is null
   */
  public TransactionAnalyzer(final List<String> capturedCommands) {
    this.capturedCommands = Objects.requireNonNull(capturedCommands, "capturedCommands");
  }

  /**
   * Returns count of completed transactions (MULTI...EXEC).
   *
   * @return number of completed transactions
   */
  public int getCompletedTransactionCount() {
    return getAnalysis().completedCount;
  }

  /**
   * Returns count of discarded transactions (MULTI...DISCARD).
   *
   * @return number of discarded transactions
   */
  public int getDiscardedTransactionCount() {
    return getAnalysis().discardedCount;
  }

  /**
   * Returns average number of commands per completed transaction.
   *
   * <p>Only counts commands inside completed transactions. Returns 0.0 if no completed
   * transactions.
   *
   * @return average commands per transaction, or 0.0 if no completed transactions
   */
  public double getAverageCommandsPerTransaction() {
    return getAnalysis().averageCommandsPerTransaction;
  }

  /**
   * Returns commands that were executed outside of any transaction context.
   *
   * <p>A command is "outside" if it appears when no MULTI is open, or if a MULTI was opened
   * but never closed with EXEC or DISCARD (open transaction at end of capture).
   *
   * @return list of command lines outside transactions (never null)
   */
  public List<String> getCommandsOutsideTransactions() {
    return Collections.unmodifiableList(getAnalysis().outsideCommands);
  }

  /**
   * Returns a summary of all transaction statistics.
   *
   * @return transaction summary record
   */
  public TransactionSummary getSummary() {
    final TransactionAnalysisResult result = getAnalysis();
    return new TransactionSummary(
        result.completedCount, result.discardedCount, result.averageCommandsPerTransaction);
  }

  /**
   * Returns cached analysis, computing it on first call (lazy initialization).
   *
   * @return cached analysis result
   */
  private TransactionAnalysisResult getAnalysis() {
    if (cachedResult == null) {
      cachedResult = analyzeTransactions();
    }
    return cachedResult;
  }

  /**
   * Internal result holder for transaction analysis.
   */
  private static final class TransactionAnalysisResult {
    int completedCount = 0;
    int discardedCount = 0;
    double averageCommandsPerTransaction = 0.0;
    List<String> outsideCommands = new ArrayList<>();
  }

  /**
   * Analyzes all captured commands for transaction patterns.
   *
   * <p>Tracks MULTI/EXEC/DISCARD boundaries. Commands inside an unclosed transaction (MULTI with
   * no subsequent EXEC or DISCARD) are treated as outside any transaction context.
   *
   * @return analysis result with counts and averages
   */
  private TransactionAnalysisResult analyzeTransactions() {
    final TransactionAnalysisResult result = new TransactionAnalysisResult();

    boolean inTransaction = false;
    int currentTransactionCommandCount = 0;
    int totalCommandsInCompletedTransactions = 0;
    final List<String> pendingTransactionCommands = new ArrayList<>();

    for (final String line : capturedCommands) {
      if (isMultiCommand(line)) {
        // Flush any previously open unclosed transaction as outside
        result.outsideCommands.addAll(pendingTransactionCommands);
        pendingTransactionCommands.clear();
        inTransaction = true;
        currentTransactionCommandCount = 0;
      } else if (isExecCommand(line)) {
        if (inTransaction) {
          result.completedCount++;
          totalCommandsInCompletedTransactions += currentTransactionCommandCount;
          pendingTransactionCommands.clear();
          inTransaction = false;
          currentTransactionCommandCount = 0;
        } else {
          result.outsideCommands.add(line);
        }
      } else if (isDiscardCommand(line)) {
        if (inTransaction) {
          result.discardedCount++;
          pendingTransactionCommands.clear();
          inTransaction = false;
          currentTransactionCommandCount = 0;
        } else {
          result.outsideCommands.add(line);
        }
      } else {
        if (inTransaction) {
          // Buffer — only promoted to "inside" if EXEC follows
          pendingTransactionCommands.add(line);
          currentTransactionCommandCount++;
        } else {
          result.outsideCommands.add(line);
        }
      }
    }

    // Unclosed transaction at end of capture — treat buffered commands as outside
    if (inTransaction) {
      result.outsideCommands.addAll(pendingTransactionCommands);
    }

    if (result.completedCount > 0) {
      result.averageCommandsPerTransaction =
          (double) totalCommandsInCompletedTransactions / result.completedCount;
    }

    return result;
  }

  /**
   * Checks if a line contains a MULTI command.
   *
   * @param line MONITOR output line
   * @return true if line contains MULTI
   */
  private static boolean isMultiCommand(final String line) {
    return line.contains("\"MULTI\"");
  }

  /**
   * Checks if a line contains an EXEC command.
   *
   * @param line MONITOR output line
   * @return true if line contains EXEC
   */
  private static boolean isExecCommand(final String line) {
    return line.contains("\"EXEC\"");
  }

  /**
   * Checks if a line contains a DISCARD command.
   *
   * @param line MONITOR output line
   * @return true if line contains DISCARD
   */
  private static boolean isDiscardCommand(final String line) {
    return line.contains("\"DISCARD\"");
  }
}
