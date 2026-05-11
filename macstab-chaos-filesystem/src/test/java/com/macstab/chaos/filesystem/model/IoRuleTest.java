/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("IoRule (unit)")
class IoRuleTest {

  private static final PathPrefix P = PathPrefix.path("/data");

  @Nested
  @DisplayName("canonical constructor — null checks")
  class NullChecks {

    @Test
    @DisplayName("rejects null path")
    void nullPath() {
      assertThatThrownBy(() -> new IoRule(null, IoOperation.OPEN, Effect.errno(Errno.EIO, 1.0)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects null operation")
    void nullOperation() {
      assertThatThrownBy(() -> new IoRule(P, null, Effect.errno(Errno.EIO, 1.0)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects null effect")
    void nullEffect() {
      assertThatThrownBy(() -> new IoRule(P, IoOperation.OPEN, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("op×effect compatibility matrix")
  class CompatibilityMatrix {

    @ParameterizedTest
    @EnumSource(IoOperation.class)
    @DisplayName("ERRNO is valid on every operation")
    void errnoEveryOp(final IoOperation op) {
      // does not throw
      new IoRule(P, op, Effect.errno(Errno.EIO, 1.0));
    }

    @ParameterizedTest
    @EnumSource(IoOperation.class)
    @DisplayName("LATENCY is valid on every operation")
    void latencyEveryOp(final IoOperation op) {
      new IoRule(P, op, Effect.latency(Duration.ofMillis(100)));
    }

    @ParameterizedTest
    @EnumSource(
        value = IoOperation.class,
        names = {"WRITE", "PWRITE"})
    @DisplayName("TORN is valid on write-family operations")
    void tornValidOnWrites(final IoOperation op) {
      new IoRule(P, op, Effect.torn(0.5));
    }

    @ParameterizedTest
    @EnumSource(
        value = IoOperation.class,
        names = {"WRITE", "PWRITE"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("TORN is rejected on non-write operations")
    void tornInvalidOnOthers(final IoOperation op) {
      assertThatThrownBy(() -> new IoRule(P, op, Effect.torn(0.5)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("TORN");
    }

    @ParameterizedTest
    @EnumSource(
        value = IoOperation.class,
        names = {"READ", "PREAD"})
    @DisplayName("CORRUPT is valid on read-family operations")
    void corruptValidOnReads(final IoOperation op) {
      new IoRule(P, op, Effect.corrupt(0.5));
    }

    @ParameterizedTest
    @EnumSource(
        value = IoOperation.class,
        names = {"READ", "PREAD"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("CORRUPT is rejected on non-read operations")
    void corruptInvalidOnOthers(final IoOperation op) {
      assertThatThrownBy(() -> new IoRule(P, op, Effect.corrupt(0.5)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("CORRUPT");
    }
  }

  @Nested
  @DisplayName("static factories")
  class Factories {

    @Test
    @DisplayName("errno() builds an ErrnoFault rule")
    void errnoFactory() {
      final IoRule r = IoRule.errno(P, IoOperation.WRITE, Errno.ENOSPC, 0.5);
      assertThat(r.effect()).isInstanceOf(Effect.ErrnoFault.class);
      assertThat(((Effect.ErrnoFault) r.effect()).errno()).isEqualTo(Errno.ENOSPC);
      assertThat(((Effect.ErrnoFault) r.effect()).probability()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("latency() builds a Latency rule")
    void latencyFactory() {
      final IoRule r = IoRule.latency(P, IoOperation.FSYNC, Duration.ofMillis(200));
      assertThat(r.effect()).isInstanceOf(Effect.Latency.class);
    }

    @Test
    @DisplayName("torn() rejects non-write operations at factory level")
    void tornFactoryRejectsNonWrite() {
      assertThatThrownBy(() -> IoRule.torn(P, IoOperation.READ, 0.5))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("corrupt() rejects non-read operations at factory level")
    void corruptFactoryRejectsNonRead() {
      assertThatThrownBy(() -> IoRule.corrupt(P, IoOperation.WRITE, 0.5))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("operations covered by matrix tests: all 13")
  void coverageBound() {
    // Defensive: if the enum grows, the matrix tests above also expand via @EnumSource(IoOperation)
    assertThat(EnumSet.allOf(IoOperation.class)).hasSize(13);
  }
}
