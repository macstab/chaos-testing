/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.defaults;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosProviderNotFoundException;
import com.macstab.chaos.core.model.Signal;

/**
 * Coverage tests for all NoOp default implementations.
 *
 * <p>Each NoOp throws {@link ChaosProviderNotFoundException} for active operations
 * and returns false/no-op for isSupported/installTools/reset.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("NoOp Defaults - Coverage")
class NoOpDefaultsCoverageTest {

  @SuppressWarnings("resource")
  private final GenericContainer<?> container = mock(GenericContainer.class);

  @Nested
  @DisplayName("NoOpNetworkChaos")
  class NoOpNetworkChaosTest {
    final NoOpNetworkChaos sut = new NoOpNetworkChaos();

    @Test void injectLatency_throws() {
      assertThatThrownBy(() -> sut.injectLatency(container, Duration.ofMillis(100)))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void injectLatencyWithJitter_throws() {
      assertThatThrownBy(() -> sut.injectLatencyWithJitter(container, Duration.ofMillis(100), Duration.ofMillis(10)))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void injectPacketLoss_throws() {
      assertThatThrownBy(() -> sut.injectPacketLoss(container, 0.1))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void injectCorrelatedPacketLoss_throws() {
      assertThatThrownBy(() -> sut.injectCorrelatedPacketLoss(container, 0.1, 0.5))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void limitBandwidth_throws() {
      assertThatThrownBy(() -> sut.limitBandwidth(container, "100mbit"))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void partitionFrom_throws() {
      assertThatThrownBy(() -> sut.partitionFrom(container, container))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
  }

  @Nested
  @DisplayName("NoOpDiskChaos")
  class NoOpDiskChaosTest {
    final NoOpDiskChaos sut = new NoOpDiskChaos();

    @Test void limitWriteBandwidth_throws() {
      assertThatThrownBy(() -> sut.limitWriteBandwidth(container, "10MB/s"))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void limitReadBandwidth_throws() {
      assertThatThrownBy(() -> sut.limitReadBandwidth(container, "10MB/s"))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void limitReadIOPS_throws() {
      assertThatThrownBy(() -> sut.limitReadIOPS(container, 100))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void limitWriteIOPS_throws() {
      assertThatThrownBy(() -> sut.limitWriteIOPS(container, 100))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void fillDisk_throws() {
      assertThatThrownBy(() -> sut.fillDisk(container, "1G", 90))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void stressDisk_throws() {
      assertThatThrownBy(() -> sut.stressDisk(container, 4))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
  }

  @Nested
  @DisplayName("NoOpMemoryChaos")
  class NoOpMemoryChaosTest {
    final NoOpMemoryChaos sut = new NoOpMemoryChaos();

    @Test void setLimit_throws() {
      assertThatThrownBy(() -> sut.setLimit(container, "512M"))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void setPressure_throws() {
      assertThatThrownBy(() -> sut.setPressure(container, "80%"))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void stress_throws() {
      assertThatThrownBy(() -> sut.stress(container, "256M"))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
  }

  @Nested
  @DisplayName("NoOpCpuChaos")
  class NoOpCpuChaosTest {
    final NoOpCpuChaos sut = new NoOpCpuChaos();

    @Test void throttle_throws() {
      assertThatThrownBy(() -> sut.throttle(container, 50))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void stress_workers_throws() {
      assertThatThrownBy(() -> sut.stress(container, 4))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void stress_duration_throws() {
      assertThatThrownBy(() -> sut.stress(container, 4, Duration.ofSeconds(10)))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void getCurrentUsage_throws() {
      assertThatThrownBy(() -> sut.getCurrentUsage(container))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
  }

  @Nested
  @DisplayName("NoOpProcessChaos")
  class NoOpProcessChaosTest {
    final NoOpProcessChaos sut = new NoOpProcessChaos();

    @Test void kill_throws() {
      assertThatThrownBy(() -> sut.kill(container, "myapp", Signal.SIGKILL))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void pause_throws() {
      assertThatThrownBy(() -> sut.pause(container, "myapp", Duration.ofSeconds(5)))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void limitProcesses_throws() {
      assertThatThrownBy(() -> sut.limitProcesses(container, 10))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
  }

  @Nested
  @DisplayName("NoOpDnsChaos")
  class NoOpDnsChaosTest {
    final NoOpDnsChaos sut = new NoOpDnsChaos();

    @Test void blockResolution_throws() {
      assertThatThrownBy(() -> sut.blockResolution(container, "example.com"))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void delayResolution_throws() {
      assertThatThrownBy(() -> sut.delayResolution(container, Duration.ofMillis(500)))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
  }

  @Nested
  @DisplayName("NoOpTimeChaos")
  class NoOpTimeChaosTest {
    final NoOpTimeChaos sut = new NoOpTimeChaos();

    @Test void shift_throws() {
      assertThatThrownBy(() -> sut.shift(container, Duration.ofHours(1)))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void drift_throws() {
      assertThatThrownBy(() -> sut.drift(container, 0.1))
          .isInstanceOf(ChaosProviderNotFoundException.class);
    }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
  }

  @Nested
  @DisplayName("NoOpCacheChaos")
  class NoOpCacheChaosTest {
    final NoOpCacheChaos sut = new NoOpCacheChaos();

    @Test void injectMisses_noOp() {
      assertThatCode(() -> sut.injectMisses(container, "*", 0.5)).doesNotThrowAnyException();
    }
    @Test void slowResponse_noOp() {
      assertThatCode(() -> sut.slowResponse(container, Duration.ofMillis(100))).doesNotThrowAnyException();
    }
    @Test void forceEviction_noOp() {
      assertThatCode(() -> sut.forceEviction(container, 50)).doesNotThrowAnyException();
    }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
  }

  @Nested
  @DisplayName("NoOpFilesystemChaos")
  class NoOpFilesystemChaosTest {
    final NoOpFilesystemChaos sut = new NoOpFilesystemChaos();

    @Test void fillDisk_noOp() {
      assertThatCode(() -> sut.fillDisk(container, "1G")).doesNotThrowAnyException();
    }
    @Test void injectPermissionErrors_noOp() {
      assertThatCode(() -> sut.injectPermissionErrors(container, "/tmp", 0.5)).doesNotThrowAnyException();
    }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
  }

  @Nested
  @DisplayName("NoOpConnectionChaos")
  class NoOpConnectionChaosTest {
    final NoOpConnectionChaos sut = new NoOpConnectionChaos();

    @Test void addLatency_noOp() {
      assertThatCode(() -> sut.addLatency(container, "db", Duration.ofMillis(100))).doesNotThrowAnyException();
    }
    @Test void dropPackets_noOp() {
      assertThatCode(() -> sut.dropPackets(container, "db", 0.1)).doesNotThrowAnyException();
    }
    @Test void limitBandwidth_noOp() {
      assertThatCode(() -> sut.limitBandwidth(container, "db", 1_000_000L)).doesNotThrowAnyException();
    }
    @Test void timeoutConnections_noOp() {
      assertThatCode(() -> sut.timeoutConnections(container, "db", Duration.ofSeconds(1))).doesNotThrowAnyException();
    }
    @Test void slowClose_noOp() {
      assertThatCode(() -> sut.slowClose(container, "db", Duration.ofMillis(500))).doesNotThrowAnyException();
    }
    @Test void rejectConnections_noOp() {
      assertThatCode(() -> sut.rejectConnections(container, "db")).doesNotThrowAnyException();
    }
    @Test void reset_noOp()        { assertThatCode(() -> sut.reset(container)).doesNotThrowAnyException(); }
    @Test void isSupported_false() { assertThat(sut.isSupported()).isFalse(); }
    @Test void installTools_noOp() { assertThatCode(() -> sut.installTools(container)).doesNotThrowAnyException(); }
  }
}
