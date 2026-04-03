/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.macstab.chaos.network.condition.DisabledOnNonLinuxHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.Capability;

import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Integration tests for {@link CgroupsCpuChaos} (Debian redis:7.4).
 *
 * <p>All tests share a single container per nested class via {@code @BeforeEach/@AfterEach}.
 * Tests requiring SIGTERM-based reset (LinuxKit limitation) are annotated
 * {@code @DisabledOnNonLinuxHost}. taskset-write and renice operations work in any Testcontainers
 * container via the Moby VM, so those tests run unconditionally on macOS.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsCpuChaos")
class CgroupsCpuChaosTest {

  private GenericContainer<?> container;
  private CgroupsCpuChaos chaos;

  @BeforeEach
  void setUp() {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
        // SYS_NICE allows taskset -p write and renice across uid boundaries.
        // redis-server runs as uid 999; execInContainer runs as root (uid 0).
        // Without this capability, taskset/renice on PID 1 return "Operation not permitted".
        .withCreateContainerCmdModifier(cmd -> cmd.withCapAdd(Capability.SYS_NICE));
    container.start();
    chaos = new CgroupsCpuChaos();
  }

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  // ==================== Throttling ====================

  @Nested
  @DisplayName("throttle")
  class Throttle {

    @Test
    @DisplayName("starts cpulimit process in container")
    void startsCpulimit() {
      // WHEN
      chaos.throttle(container, 50);

      // THEN
      assertThat(chaos.isThrottled(container)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 25, 50, 75, 100})
    @DisplayName("accepts valid percentage range [1, 100]")
    void acceptsValidPercentage(final int percentage) {
      // WHEN
      chaos.throttle(container, percentage);

      // THEN
      assertThat(chaos.isThrottled(container)).isTrue();
    }

    @Test
    @DisplayName("isThrottled returns false after reset")
    void falseAfterReset() {
      // GIVEN
      chaos.throttle(container, 50);

      // WHEN
      chaos.reset(container);

      // THEN
      assertThat(chaos.isThrottled(container)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, 200})
    @DisplayName("rejects invalid percentage")
    void rejectsInvalidPercentage(final int percentage) {
      assertThatThrownBy(() -> chaos.throttle(container, percentage))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("must be in [1, 100]");
    }

    @Test
    @DisplayName("rejects null container")
    void rejectsNullContainer() {
      assertThatThrownBy(() -> chaos.throttle(null, 50))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects stopped container")
    void rejectsStoppedContainer() {
      container.stop();
      assertThatThrownBy(() -> chaos.throttle(container, 50))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }
  }

  @Nested
  @DisplayName("throttle with duration")
  class ThrottleWithDuration {

    @Test
    @DisplayName("starts cpulimit immediately after call")
    void startsCpulimitImmediately() {
      // WHEN
      chaos.throttle(container, 50, Duration.ofSeconds(30));

      // THEN
      assertThat(chaos.isThrottled(container)).isTrue();
    }

    @Test
    @DisplayName("auto-resets after duration")
    void autoResetsAfterDuration() throws Exception {
      // WHEN
      chaos.throttle(container, 50, Duration.ofSeconds(3));

      // THEN — running after 1s
      Thread.sleep(1_000);
      assertThat(chaos.isThrottled(container)).isTrue();

      // THEN — gone after 6s total
      await().atMost(6, TimeUnit.SECONDS)
          .pollInterval(500, TimeUnit.MILLISECONDS)
          .until(() -> !chaos.isThrottled(container));
    }

    @Test
    @DisplayName("rejects duration <= 0")
    void rejectsZeroDuration() {
      assertThatThrownBy(() -> chaos.throttle(container, 50, Duration.ofSeconds(0)))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("duration must be > 0");
    }

    @Test
    @DisplayName("rejects null duration")
    void rejectsNullDuration() {
      assertThatThrownBy(() -> chaos.throttle(container, 50, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== CPU Compute Stress ====================

  @Nested
  @DisplayName("stress (CPU)")
  class Stress {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    @DisplayName("starts stress-ng workers")
    void startsStressNg(final int workers) {
      // WHEN
      chaos.stress(container, workers);

      // THEN
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("with duration: process running immediately")
    void withDurationRunningImmediately() {
      // WHEN
      chaos.stress(container, 1, Duration.ofSeconds(30));

      // THEN
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("isStressed returns false after reset")
    void falseAfterReset() {
      // GIVEN
      chaos.stress(container, 1);

      // WHEN
      chaos.reset(container);

      // THEN
      assertThat(chaos.isStressed(container)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    @DisplayName("rejects invalid workers")
    void rejectsInvalidWorkers(final int workers) {
      assertThatThrownBy(() -> chaos.stress(container, workers))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("must be >= 1");
    }

    @Test
    @DisplayName("rejects null container")
    void rejectsNullContainer() {
      assertThatThrownBy(() -> chaos.stress(null, 1))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("stressWithThrottle")
  class StressWithThrottle {

    @Test
    @DisplayName("starts both stress-ng and cpulimit")
    void startsBothProcesses() {
      // WHEN
      chaos.stressWithThrottle(container, 2, 50);

      // THEN
      assertThat(chaos.isStressed(container)).isTrue();
      assertThat(chaos.isThrottled(container)).isTrue();
    }

    @Test
    @DisplayName("both gone after reset")
    void cleansUpAfterReset() {
      // GIVEN
      chaos.stressWithThrottle(container, 1, 50);

      // WHEN
      chaos.reset(container);

      // THEN
      assertThat(chaos.isStressed(container)).isFalse();
      assertThat(chaos.isThrottled(container)).isFalse();
    }

    @Test
    @DisplayName("rejects invalid workers")
    void rejectsInvalidWorkers() {
      assertThatThrownBy(() -> chaos.stressWithThrottle(container, 0, 50))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("rejects invalid percentage")
    void rejectsInvalidPercentage() {
      assertThatThrownBy(() -> chaos.stressWithThrottle(container, 2, 101))
          .isInstanceOf(ChaosConfigurationException.class);
    }
  }

  // ==================== Cache Stress ====================

  @Nested
  @DisplayName("stressCache")
  class StressCache {

    @Test
    @DisplayName("starts stress-ng cache workers")
    void startsWorkers() {
      chaos.stressCache(container, 1);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("with duration: running immediately")
    void withDuration() {
      chaos.stressCache(container, 1, Duration.ofSeconds(30));
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("resets cleanly")
    void resetsCleanly() {
      chaos.stressCache(container, 1);
      chaos.reset(container);
      assertThat(chaos.isStressed(container)).isFalse();
    }

    @Test
    @DisplayName("rejects invalid workers")
    void rejectsInvalidWorkers() {
      assertThatThrownBy(() -> chaos.stressCache(container, 0))
          .isInstanceOf(ChaosConfigurationException.class);
    }
  }

  @Nested
  @DisplayName("stressCacheLine")
  class StressCacheLine {

    @Test
    @DisplayName("starts stress-ng cacheline workers")
    void startsWorkers() {
      chaos.stressCacheLine(container, 1);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("resets cleanly")
    void resetsCleanly() {
      chaos.stressCacheLine(container, 1);
      chaos.reset(container);
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  // ==================== Scheduler / Context Switch Stress ====================

  @Nested
  @DisplayName("stressContextSwitch")
  class StressContextSwitch {

    @Test
    @DisplayName("starts stress-ng context workers")
    void startsWorkers() {
      chaos.stressContextSwitch(container, 1);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("resets cleanly")
    void resetsCleanly() {
      chaos.stressContextSwitch(container, 1);
      chaos.reset(container);
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  @Nested
  @DisplayName("stressThreadSwitch")
  class StressThreadSwitch {

    @Test
    @DisplayName("starts stress-ng switch workers")
    void startsWorkers() {
      chaos.stressThreadSwitch(container, 1);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("resets cleanly")
    void resetsCleanly() {
      chaos.stressThreadSwitch(container, 1);
      chaos.reset(container);
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  // ==================== Pipeline / Interrupt Stress ====================

  @Nested
  @DisplayName("stressBranchPredictor")
  class StressBranchPredictor {

    @Test
    @DisplayName("starts stress-ng branch workers")
    void startsWorkers() {
      chaos.stressBranchPredictor(container, 1);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("resets cleanly")
    void resetsCleanly() {
      chaos.stressBranchPredictor(container, 1);
      chaos.reset(container);
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  @Nested
  @DisplayName("stressTimerInterrupts")
  class StressTimerInterrupts {

    @Test
    @DisplayName("starts stress-ng hrtimers workers")
    void startsWorkers() {
      chaos.stressTimerInterrupts(container, 1);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("resets cleanly")
    void resetsCleanly() {
      chaos.stressTimerInterrupts(container, 1);
      chaos.reset(container);
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  @Nested
  @DisplayName("stressMatrix")
  class StressMatrix {

    @Test
    @DisplayName("starts stress-ng matrix workers")
    void startsWorkers() {
      chaos.stressMatrix(container, 1);
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("with duration: running immediately")
    void withDuration() {
      chaos.stressMatrix(container, 1, Duration.ofSeconds(30));
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("resets cleanly")
    void resetsCleanly() {
      chaos.stressMatrix(container, 1);
      chaos.reset(container);
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  // ==================== CPU Affinity ====================

  @Nested
  @DisplayName("pinToCoreMask")
  class PinToCoreMask {

    @Test
    @DisplayName("isAffinityPinned is false before pinning")
    void falseBeforePinning() {
      assertThat(chaos.isAffinityPinned(container)).isFalse();
    }

    @Test
    @DisplayName("isAffinityPinned is true after single-core pin")
    void trueAfterPin() {
      chaos.pinToCoreMask(container, 0x1L);
      assertThat(chaos.isAffinityPinned(container)).isTrue();
    }

    @Test
    @DisplayName("getPinnedCoreMask returns correct mask")
    void returnsCorrectMask() {
      chaos.pinToCoreMask(container, 0x1L);
      assertThat(chaos.getPinnedCoreMask(container)).isEqualTo(0x1L);
    }

    @Test
    @DisplayName("reset restores full-core affinity")
    void resetRestoresFullAffinity() {
      chaos.pinToCoreMask(container, 0x1L);
      chaos.reset(container);
      assertThat(chaos.isAffinityPinned(container)).isFalse();
    }

    @Test
    @DisplayName("isAffinityPinned returns false for stopped container")
    void falseForStopped() {
      container.stop();
      assertThat(chaos.isAffinityPinned(container)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -100L})
    @DisplayName("rejects zero or negative mask")
    void rejectsInvalidMask(final long mask) {
      assertThatThrownBy(() -> chaos.pinToCoreMask(container, mask))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("rejects stopped container")
    void rejectsStoppedContainer() {
      container.stop();
      assertThatThrownBy(() -> chaos.pinToCoreMask(container, 0x1L))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== Process Priority ====================

  @Nested
  @DisplayName("degradePriority / getNiceValue / resetPriority")
  class ProcessPriority {

    @Test
    @DisplayName("getNiceValue returns 0 for fresh container")
    void defaultIsZero() {
      assertThat(chaos.getNiceValue(container)).isEqualTo(0);
    }

    @Test
    @DisplayName("getNiceValue reflects degraded priority")
    void reflectsDegradation() {
      chaos.degradePriority(container, 10);
      assertThat(chaos.getNiceValue(container)).isEqualTo(10);
    }

    @Test
    @DisplayName("degradePriority to maximum nice value 19")
    void maxNice() {
      chaos.degradePriority(container, 19);
      assertThat(chaos.getNiceValue(container)).isEqualTo(19);
    }

    @Test
    @DisplayName("resetPriority on stopped container does not throw")
    void resetOnStoppedDoesNotThrow() {
      container.stop();
      chaos.resetPriority(container);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 20, 100})
    @DisplayName("rejects invalid nice values")
    void rejectsInvalidNice(final int niceValue) {
      assertThatThrownBy(() -> chaos.degradePriority(container, niceValue))
          .isInstanceOf(ChaosConfigurationException.class);
    }

    @Test
    @DisplayName("rejects stopped container for degradePriority")
    void rejectsStoppedForDegrade() {
      container.stop();
      assertThatThrownBy(() -> chaos.degradePriority(container, 10))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejects stopped container for getNiceValue")
    void rejectsStoppedForGetNice() {
      container.stop();
      assertThatThrownBy(() -> chaos.getNiceValue(container))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ==================== Observability ====================

  @Nested
  @DisplayName("observability")
  class Observability {

    @Test
    @DisplayName("getAvailableCores returns at least 1")
    void coreCountPositive() {
      assertThat(chaos.getAvailableCores(container)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("getCurrentUsage > 0 after stress")
    void usageDetectsStress() {
      chaos.stress(container, 2);
      await().atMost(10, TimeUnit.SECONDS)
          .pollInterval(1, TimeUnit.SECONDS)
          .until(() -> chaos.getCurrentUsage(container) > 0);
    }

    @Test
    @DisplayName("isThrottled returns false for stopped container")
    void isThrottledFalseForStopped() {
      container.stop();
      assertThat(chaos.isThrottled(container)).isFalse();
    }

    @Test
    @DisplayName("isStressed returns false for stopped container")
    void isStressedFalseForStopped() {
      container.stop();
      assertThat(chaos.isStressed(container)).isFalse();
    }

    @Test
    @DisplayName("isSupported returns true")
    void isSupported() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }

  // ==================== Compound Scenarios ====================

  @Nested
  @DisplayName("compound scenarios")
  class CompoundScenarios {

    @Test
    @DisplayName("stress + throttle: both visible, both cleaned on reset")
    void stressThenThrottleThenReset() {
      // GIVEN
      chaos.stress(container, 2);
      chaos.throttle(container, 50);

      // THEN — both active
      assertThat(chaos.isStressed(container)).isTrue();
      assertThat(chaos.isThrottled(container)).isTrue();

      // WHEN
      chaos.reset(container);

      // THEN — both gone
      assertThat(chaos.isStressed(container)).isFalse();
      assertThat(chaos.isThrottled(container)).isFalse();
    }

    @Test
    @DisplayName("cache stress + affinity pin: both active simultaneously")
    void cachePlusAffinity() {
      chaos.stressCache(container, 1);
      chaos.pinToCoreMask(container, 0x1L);

      assertThat(chaos.isStressed(container)).isTrue();
      assertThat(chaos.isAffinityPinned(container)).isTrue();
    }

    @Test
    @DisplayName("priority + stress: both active simultaneously")
    void priorityPlusStress() {
      chaos.stress(container, 1);
      chaos.degradePriority(container, 19);

      assertThat(chaos.isStressed(container)).isTrue();
      assertThat(chaos.getNiceValue(container)).isEqualTo(19);
    }

    @Test
    @DisplayName("all stressor types detected by isStressed")
    void allStressorsDetected() {
      // Sequential: start → verify → reset → wait → next
      verifyStressor(() -> chaos.stressCache(container, 1), "stressCache");
      verifyStressor(() -> chaos.stressCacheLine(container, 1), "stressCacheLine");
      verifyStressor(() -> chaos.stressContextSwitch(container, 1), "stressContextSwitch");
      verifyStressor(() -> chaos.stressThreadSwitch(container, 1), "stressThreadSwitch");
      verifyStressor(() -> chaos.stressBranchPredictor(container, 1), "stressBranchPredictor");
      verifyStressor(() -> chaos.stressTimerInterrupts(container, 1), "stressTimerInterrupts");
      verifyStressor(() -> chaos.stressMatrix(container, 1), "stressMatrix");
    }

    private void verifyStressor(final Runnable inject, final String label) {
      inject.run();
      assertThat(chaos.isStressed(container)).as(label).isTrue();
      chaos.reset(container);
      await().atMost(5, TimeUnit.SECONDS).until(() -> !chaos.isStressed(container));
    }
  }

  // ==================== computeFullMask Unit Tests ====================

  @Nested
  @DisplayName("computeFullMask")
  class ComputeFullMask {

    @Test
    @DisplayName("1 core → 0x1")
    void oneCore() {
      assertThat(CpuObservability.computeFullMask(1)).isEqualTo(0x1L);
    }

    @Test
    @DisplayName("4 cores → 0xf")
    void fourCores() {
      assertThat(CpuObservability.computeFullMask(4)).isEqualTo(0xfL);
    }

    @Test
    @DisplayName("12 cores → 0xfff")
    void twelveCores() {
      assertThat(CpuObservability.computeFullMask(12)).isEqualTo(0xfffL);
    }

    @Test
    @DisplayName("63 cores → all bits set except MSB")
    void sixtyThreeCores() {
      assertThat(CpuObservability.computeFullMask(63)).isEqualTo((1L << 63) - 1);
    }

    @Test
    @DisplayName("64 cores → -1L (overflow guard: all bits set)")
    void sixtyFourCores() {
      // 1L << 64 would overflow to 0 in Java; guard returns -1L (all bits set)
      assertThat(CpuObservability.computeFullMask(64)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("128 cores → -1L (overflow guard)")
    void oneHundredTwentyEightCores() {
      assertThat(CpuObservability.computeFullMask(128)).isEqualTo(-1L);
    }
  }

  // ==================== parseCpuStat / computeUsage Unit Tests ====================

  @Nested
  @DisplayName("parseCpuStat")
  class ParseCpuStat {

    @Test
    @DisplayName("parses standard 8-field /proc/stat line")
    void parsesStandardLine() {
      final long[] result = CpuObservability.parseCpuStat("cpu  3357 0 4313 1362393 10 0 90 0");

      assertThat(result).hasSize(8);
      assertThat(result[0]).isEqualTo(3357L);
      assertThat(result[3]).isEqualTo(1362393L);
    }

    @Test
    @DisplayName("parses 10-field /proc/stat line (with guest fields)")
    void parses10FieldLine() {
      final long[] result = CpuObservability.parseCpuStat("cpu  100 200 300 400 50 10 20 5 0 0");

      assertThat(result).hasSize(10);
      assertThat(result[3]).isEqualTo(400L);
      assertThat(result[4]).isEqualTo(50L);
    }
  }

  @Nested
  @DisplayName("computeUsage")
  class ComputeUsage {

    @Test
    @DisplayName("returns 0 on zero delta")
    void zeroOnZeroDelta() {
      final long[] s = {100L, 0L, 50L, 500L, 10L, 0L, 5L, 0L};
      assertThat(CpuObservability.computeUsage(s, s)).isZero();
    }

    @Test
    @DisplayName("returns 0 on negative delta (counter wrap)")
    void zeroOnNegativeDelta() {
      final long[] first  = {200L, 0L, 100L, 1000L, 20L, 0L, 10L, 0L};
      final long[] second = {100L, 0L,  50L,  500L, 10L, 0L,  5L, 0L};
      assertThat(CpuObservability.computeUsage(first, second)).isZero();
    }

    @Test
    @DisplayName("returns 100 when fully busy")
    void hundredWhenFullyBusy() {
      final long[] first  = {0L, 0L, 0L, 500L, 0L, 0L, 0L, 0L};
      final long[] second = {100L, 0L, 0L, 500L, 0L, 0L, 0L, 0L};
      assertThat(CpuObservability.computeUsage(first, second)).isEqualTo(100);
    }

    @Test
    @DisplayName("returns 0 when fully idle")
    void zeroWhenFullyIdle() {
      final long[] first  = {0L, 0L, 0L, 500L, 0L, 0L, 0L, 0L};
      final long[] second = {0L, 0L, 0L, 600L, 0L, 0L, 0L, 0L};
      assertThat(CpuObservability.computeUsage(first, second)).isZero();
    }

    @Test
    @DisplayName("returns 50 for half-busy")
    void fiftyForHalfBusy() {
      final long[] first  = {0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
      final long[] second = {50L, 0L, 0L, 50L, 0L, 0L, 0L, 0L};
      assertThat(CpuObservability.computeUsage(first, second)).isEqualTo(50);
    }

    @Test
    @DisplayName("includes iowait in idle delta")
    void includesIowaitInIdle() {
      final long[] first  = {0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L};
      final long[] second = {10L, 0L, 0L, 40L, 50L, 0L, 0L, 0L};
      assertThat(CpuObservability.computeUsage(first, second)).isEqualTo(10);
    }
  }
}
