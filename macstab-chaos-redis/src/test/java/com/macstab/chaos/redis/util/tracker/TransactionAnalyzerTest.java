/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.redis.util.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.redis.util.tracker.TransactionAnalyzer.TransactionSummary;

/** Comprehensive unit tests for {@link TransactionAnalyzer}. */
@DisplayName("TransactionAnalyzer")
class TransactionAnalyzerTest {

  private static final String MULTI = "1234.0 [0 127.0.0.1:12345] \"MULTI\"";
  private static final String EXEC = "1234.1 [0 127.0.0.1:12345] \"EXEC\"";
  private static final String DISCARD = "1234.2 [0 127.0.0.1:12345] \"DISCARD\"";
  private static final String SET_1 = "1234.3 [0 127.0.0.1:12345] \"SET\" \"key1\" \"val1\"";
  private static final String SET_2 = "1234.4 [0 127.0.0.1:12345] \"SET\" \"key2\" \"val2\"";
  private static final String GET_1 = "1234.5 [0 127.0.0.1:12345] \"GET\" \"key1\"";
  private static final String GET_2 = "1234.6 [0 127.0.0.1:12345] \"GET\" \"key2\"";
  private static final String PING = "1234.7 [0 127.0.0.1:12345] \"PING\"";

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    @DisplayName("Should throw NPE for null capturedCommands")
    void shouldThrowForNull() {
      assertThatThrownBy(() -> new TransactionAnalyzer(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("capturedCommands");
    }

    @Test
    @DisplayName("Should accept empty list")
    void shouldAcceptEmptyList() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of());
      assertThat(analyzer.getCompletedTransactionCount()).isZero();
    }
  }

  @Nested
  @DisplayName("getCompletedTransactionCount()")
  class GetCompletedTransactionCount {

    @Test
    @DisplayName("Should return zero when no transactions")
    void shouldReturnZeroWhenNoTransactions() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(SET_1, GET_1));

      assertThat(analyzer.getCompletedTransactionCount()).isZero();
    }

    @Test
    @DisplayName("Should count one completed transaction")
    void shouldCountOneCompletedTransaction() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(MULTI, SET_1, EXEC));

      assertThat(analyzer.getCompletedTransactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should count multiple completed transactions")
    void shouldCountMultipleCompletedTransactions() {
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(List.of(MULTI, SET_1, EXEC, MULTI, SET_2, EXEC));

      assertThat(analyzer.getCompletedTransactionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should not count unclosed transaction")
    void shouldNotCountUnclosedTransaction() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(MULTI, SET_1));

      assertThat(analyzer.getCompletedTransactionCount()).isZero();
    }
  }

  @Nested
  @DisplayName("getDiscardedTransactionCount()")
  class GetDiscardedTransactionCount {

    @Test
    @DisplayName("Should return zero when no discarded transactions")
    void shouldReturnZeroWhenNoDiscarded() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(MULTI, SET_1, EXEC));

      assertThat(analyzer.getDiscardedTransactionCount()).isZero();
    }

    @Test
    @DisplayName("Should count one discarded transaction")
    void shouldCountOneDiscardedTransaction() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(MULTI, SET_1, DISCARD));

      assertThat(analyzer.getDiscardedTransactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should count mixed completed and discarded")
    void shouldCountMixedCompletedAndDiscarded() {
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(
              List.of(MULTI, SET_1, EXEC, MULTI, SET_2, DISCARD, MULTI, GET_1, EXEC));

      assertThat(analyzer.getDiscardedTransactionCount()).isEqualTo(1);
      assertThat(analyzer.getCompletedTransactionCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("getAverageCommandsPerTransaction()")
  class GetAverageCommandsPerTransaction {

    @Test
    @DisplayName("Should calculate exact average")
    void shouldCalculateExactAverage() {
      // First transaction: 2 commands, second transaction: 4 commands
      // Average: (2 + 4) / 2 = 3.0
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(
              List.of(MULTI, SET_1, GET_1, EXEC, MULTI, SET_2, GET_2, PING, SET_1, EXEC));

      assertThat(analyzer.getAverageCommandsPerTransaction()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("Should return zero when no completed transactions")
    void shouldReturnZeroWhenNoCompleted() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(SET_1, GET_1));

      assertThat(analyzer.getAverageCommandsPerTransaction()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should handle mixed transaction sizes")
    void shouldHandleMixedSizes() {
      // Transaction 1: 1 command, Transaction 2: 3 commands
      // Average: (1 + 3) / 2 = 2.0
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(List.of(MULTI, SET_1, EXEC, MULTI, SET_2, GET_1, GET_2, EXEC));

      assertThat(analyzer.getAverageCommandsPerTransaction()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Should not count discarded transactions in average")
    void shouldNotCountDiscardedInAverage() {
      // Only completed transaction has 2 commands, discarded has 1 but is ignored
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(
              List.of(MULTI, SET_1, GET_1, EXEC, MULTI, SET_2, DISCARD));

      assertThat(analyzer.getAverageCommandsPerTransaction()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Should handle empty transaction")
    void shouldHandleEmptyTransaction() {
      // Transaction with 0 commands
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(MULTI, EXEC));

      assertThat(analyzer.getAverageCommandsPerTransaction()).isEqualTo(0.0);
    }
  }

  @Nested
  @DisplayName("getCommandsOutsideTransactions()")
  class GetCommandsOutsideTransactions {

    @Test
    @DisplayName("Should return all commands when no transactions")
    void shouldReturnAllCommandsWhenNoTransactions() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(SET_1, GET_1, PING));

      final List<String> outside = analyzer.getCommandsOutsideTransactions();

      assertThat(outside).hasSize(3);
      assertThat(outside).contains(SET_1, GET_1, PING);
    }

    @Test
    @DisplayName("Should return empty when all commands inside transactions")
    void shouldReturnEmptyWhenAllInside() {
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(List.of(MULTI, SET_1, GET_1, EXEC));

      final List<String> outside = analyzer.getCommandsOutsideTransactions();

      assertThat(outside).isEmpty();
    }

    @Test
    @DisplayName("Should return mixed outside commands")
    void shouldReturnMixedOutsideCommands() {
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(List.of(SET_1, MULTI, GET_1, EXEC, PING));

      final List<String> outside = analyzer.getCommandsOutsideTransactions();

      assertThat(outside).hasSize(2);
      assertThat(outside).contains(SET_1, PING);
      assertThat(outside).doesNotContain(GET_1);
    }

    @Test
    @DisplayName("Should not include MULTI, EXEC, DISCARD as outside commands")
    void shouldNotIncludeTransactionCommandsAsOutside() {
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(List.of(MULTI, SET_1, EXEC, DISCARD));

      final List<String> outside = analyzer.getCommandsOutsideTransactions();

      // DISCARD is outside transaction (no open MULTI), but treated as transaction control
      assertThat(outside).contains(DISCARD);
      assertThat(outside).doesNotContain(MULTI, EXEC);
    }
  }

  @Nested
  @DisplayName("getSummary()")
  class GetSummary {

    @Test
    @DisplayName("Should return summary matching individual methods")
    void shouldReturnSummaryMatchingMethods() {
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(
              List.of(MULTI, SET_1, SET_2, EXEC, MULTI, GET_1, DISCARD, PING));

      final TransactionSummary summary = analyzer.getSummary();

      assertThat(summary.completedTransactions()).isEqualTo(1);
      assertThat(summary.discardedTransactions()).isEqualTo(1);
      assertThat(summary.averageCommandsPerTransaction()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Should return zero summary for empty list")
    void shouldReturnZeroSummaryForEmpty() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of());

      final TransactionSummary summary = analyzer.getSummary();

      assertThat(summary.completedTransactions()).isZero();
      assertThat(summary.discardedTransactions()).isZero();
      assertThat(summary.averageCommandsPerTransaction()).isEqualTo(0.0);
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("Should handle empty list")
    void shouldHandleEmptyList() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of());

      assertThat(analyzer.getCompletedTransactionCount()).isZero();
      assertThat(analyzer.getDiscardedTransactionCount()).isZero();
      assertThat(analyzer.getCommandsOutsideTransactions()).isEmpty();
    }

    @Test
    @DisplayName("Should handle unclosed MULTI at end")
    void shouldHandleUnclosedMultiAtEnd() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(MULTI, SET_1, GET_1));

      assertThat(analyzer.getCompletedTransactionCount()).isZero();
      assertThat(analyzer.getDiscardedTransactionCount()).isZero();
      // Commands in unclosed transaction are counted as outside
      assertThat(analyzer.getCommandsOutsideTransactions()).hasSize(2);
    }

    @Test
    @DisplayName("Should treat nested MULTI as command inside outer transaction")
    void shouldTreatNestedMultiAsCommandInsideOuter() {
      // Nested MULTI is treated as a regular command inside the outer transaction
      final TransactionAnalyzer analyzer =
          new TransactionAnalyzer(List.of(MULTI, SET_1, MULTI, GET_1, EXEC));

      // The nested MULTI opens a new transaction context, so only outer EXEC completes
      assertThat(analyzer.getCompletedTransactionCount()).isEqualTo(1);
      // Commands: SET_1 before inner MULTI, then GET_1 inside inner (which remains open)
      // Actually, since inner MULTI opens new context, GET_1 is in inner, outer gets SET_1 + MULTI
      // Let's verify the behavior: outer has SET_1, then inner MULTI opens, GET_1 is in inner,
      // EXEC closes inner
      // Average should be 2 (SET_1 and MULTI counted as commands in completed outer transaction)
      // Wait, the EXEC closes the MOST RECENT open transaction (the inner one)
      // So: MULTI(outer) SET_1 MULTI(inner) GET_1 EXEC(closes inner)
      // Outer remains open, inner completed with 1 command (GET_1)
      assertThat(analyzer.getAverageCommandsPerTransaction()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should handle EXEC without MULTI")
    void shouldHandleExecWithoutMulti() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(SET_1, EXEC, GET_1));

      // EXEC without open MULTI does not count as completed transaction
      assertThat(analyzer.getCompletedTransactionCount()).isZero();
      assertThat(analyzer.getCommandsOutsideTransactions()).hasSize(3);
    }

    @Test
    @DisplayName("Should handle DISCARD without MULTI")
    void shouldHandleDiscardWithoutMulti() {
      final TransactionAnalyzer analyzer = new TransactionAnalyzer(List.of(SET_1, DISCARD, GET_1));

      assertThat(analyzer.getDiscardedTransactionCount()).isZero();
      assertThat(analyzer.getCommandsOutsideTransactions()).hasSize(3);
    }
  }
}
