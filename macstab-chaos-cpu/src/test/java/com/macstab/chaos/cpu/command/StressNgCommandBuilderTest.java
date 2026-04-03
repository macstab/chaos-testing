/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.cpu.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.macstab.chaos.core.command.cpu.CpuCommandBuilder;
import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Unit tests for {@link StressNgCommandBuilder}.
 *
 * <p>All tests are pure unit tests — no Docker, no I/O, no side effects. Every method is a pure
 * string function; tests verify the structural correctness of the produced shell commands.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("StressNgCommandBuilder")
class StressNgCommandBuilderTest {

  private StressNgCommandBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new StressNgCommandBuilder();
  }

  // ==================== Process Lifecycle ====================

  @Nested
  @DisplayName("buildFindLowestPidByCommCommand")
  class FindLowestPidByCommCommand {

    @Test
    @DisplayName("scans /proc/comm for exact name match")
    void scansSlashProcComm() {
      // WHEN
      final String command = builder.buildFindLowestPidByCommCommand("stress-ng");

      // THEN
      assertThat(command).contains("/proc/[0-9]*/comm").contains("stress-ng");
    }

    @Test
    @DisplayName("outputs lowest numeric PID")
    void outputsLowestPid() {
      // WHEN
      final String command = builder.buildFindLowestPidByCommCommand("stress-ng");

      // THEN
      assertThat(command)
          .contains("[0-9]*$") // grep -o extracts numeric PID
          .contains("break"); // stops at first (lowest) match
    }

    @Test
    @DisplayName("uses exact match via string equality")
    void usesExactMatch() {
      // WHEN
      final String command = builder.buildFindLowestPidByCommCommand("cpulimit");

      // THEN
      assertThat(command).contains("= \"cpulimit\"");
    }

    @Test
    @DisplayName("rejects null comm name")
    void rejectsNullCommName() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildFindLowestPidByCommCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("exactCommName must not be null");
    }
  }

  @Nested
  @DisplayName("buildIsRunningByCommExactCommand")
  class IsRunningByCommExactCommand {

    @Test
    @DisplayName("uses grep with anchored exact match")
    void usesAnchoredExactMatch() {
      // WHEN
      final String command = builder.buildIsRunningByCommExactCommand("cpulimit");

      // THEN
      assertThat(command).contains("'^cpulimit$'");
    }

    @Test
    @DisplayName("scans /proc/comm files")
    void scansSlashProcComm() {
      // WHEN
      final String command = builder.buildIsRunningByCommExactCommand("cpulimit");

      // THEN
      assertThat(command).contains("/proc/[0-9]*/comm");
    }

    @Test
    @DisplayName("suppresses errors from missing /proc entries")
    void suppressesErrors() {
      // WHEN
      final String command = builder.buildIsRunningByCommExactCommand("cpulimit");

      // THEN
      assertThat(command).contains("2>/dev/null");
    }

    @Test
    @DisplayName("exit code reflects presence — grep -q pipelines")
    void usesGrepQForExitCode() {
      // WHEN
      final String command = builder.buildIsRunningByCommExactCommand("cpulimit");

      // THEN
      assertThat(command).contains("grep -q .");
    }

    @Test
    @DisplayName("rejects null comm name")
    void rejectsNullCommName() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildIsRunningByCommExactCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("exactCommName must not be null");
    }
  }

  @Nested
  @DisplayName("buildIsRunningByCommPrefixCommand")
  class IsRunningByCommPrefixCommand {

    @Test
    @DisplayName("uses prefix match (no end anchor)")
    void usesPrefixMatch() {
      // WHEN
      final String command = builder.buildIsRunningByCommPrefixCommand("stress-ng");

      // THEN
      // prefix pattern: '^stress-ng' (no $ anchor)
      assertThat(command).contains("'^stress-ng'").doesNotContain("'^stress-ng$'");
    }

    @Test
    @DisplayName("covers stress-ng worker variants via prefix")
    void coverWorkerVariants() {
      // WHEN
      final String command = builder.buildIsRunningByCommPrefixCommand("stress-ng");

      // THEN — prefix matches stress-ng-cpu, stress-ng-cache, etc.
      assertThat(command).contains("'^stress-ng'");
    }

    @Test
    @DisplayName("rejects null comm prefix")
    void rejectsNullCommPrefix() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildIsRunningByCommPrefixCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("commPrefix must not be null");
    }
  }

  @Nested
  @DisplayName("buildKillAllByCommSigKillCommand")
  class KillAllByCommSigKillCommand {

    @Test
    @DisplayName("sends SIGKILL (-9) to all matching processes")
    void sendsSigKill() {
      // WHEN
      final String command = builder.buildKillAllByCommSigKillCommand("cpulimit");

      // THEN
      assertThat(command).contains("kill -9");
    }

    @Test
    @DisplayName("iterates all /proc/comm entries")
    void iteratesAllProcComm() {
      // WHEN
      final String command = builder.buildKillAllByCommSigKillCommand("cpulimit");

      // THEN
      assertThat(command).contains("/proc/[0-9]*/comm");
    }

    @Test
    @DisplayName("is idempotent — always exits 0")
    void isIdempotent() {
      // WHEN
      final String command = builder.buildKillAllByCommSigKillCommand("cpulimit");

      // THEN
      assertThat(command).endsWith("true");
    }

    @Test
    @DisplayName("rejects null comm name")
    void rejectsNullCommName() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildKillAllByCommSigKillCommand(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("buildKillParentByCommSigTermCommand")
  class KillParentByCommSigTermCommand {

    @Test
    @DisplayName("sends SIGTERM (-15) — not SIGKILL")
    void sendsSigTerm() {
      // WHEN
      final String command = builder.buildKillParentByCommSigTermCommand("stress-ng");

      // THEN
      assertThat(command).contains("kill -15").doesNotContain("kill -9");
    }

    @Test
    @DisplayName("breaks after first (parent) PID only")
    void breaksAfterFirstPid() {
      // WHEN
      final String command = builder.buildKillParentByCommSigTermCommand("stress-ng");

      // THEN
      assertThat(command).contains("break");
    }

    @Test
    @DisplayName("is idempotent — always exits 0")
    void isIdempotent() {
      // WHEN
      final String command = builder.buildKillParentByCommSigTermCommand("stress-ng");

      // THEN
      assertThat(command).endsWith("true");
    }

    @Test
    @DisplayName("rejects null comm name")
    void rejectsNullCommName() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildKillParentByCommSigTermCommand(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== stress-ng Stressor Commands ====================

  @Nested
  @DisplayName("buildStressCpuCommand")
  class StressCpuCommand {

    @Test
    @DisplayName("uses stress-ng --cpu stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressCpuCommand(2);

      // THEN
      assertThat(command).contains("stress-ng --cpu 2");
    }

    @Test
    @DisplayName("runs indefinitely with --timeout 0")
    void runsIndefinitely() {
      // WHEN
      final String command = builder.buildStressCpuCommand(1);

      // THEN
      assertThat(command).contains("--timeout 0");
    }

    @Test
    @DisplayName("runs in background")
    void runsInBackground() {
      // WHEN
      final String command = builder.buildStressCpuCommand(1);

      // THEN
      assertThat(command).endsWith("&");
    }

    @Test
    @DisplayName("redirects all output to /dev/null")
    void suppressesOutput() {
      // WHEN
      final String command = builder.buildStressCpuCommand(1);

      // THEN
      assertThat(command).contains(">/dev/null 2>&1");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16})
    @DisplayName("embeds worker count correctly")
    void embedsWorkerCount(final int workers) {
      // WHEN
      final String command = builder.buildStressCpuCommand(workers);

      // THEN
      assertThat(command).contains("--cpu " + workers);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("rejects invalid worker counts")
    void rejectsInvalidWorkers(final int workers) {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildStressCpuCommand(workers))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("workers must be >= 1");
    }
  }

  @Nested
  @DisplayName("buildStressCpuWithTimeoutCommand")
  class StressCpuWithTimeoutCommand {

    @Test
    @DisplayName("appends seconds suffix to timeout value")
    void appendsSecondsSuffix() {
      // WHEN
      final String command = builder.buildStressCpuWithTimeoutCommand(2, 30);

      // THEN
      assertThat(command).contains("--timeout 30s");
    }

    @Test
    @DisplayName("embeds both workers and timeout")
    void embedsBothParams() {
      // WHEN
      final String command = builder.buildStressCpuWithTimeoutCommand(4, 10);

      // THEN
      assertThat(command).contains("--cpu 4").contains("--timeout 10s");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -60})
    @DisplayName("rejects zero or negative seconds")
    void rejectsInvalidSeconds(final long seconds) {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildStressCpuWithTimeoutCommand(1, seconds))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("seconds must be >= 1");
    }
  }

  @Nested
  @DisplayName("buildStressCacheCommand")
  class StressCacheCommand {

    @Test
    @DisplayName("uses stress-ng --cache stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressCacheCommand(2);

      // THEN
      assertThat(command).contains("stress-ng --cache 2");
    }

    @Test
    @DisplayName("runs indefinitely with --timeout 0")
    void runsIndefinitely() {
      assertThat(builder.buildStressCacheCommand(1)).contains("--timeout 0");
    }

    @Test
    @DisplayName("rejects invalid workers")
    void rejectsInvalidWorkers() {
      assertThatThrownBy(() -> builder.buildStressCacheCommand(0))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("workers must be >= 1");
    }
  }

  @Nested
  @DisplayName("buildStressCacheWithTimeoutCommand")
  class StressCacheWithTimeoutCommand {

    @Test
    @DisplayName("uses --cache with timeout suffix")
    void usesCacheWithTimeout() {
      // WHEN
      final String command = builder.buildStressCacheWithTimeoutCommand(2, 15);

      // THEN
      assertThat(command).contains("--cache 2").contains("--timeout 15s");
    }

    @Test
    @DisplayName("rejects invalid seconds")
    void rejectsInvalidSeconds() {
      assertThatThrownBy(() -> builder.buildStressCacheWithTimeoutCommand(1, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("seconds must be >= 1");
    }
  }

  @Nested
  @DisplayName("buildStressCacheLineCommand")
  class StressCacheLineCommand {

    @Test
    @DisplayName("uses stress-ng --cacheline stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressCacheLineCommand(4);

      // THEN
      assertThat(command).contains("stress-ng --cacheline 4");
    }

    @Test
    @DisplayName("runs indefinitely")
    void runsIndefinitely() {
      assertThat(builder.buildStressCacheLineCommand(1)).contains("--timeout 0");
    }
  }

  @Nested
  @DisplayName("buildStressContextSwitchCommand")
  class StressContextSwitchCommand {

    @Test
    @DisplayName("uses stress-ng --context stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressContextSwitchCommand(2);

      // THEN
      assertThat(command).contains("stress-ng --context 2");
    }

    @Test
    @DisplayName("runs indefinitely")
    void runsIndefinitely() {
      assertThat(builder.buildStressContextSwitchCommand(1)).contains("--timeout 0");
    }
  }

  @Nested
  @DisplayName("buildStressThreadSwitchCommand")
  class StressThreadSwitchCommand {

    @Test
    @DisplayName("uses stress-ng --switch stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressThreadSwitchCommand(4);

      // THEN
      assertThat(command).contains("stress-ng --switch 4");
    }

    @Test
    @DisplayName("runs indefinitely")
    void runsIndefinitely() {
      assertThat(builder.buildStressThreadSwitchCommand(1)).contains("--timeout 0");
    }
  }

  @Nested
  @DisplayName("buildStressBranchPredictorCommand")
  class StressBranchPredictorCommand {

    @Test
    @DisplayName("uses stress-ng --branch stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressBranchPredictorCommand(2);

      // THEN
      assertThat(command).contains("stress-ng --branch 2");
    }

    @Test
    @DisplayName("runs indefinitely")
    void runsIndefinitely() {
      assertThat(builder.buildStressBranchPredictorCommand(1)).contains("--timeout 0");
    }
  }

  @Nested
  @DisplayName("buildStressTimerInterruptsCommand")
  class StressTimerInterruptsCommand {

    @Test
    @DisplayName("uses stress-ng --hrtimers stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressTimerInterruptsCommand(2);

      // THEN
      assertThat(command).contains("stress-ng --hrtimers 2");
    }

    @Test
    @DisplayName("runs indefinitely")
    void runsIndefinitely() {
      assertThat(builder.buildStressTimerInterruptsCommand(1)).contains("--timeout 0");
    }
  }

  @Nested
  @DisplayName("buildStressMatrixCommand")
  class StressMatrixCommand {

    @Test
    @DisplayName("uses stress-ng --matrix stressor")
    void usesCorrectStressor() {
      // WHEN
      final String command = builder.buildStressMatrixCommand(2);

      // THEN
      assertThat(command).contains("stress-ng --matrix 2");
    }

    @Test
    @DisplayName("runs indefinitely")
    void runsIndefinitely() {
      assertThat(builder.buildStressMatrixCommand(1)).contains("--timeout 0");
    }
  }

  @Nested
  @DisplayName("buildStressMatrixWithTimeoutCommand")
  class StressMatrixWithTimeoutCommand {

    @Test
    @DisplayName("uses --matrix with timeout suffix")
    void usesMatrixWithTimeout() {
      // WHEN
      final String command = builder.buildStressMatrixWithTimeoutCommand(2, 20);

      // THEN
      assertThat(command).contains("--matrix 2").contains("--timeout 20s");
    }

    @Test
    @DisplayName("rejects invalid seconds")
    void rejectsInvalidSeconds() {
      assertThatThrownBy(() -> builder.buildStressMatrixWithTimeoutCommand(1, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("seconds must be >= 1");
    }
  }

  // ==================== cpulimit Commands ====================

  @Nested
  @DisplayName("buildThrottleCommand")
  class ThrottleCommand {

    @Test
    @DisplayName("builds cpulimit with correct percentage and pid")
    void buildsCorrectCommand() {
      // WHEN
      final String command = builder.buildThrottleCommand(1, 50);

      // THEN
      assertThat(command).contains("cpulimit").contains("-l 50").contains("-p 1");
    }

    @Test
    @DisplayName("runs in background")
    void runsInBackground() {
      assertThat(builder.buildThrottleCommand(1, 50)).endsWith("&");
    }

    @Test
    @DisplayName("suppresses output")
    void suppressesOutput() {
      assertThat(builder.buildThrottleCommand(1, 50)).contains(">/dev/null 2>&1");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 25, 50, 75, 100})
    @DisplayName("accepts valid percentage range [1, 100]")
    void acceptsValidPercentage(final int pct) {
      // WHEN
      final String command = builder.buildThrottleCommand(1, pct);

      // THEN
      assertThat(command).contains("-l " + pct);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 200})
    @DisplayName("rejects invalid percentage")
    void rejectsInvalidPercentage(final int pct) {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildThrottleCommand(1, pct))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("percentage must be in [1, 100]");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("rejects invalid pid")
    void rejectsInvalidPid(final int pid) {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildThrottleCommand(pid, 50))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pid must be >= 1");
    }
  }

  @Nested
  @DisplayName("buildThrottleWithDurationCommand")
  class ThrottleWithDurationCommand {

    @Test
    @DisplayName("wraps cpulimit in background subshell with sleep-kill timer")
    void wrapsInSubshell() {
      // WHEN
      final String command = builder.buildThrottleWithDurationCommand(1, 50, 5);

      // THEN
      assertThat(command)
          .startsWith("(")
          .contains("CPID=$!")
          .contains("sleep 5")
          .contains("kill $CPID")
          .endsWith(") &");
    }

    @Test
    @DisplayName("embeds correct percentage and pid")
    void embedsCorrectParams() {
      // WHEN
      final String command = builder.buildThrottleWithDurationCommand(42, 75, 10);

      // THEN
      assertThat(command).contains("-l 75").contains("-p 42").contains("sleep 10");
    }

    @Test
    @DisplayName("container-internal timer — no Java side effects")
    void isContainerInternal() {
      // WHEN
      final String command = builder.buildThrottleWithDurationCommand(1, 50, 30);

      // THEN — the entire lifecycle is shell-managed, no Java scheduler involved
      assertThat(command)
          .contains("CPID=$!") // capture cpulimit PID inside shell
          .contains("sleep") // shell sleep, not Java Thread.sleep
          .contains("kill $CPID"); // shell kill
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -30})
    @DisplayName("rejects zero or negative seconds")
    void rejectsInvalidSeconds(final long secs) {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildThrottleWithDurationCommand(1, 50, secs))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("seconds must be >= 1");
    }
  }

  // ==================== taskset Commands ====================

  @Nested
  @DisplayName("buildPinToMaskCommand")
  class PinToMaskCommand {

    @Test
    @DisplayName("uses taskset -p with hex mask and pid")
    void usesTasksetWithHexMask() {
      // WHEN
      final String command = builder.buildPinToMaskCommand(1, 0x1L);

      // THEN
      assertThat(command).contains("taskset").contains("-p").contains("0x1").contains(" 1");
    }

    @Test
    @DisplayName("formats mask as lowercase hex")
    void formatsMaskAsLowercaseHex() {
      // WHEN
      final String command = builder.buildPinToMaskCommand(1, 0xfffL);

      // THEN
      assertThat(command).contains("0xfff");
    }

    @Test
    @DisplayName("applies to running process (no exec flags)")
    void appliesToRunningProcess() {
      // WHEN
      final String command = builder.buildPinToMaskCommand(1, 0x3L);

      // THEN — taskset -p modifies existing PID, no exec-mode flags
      assertThat(command).startsWith("taskset -p").doesNotContain("--cpu-list");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    @DisplayName("rejects zero or negative affinity mask")
    void rejectsInvalidMask(final long mask) {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildPinToMaskCommand(1, mask))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("affinityMask must be > 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("rejects invalid pid")
    void rejectsInvalidPid(final int pid) {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildPinToMaskCommand(pid, 0x1L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pid must be >= 1");
    }
  }

  @Nested
  @DisplayName("buildGetAffinityMaskCommand")
  class GetAffinityMaskCommand {

    @Test
    @DisplayName("uses taskset -p <pid> (read mode)")
    void usesTasksetReadMode() {
      // WHEN
      final String command = builder.buildGetAffinityMaskCommand(1);

      // THEN
      assertThat(command).isEqualTo("taskset -p 1");
    }

    @Test
    @DisplayName("embeds correct pid")
    void embedsCorrectPid() {
      // WHEN
      final String command = builder.buildGetAffinityMaskCommand(42);

      // THEN
      assertThat(command).contains("42");
    }

    @Test
    @DisplayName("rejects invalid pid")
    void rejectsInvalidPid() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildGetAffinityMaskCommand(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pid must be >= 1");
    }
  }

  // ==================== System Info Commands ====================

  @Nested
  @DisplayName("buildGetCoreCountCommand")
  class GetCoreCountCommand {

    @Test
    @DisplayName("uses nproc")
    void usesNproc() {
      // WHEN / THEN
      assertThat(builder.buildGetCoreCountCommand()).isEqualTo("nproc");
    }
  }

  @Nested
  @DisplayName("buildGetCoreCountFallbackCommand")
  class GetCoreCountFallbackCommand {

    @Test
    @DisplayName("reads from /proc/cpuinfo")
    void readsFromProcCpuinfo() {
      // WHEN
      final String command = builder.buildGetCoreCountFallbackCommand();

      // THEN
      assertThat(command).contains("/proc/cpuinfo");
    }

    @Test
    @DisplayName("counts processor lines")
    void countsProcessorLines() {
      // WHEN
      final String command = builder.buildGetCoreCountFallbackCommand();

      // THEN
      assertThat(command).contains("processor");
    }
  }

  @Nested
  @DisplayName("buildReadCpuStatCommand")
  class ReadCpuStatCommand {

    @Test
    @DisplayName("reads /proc/stat")
    void readsProcStat() {
      // WHEN / THEN
      assertThat(builder.buildReadCpuStatCommand()).contains("/proc/stat");
    }
  }

  // ==================== renice Commands ====================

  @Nested
  @DisplayName("buildSetNiceValueCommand")
  class SetNiceValueCommand {

    @Test
    @DisplayName("uses renice with correct value and pid")
    void usesReniceWithCorrectParams() {
      // WHEN
      final String command = builder.buildSetNiceValueCommand(1, 19);

      // THEN
      assertThat(command).contains("renice").contains("19").contains("-p 1");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 10, 19})
    @DisplayName("embeds nice value correctly for valid unprivileged range")
    void embedsNiceValueCorrectly(final int niceValue) {
      // WHEN
      final String command = builder.buildSetNiceValueCommand(1, niceValue);

      // THEN
      assertThat(command).contains(String.valueOf(niceValue));
    }

    @Test
    @DisplayName("rejects invalid pid")
    void rejectsInvalidPid() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildSetNiceValueCommand(0, 10))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pid must be >= 1");
    }
  }

  @Nested
  @DisplayName("buildGetNiceValueCommand")
  class GetNiceValueCommand {

    @Test
    @DisplayName("reads field 19 from /proc/<pid>/stat via awk")
    void readsField19ViaAwk() {
      // WHEN
      final String command = builder.buildGetNiceValueCommand(1);

      // THEN
      assertThat(command).contains("awk").contains("$19").contains("/proc/1/stat");
    }

    @Test
    @DisplayName("embeds correct pid in path")
    void embedsCorrectPid() {
      // WHEN
      final String command = builder.buildGetNiceValueCommand(42);

      // THEN
      assertThat(command).contains("/proc/42/stat");
    }

    @Test
    @DisplayName("rejects invalid pid")
    void rejectsInvalidPid() {
      // WHEN / THEN
      assertThatThrownBy(() -> builder.buildGetNiceValueCommand(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("pid must be >= 1");
    }
  }

  // ==================== Singleton Contract ====================

  @Nested
  @DisplayName("INSTANCE singleton")
  class SingletonContract {

    @Test
    @DisplayName("INSTANCE is not null")
    void instanceIsNotNull() {
      assertThat(StressNgCommandBuilder.INSTANCE).isNotNull();
    }

    @Test
    @DisplayName("INSTANCE implements CpuCommandBuilder")
    void instanceImplementsCpuCommandBuilder() {
      assertThat(StressNgCommandBuilder.INSTANCE).isInstanceOf(CpuCommandBuilder.class);
    }

    @Test
    @DisplayName("new instance equals INSTANCE in behavior (stateless)")
    void newInstanceBehavesLikeSingleton() {
      // GIVEN
      final StressNgCommandBuilder fresh = new StressNgCommandBuilder();

      // WHEN
      final String fromInstance = StressNgCommandBuilder.INSTANCE.buildStressCpuCommand(2);
      final String fromFresh = fresh.buildStressCpuCommand(2);

      // THEN — pure function, same output
      assertThat(fromInstance).isEqualTo(fromFresh);
    }
  }
}
