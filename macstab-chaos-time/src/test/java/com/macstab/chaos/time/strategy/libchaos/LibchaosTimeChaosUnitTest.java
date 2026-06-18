/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.time.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosUnsupportedOperationException;
import com.macstab.chaos.core.exception.LibchaosNotPreparedException;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.time.api.RuleHandle;
import com.macstab.chaos.time.model.TimeClock;
import com.macstab.chaos.time.model.TimeErrno;
import com.macstab.chaos.time.model.TimeRule;
import com.macstab.chaos.time.model.TimeSelector;

@DisplayName("LibchaosTimeChaos (unit)")
class LibchaosTimeChaosUnitTest {

  private LibchaosTransport transport;
  private GenericContainer<?> container;
  private LibchaosTimeChaos chaos;

  @BeforeEach
  void setUp() {
    transport = mock(LibchaosTransport.class);
    container = mock(GenericContainer.class);
    chaos = new LibchaosTimeChaos(transport);
    when(transport.isActive(container)).thenReturn(true);
  }

  @Nested
  @DisplayName("supports")
  class Supports {
    @Test
    void delegates() {
      assertThat(chaos.supports(container)).isTrue();
      when(transport.isActive(container)).thenReturn(false);
      assertThat(chaos.supports(container)).isFalse();
    }

    @Test
    void probeFails() {
      when(transport.isActive(container)).thenThrow(new RuntimeException("docker down"));
      assertThat(chaos.supports(container)).isFalse();
    }
  }

  @Nested
  @DisplayName("requirePrepared gate")
  class RequirePrepared {
    @BeforeEach
    void unprepared() {
      when(transport.isActive(container)).thenReturn(false);
    }

    @Test
    void apply() {
      assertThatThrownBy(
              () ->
                  chaos.apply(
                      container, TimeRule.errno(TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL)))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("time")
          .hasMessageContaining("@SyscallLevelChaos");
    }

    @Test
    void otherGeneric() {
      assertThatThrownBy(() -> chaos.applyAll(container, List.of()))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.remove(container, new RuleHandle("r1")))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.removeAll(container))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    void typed() {
      assertThatThrownBy(() -> chaos.failClockGet(container, 0.5))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(
              () -> chaos.skewClock(container, TimeClock.MONOTONIC, Duration.ofMillis(-1500)))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }
  }

  @Nested
  @DisplayName("portable verbs route via ChaosUnsupportedOperationException")
  class PortableUnsupported {
    @Test
    void shift() {
      assertThatThrownBy(() -> chaos.shift(container, Duration.ofSeconds(60)))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void drift() {
      assertThatThrownBy(() -> chaos.drift(container, 2.0))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("apply / applyAll / remove plumbing")
  class Plumbing {
    @Test
    void apply() {
      final RuleHandle h =
          chaos.apply(container, TimeRule.errno(TimeSelector.CLOCK_GETTIME, TimeErrno.EINVAL));
      assertThat(h.owner()).matches("r[0-9]+");
      verify(transport).addRule(eq(container), eq(h.owner()), anyString());
    }

    @Test
    void applyAllEmpty() {
      assertThat(chaos.applyAll(container, List.of())).isEmpty();
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }

    @Test
    void applyAllN() {
      chaos.applyAll(
          container,
          List.of(
              TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR),
              TimeRule.latency(TimeSelector.USLEEP, Duration.ofMillis(20))));
      verify(transport, times(2)).addRule(eq(container), anyString(), anyString());
    }

    @Test
    void applyAllNullElement() {
      final java.util.ArrayList<TimeRule> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> chaos.applyAll(container, withNull))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void removeApplied() {
      final RuleHandle h =
          chaos.apply(container, TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR));
      chaos.remove(container, h);
      verify(transport).removeRules(container, h.owner());
    }

    @Test
    void removeUnknown() {
      chaos.remove(container, new RuleHandle("r_missing"));
      verify(transport, never()).removeRules(any(), anyString());
    }

    @Test
    void removeAll() {
      final RuleHandle h1 =
          chaos.apply(container, TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR));
      final RuleHandle h2 =
          chaos.apply(container, TimeRule.errno(TimeSelector.USLEEP, TimeErrno.EINTR));
      chaos.removeAll(container);
      verify(transport).removeRules(container, h1.owner());
      verify(transport).removeRules(container, h2.owner());
    }
  }

  @Nested
  @DisplayName("raw escape hatches")
  class Raw {
    @Test
    void errnoEscapeHatch() {
      chaos.errno(container, TimeSelector.NANOSLEEP, TimeErrno.EINTR, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("nanosleep:ERRNO:EINTR@0.5"));
    }

    @Test
    void latencyEscapeHatch() {
      chaos.latency(container, TimeSelector.CLOCK_GETTIME, Duration.ofMillis(20));
      verify(transport).addRule(eq(container), anyString(), contains("clock_gettime:LATENCY:20"));
    }

    @Test
    void offsetEscapeHatch() {
      chaos.offset(container, TimeClock.MONOTONIC, Duration.ofMillis(-1500));
      verify(transport)
          .addRule(eq(container), anyString(), contains("clock_gettime/monotonic:OFFSET:-1500"));
    }

    @Test
    void offsetEscapeHatchProbability() {
      chaos.offset(container, TimeClock.REALTIME, Duration.ofMillis(500), 0.1);
      verify(transport)
          .addRule(eq(container), anyString(), contains("clock_gettime/realtime:OFFSET:500@0.1"));
    }
  }

  @Nested
  @DisplayName("clock_gettime verbs")
  class ClockGet {
    @Test
    void failClockGet() {
      chaos.failClockGet(container, 0.01);
      verify(transport)
          .addRule(eq(container), anyString(), contains("clock_gettime:ERRNO:EINVAL@0.01"));
    }

    @Test
    void failClockGetWithErrno() {
      chaos.failClockGetWithErrno(container, TimeErrno.EPERM, 0.5);
      verify(transport)
          .addRule(eq(container), anyString(), contains("clock_gettime:ERRNO:EPERM@0.5"));
    }

    @Test
    void slowClockGet() {
      chaos.slowClockGet(container, Duration.ofMillis(100));
      verify(transport).addRule(eq(container), anyString(), contains("clock_gettime:LATENCY:100"));
    }

    @Test
    @DisplayName("skewClock emits clock_gettime/<clock>:OFFSET:<signedMs>")
    void skewClock() {
      chaos.skewClock(container, TimeClock.MONOTONIC, Duration.ofMillis(-1500));
      verify(transport)
          .addRule(eq(container), anyString(), contains("clock_gettime/monotonic:OFFSET:-1500"));
    }

    @Test
    void skewClockPositive() {
      chaos.skewClock(container, TimeClock.REALTIME, Duration.ofMillis(60_000));
      verify(transport)
          .addRule(eq(container), anyString(), contains("clock_gettime/realtime:OFFSET:60000"));
    }
  }

  @Nested
  @DisplayName("nanosleep verbs")
  class Nanosleep {
    @Test
    void failNanosleep() {
      chaos.failNanosleep(container, 0.1);
      verify(transport).addRule(eq(container), anyString(), contains("nanosleep:ERRNO:EINTR@0.1"));
    }

    @Test
    void slowNanosleep() {
      chaos.slowNanosleep(container, Duration.ofMillis(200));
      verify(transport).addRule(eq(container), anyString(), contains("nanosleep:LATENCY:200"));
    }

    @Test
    @DisplayName("signalInterruptSleep → nanosleep:ERRNO:EINTR alias")
    void signalInterruptSleep() {
      chaos.signalInterruptSleep(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("nanosleep:ERRNO:EINTR@0.5"));
    }
  }

  @Nested
  @DisplayName("usleep verbs")
  class Usleep {
    @Test
    void failUsleep() {
      chaos.failUsleep(container, 0.1);
      verify(transport).addRule(eq(container), anyString(), contains("usleep:ERRNO:EINTR@0.1"));
    }

    @Test
    void slowUsleep() {
      chaos.slowUsleep(container, Duration.ofMillis(50));
      verify(transport).addRule(eq(container), anyString(), contains("usleep:LATENCY:50"));
    }

    @Test
    @DisplayName("signalInterruptMicrosleep → usleep:ERRNO:EINTR alias")
    void signalInterruptMicrosleep() {
      chaos.signalInterruptMicrosleep(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("usleep:ERRNO:EINTR@0.5"));
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {
    @Test
    void resetUnprepared() {
      when(transport.isActive(container)).thenReturn(false);
      chaos.reset(container);
      verify(transport, never()).removeRules(any(), anyString());
    }

    @Test
    void resetPrepared() {
      final RuleHandle h =
          chaos.apply(container, TimeRule.errno(TimeSelector.NANOSLEEP, TimeErrno.EINTR));
      chaos.reset(container);
      verify(transport).removeRules(container, h.owner());
    }

    @Test
    void installToolsNoOp() {
      chaos.installTools(container);
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }

    @Test
    void isSupportedTrue() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }
}
