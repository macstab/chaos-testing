/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.annotation.l1.translators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.macstab.chaos.filesystem.annotation.l1.fsync.ChaosFsyncEio;
import com.macstab.chaos.filesystem.annotation.l1.open.ChaosOpenEacces;
import com.macstab.chaos.filesystem.annotation.l1.open.ChaosOpenEnoent;
import com.macstab.chaos.filesystem.annotation.l1.open.ChaosOpenLatency;
import com.macstab.chaos.filesystem.annotation.l1.pread.ChaosPreadCorrupt;
import com.macstab.chaos.filesystem.annotation.l1.pread.ChaosPreadEio;
import com.macstab.chaos.filesystem.annotation.l1.pwrite.ChaosPwriteTorn;
import com.macstab.chaos.filesystem.annotation.l1.read.ChaosReadCorrupt;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteEnospc;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteLatency;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteTorn;
import com.macstab.chaos.filesystem.model.Effect;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

@DisplayName("Filesystem L1 translators — table-driven")
class FilesystemTranslatorsTest {

  @ChaosOpenEacces
  static class OpenEacces {}

  @ChaosOpenEnoent
  static class OpenEnoent {}

  @ChaosWriteEnospc
  static class WriteEnospc {}

  @ChaosPreadEio
  static class PreadEio {}

  @ChaosFsyncEio
  static class FsyncEio {}

  @ChaosOpenEacces(probability = 0.05)
  static class OpenEaccesCustom {}

  @ChaosOpenLatency
  static class OpenLatencyDefault {}

  @ChaosWriteLatency(delayMs = 500L)
  static class WriteLatencyCustom {}

  @ChaosWriteTorn
  static class WriteTornDefault {}

  @ChaosPwriteTorn(probability = 0.01)
  static class PwriteTornCustom {}

  @ChaosReadCorrupt
  static class ReadCorruptDefault {}

  @ChaosPreadCorrupt(probability = 0.02)
  static class PreadCorruptCustom {}

  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface PlainNoBinding {}

  @PlainNoBinding
  static class WithPlainNoBinding {}

  private static Annotation pick(final Class<?> clazz) {
    for (final Annotation a : clazz.getAnnotations()) {
      if (a.annotationType().getName().startsWith("java.")) {
        continue;
      }
      return a;
    }
    throw new IllegalStateException("No annotation on " + clazz);
  }

  @Nested
  @DisplayName("IoErrnoTranslator")
  class Errno {

    static Stream<Arguments> tuples() {
      return Stream.of(
          Arguments.of(OpenEacces.class, IoOperation.OPEN, com.macstab.chaos.filesystem.model.Errno.EACCES),
          Arguments.of(OpenEnoent.class, IoOperation.OPEN, com.macstab.chaos.filesystem.model.Errno.ENOENT),
          Arguments.of(WriteEnospc.class, IoOperation.WRITE, com.macstab.chaos.filesystem.model.Errno.ENOSPC),
          Arguments.of(PreadEio.class, IoOperation.PREAD, com.macstab.chaos.filesystem.model.Errno.EIO),
          Arguments.of(FsyncEio.class, IoOperation.FSYNC, com.macstab.chaos.filesystem.model.Errno.EIO));
    }

    @ParameterizedTest(name = "{0} → ({1}, {2})")
    @MethodSource("tuples")
    void translates(
        final Class<?> fixture, final IoOperation op, final com.macstab.chaos.filesystem.model.Errno expectedErrno) {
      final IoRule rule = IoErrnoTranslator.buildRule(pick(fixture));
      assertThat(rule.path()).isInstanceOf(PathPrefix.Wildcard.class);
      assertThat(rule.operation()).isEqualTo(op);
      assertThat(rule.effect()).isInstanceOf(Effect.ErrnoFault.class);
      assertThat(((Effect.ErrnoFault) rule.effect()).errno()).isEqualTo(expectedErrno);
      assertThat(((Effect.ErrnoFault) rule.effect()).probability()).isEqualTo(1.0);
    }

    @Test
    void customProbability() {
      final IoRule rule = IoErrnoTranslator.buildRule(pick(OpenEaccesCustom.class));
      assertThat(((Effect.ErrnoFault) rule.effect()).probability()).isEqualTo(0.05);
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> IoErrnoTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@IoErrnoBinding meta-annotation missing");
    }
  }

  @Nested
  @DisplayName("IoLatencyTranslator")
  class Latency {

    @Test
    void defaultDelay50ms() {
      final IoRule rule = IoLatencyTranslator.buildRule(pick(OpenLatencyDefault.class));
      assertThat(rule.operation()).isEqualTo(IoOperation.OPEN);
      assertThat(((Effect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void customDelay() {
      final IoRule rule = IoLatencyTranslator.buildRule(pick(WriteLatencyCustom.class));
      assertThat(rule.operation()).isEqualTo(IoOperation.WRITE);
      assertThat(((Effect.Latency) rule.effect()).delay()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void missingBinding() {
      assertThatThrownBy(() -> IoLatencyTranslator.buildRule(pick(WithPlainNoBinding.class)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("@IoLatencyBinding meta-annotation missing");
    }
  }

  @Nested
  @DisplayName("IoTornTranslator")
  class Torn {

    @Test
    void defaults() {
      final IoRule rule = IoTornTranslator.buildRule(pick(WriteTornDefault.class));
      assertThat(rule.operation()).isEqualTo(IoOperation.WRITE);
      assertThat(rule.effect()).isInstanceOf(Effect.Torn.class);
      assertThat(((Effect.Torn) rule.effect()).probability()).isEqualTo(0.001);
    }

    @Test
    void customProbability() {
      final IoRule rule = IoTornTranslator.buildRule(pick(PwriteTornCustom.class));
      assertThat(rule.operation()).isEqualTo(IoOperation.PWRITE);
      assertThat(((Effect.Torn) rule.effect()).probability()).isEqualTo(0.01);
    }
  }

  @Nested
  @DisplayName("IoCorruptTranslator")
  class Corrupt {

    @Test
    void defaults() {
      final IoRule rule = IoCorruptTranslator.buildRule(pick(ReadCorruptDefault.class));
      assertThat(rule.operation()).isEqualTo(IoOperation.READ);
      assertThat(rule.effect()).isInstanceOf(Effect.Corrupt.class);
      assertThat(((Effect.Corrupt) rule.effect()).probability()).isEqualTo(0.001);
    }

    @Test
    void customProbability() {
      final IoRule rule = IoCorruptTranslator.buildRule(pick(PreadCorruptCustom.class));
      assertThat(rule.operation()).isEqualTo(IoOperation.PREAD);
      assertThat(((Effect.Corrupt) rule.effect()).probability()).isEqualTo(0.02);
    }
  }
}
