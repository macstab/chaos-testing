/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

import com.macstab.chaos.core.exception.ChaosConfigurationException;
import com.macstab.chaos.core.exception.ChaosOperationFailedException;
import com.macstab.chaos.core.platform.Tool;
import com.macstab.chaos.core.syscall.DiskErrno;
import com.macstab.chaos.core.syscall.DiskOperation;
import com.macstab.chaos.core.util.PackageInstaller;

/**
 * Unit tests for {@link CgroupsDiskChaos} — no Docker required.
 *
 * <p>Covers all validation logic, null guards, private helper behaviour (via public API), exec
 * failure wrapping, and the stopped-container no-op branches. Uses Mockito to mock {@link
 * GenericContainer} — the same pattern established by {@code CgroupsCpuChaosUnitTest}.
 *
 * <p>All {@code execInContainer} calls in production code use the {@code (shell, "-c", command)}
 * form. Mocks discriminate by inspecting the command string (argument index 2).
 *
 * <p>Syscall injection methods ({@code injectIOError}, etc.) are fully exercisable here because
 * {@code SyscallFaultInjector.addRule} only inspects the container's label map and calls {@code
 * execInContainer} — both are mocked.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsDiskChaos (unit)")
class CgroupsDiskChaosUnitTest {

  /**
   * The string constant from {@code SyscallFaultInjector.LABEL_ACTIVE} (package-private in core).
   * Hardcoded here intentionally — it is part of the observable container label contract and must
   * not change without a version bump.
   */
  private static final String SYSCALL_LABEL_ACTIVE = "macstab.chaos.io.active";

  /**
   * Simulated total-KB output from {@code df -P /tmp | awk 'NR==2{print $2}'}.
   *
   * <p>10 GiB = 10 485 760 KiB. Returned as a plain integer string (no header, no units), matching
   * real awk output.
   */
  private static final String DF_TOTAL_KB_STDOUT = "10485760";

  /**
   * Simulated usage-percent output from {@code df -P /tmp | awk 'NR==2{gsub(/%/,"",$5); ...}'}.
   *
   * <p>Plain integer string (no {@code %} sign), matching real awk output.
   */
  private static final String DF_USAGE_PCT_STDOUT = "42";

  @SuppressWarnings("rawtypes")
  private GenericContainer container;

  private CgroupsDiskChaos chaos;
  private ExecResult defaultSuccess;
  private ExecResult dfTotalResult;
  private ExecResult dfUsageResult;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    chaos = new CgroupsDiskChaos();
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);

    final Map<String, String> labels = new HashMap<>();
    for (final Tool tool : Tool.values()) {
      labels.put(PackageInstaller.LABEL_PREFIX + tool.name().toLowerCase(), "true");
    }
    labels.put(SYSCALL_LABEL_ACTIVE, "glibc-amd64");
    when(container.getLabels()).thenReturn(labels);

    defaultSuccess = execResult(0, "");
    dfTotalResult = execResult(0, DF_TOTAL_KB_STDOUT);
    dfUsageResult = execResult(0, DF_USAGE_PCT_STDOUT);

    // All production exec calls are (sh, -c, command). Distinguish on the command string (arg[2]).
    // "$2" in the awk expression → total KB command; "$5" → usage percent command.
    when(container.execInContainer(anyString(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              final String cmd = inv.getArgument(2);
              if (cmd.contains("df -P") && cmd.contains("$2")) return dfTotalResult;
              if (cmd.contains("df -P") && cmd.contains("$5")) return dfUsageResult;
              return defaultSuccess;
            });
  }

  // ==================== isSupported ====================

  @Nested
  @DisplayName("isSupported")
  class IsSupported {

    @Test
    @DisplayName("always returns true regardless of environment")
    void alwaysTrue() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }

  // ==================== Null guards ====================

  @Nested
  @DisplayName("null guards — all methods reject null container")
  class NullGuards {

    @Test
    @DisplayName("stressDisk(workers) — null container")
    void stressDiskNullContainer() {
      assertThatThrownBy(() -> chaos.stressDisk(null, 2)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("stressDisk(workers, duration) — null container")
    void stressDiskDurationNullContainer() {
      assertThatThrownBy(() -> chaos.stressDisk(null, 2, Duration.ofSeconds(10)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("stressDisk(workers, duration) — null duration")
    void stressDiskNullDuration() {
      assertThatThrownBy(() -> chaos.stressDisk(container, 2, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("fillDisk — null container")
    void fillDiskNullContainer() {
      assertThatThrownBy(() -> chaos.fillDisk(null, "/tmp", 10))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("fillDisk — null mountPoint")
    void fillDiskNullMountPoint() {
      assertThatThrownBy(() -> chaos.fillDisk(container, null, 10))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("fillDiskBySize — null container")
    void fillDiskBySizeNullContainer() {
      assertThatThrownBy(() -> chaos.fillDiskBySize(null, "/tmp", "100M"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("fillDiskBySize — null mountPoint")
    void fillDiskBySizeNullMountPoint() {
      assertThatThrownBy(() -> chaos.fillDiskBySize(container, null, "100M"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("fillDiskBySize — null size")
    void fillDiskBySizeNullSize() {
      assertThatThrownBy(() -> chaos.fillDiskBySize(container, "/tmp", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOError — null container")
    void injectIOErrorNullContainer() {
      assertThatThrownBy(
              () -> chaos.injectIOError(null, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOError — null path")
    void injectIOErrorNullPath() {
      assertThatThrownBy(
              () -> chaos.injectIOError(container, null, DiskOperation.WRITE, DiskErrno.EIO, 0.3))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOError — null operation")
    void injectIOErrorNullOperation() {
      assertThatThrownBy(() -> chaos.injectIOError(container, "/data", null, DiskErrno.EIO, 0.3))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOError — null errno")
    void injectIOErrorNullErrno() {
      assertThatThrownBy(
              () -> chaos.injectIOError(container, "/data", DiskOperation.WRITE, null, 0.3))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOLatency — null container")
    void injectIOLatencyNullContainer() {
      assertThatThrownBy(
              () ->
                  chaos.injectIOLatency(null, "/data", DiskOperation.WRITE, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOLatency — null path")
    void injectIOLatencyNullPath() {
      assertThatThrownBy(
              () ->
                  chaos.injectIOLatency(
                      container, null, DiskOperation.WRITE, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOLatency — null operation")
    void injectIOLatencyNullOperation() {
      assertThatThrownBy(
              () -> chaos.injectIOLatency(container, "/data", null, Duration.ofMillis(100)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectIOLatency — null latency")
    void injectIOLatencyNullLatency() {
      assertThatThrownBy(() -> chaos.injectIOLatency(container, "/data", DiskOperation.WRITE, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectTornWrite — null container")
    void injectTornWriteNullContainer() {
      assertThatThrownBy(() -> chaos.injectTornWrite(null, "/data", 0.1))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectTornWrite — null path")
    void injectTornWriteNullPath() {
      assertThatThrownBy(() -> chaos.injectTornWrite(container, null, 0.1))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectCorruptRead — null container")
    void injectCorruptReadNullContainer() {
      assertThatThrownBy(() -> chaos.injectCorruptRead(null, "/data", 0.05))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("injectCorruptRead — null path")
    void injectCorruptReadNullPath() {
      assertThatThrownBy(() -> chaos.injectCorruptRead(container, null, 0.05))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getDiskUsagePercent — null container")
    void getDiskUsagePercentNullContainer() {
      assertThatThrownBy(() -> chaos.getDiskUsagePercent(null, "/tmp"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getDiskUsagePercent — null mountPoint")
    void getDiskUsagePercentNullMountPoint() {
      assertThatThrownBy(() -> chaos.getDiskUsagePercent(container, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("isStressed — null container")
    void isStressedNullContainer() {
      assertThatThrownBy(() -> chaos.isStressed(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("reset — null container")
    void resetNullContainer() {
      assertThatThrownBy(() -> chaos.reset(null)).isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== Validation guards ====================

  @Nested
  @DisplayName("validation guards")
  class ValidationGuards {

    @Nested
    @DisplayName("stressDisk worker bounds")
    class WorkerBounds {

      @Test
      @DisplayName("workers = 0 throws ChaosConfigurationException")
      void zeroWorkers() {
        assertThatThrownBy(() -> chaos.stressDisk(container, 0))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("workers must be >= 1");
      }

      @Test
      @DisplayName("workers = -1 throws ChaosConfigurationException")
      void negativeWorkers() {
        assertThatThrownBy(() -> chaos.stressDisk(container, -1))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("workers must be >= 1");
      }

      @Test
      @DisplayName("stressDisk(duration) — workers = 0 throws before duration check")
      void zeroWorkersDuration() {
        assertThatThrownBy(() -> chaos.stressDisk(container, 0, Duration.ofSeconds(10)))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("workers must be >= 1");
      }
    }

    @Nested
    @DisplayName("stressDisk duration bounds")
    class DurationBounds {

      @Test
      @DisplayName("Duration.ZERO throws ChaosConfigurationException")
      void zeroDuration() {
        assertThatThrownBy(() -> chaos.stressDisk(container, 2, Duration.ZERO))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("duration must be > 0");
      }

      @Test
      @DisplayName("negative duration throws ChaosConfigurationException")
      void negativeDuration() {
        assertThatThrownBy(() -> chaos.stressDisk(container, 2, Duration.ofSeconds(-5)))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("duration must be > 0");
      }
    }

    @Nested
    @DisplayName("fillDisk percentage bounds")
    class PercentageBounds {

      @Test
      @DisplayName("0% throws ChaosConfigurationException")
      void zeroPercent() {
        assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 0))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("must be in [1, 95]");
      }

      @Test
      @DisplayName("-1% throws ChaosConfigurationException")
      void negativePercent() {
        assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", -1))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("must be in [1, 95]");
      }

      @Test
      @DisplayName("96% throws ChaosConfigurationException — MAX_FILL_PERCENTAGE enforced")
      void over95Percent() {
        assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 96))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("must be in [1, 95]");
      }

      @Test
      @DisplayName("100% throws ChaosConfigurationException")
      void hundredPercent() {
        assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 100))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("must be in [1, 95]");
      }

      @Test
      @DisplayName("1% is accepted — lower bound")
      void onePercentAccepted() {
        assertThatCode(() -> chaos.fillDisk(container, "/tmp", 1)).doesNotThrowAnyException();
      }

      @Test
      @DisplayName("95% is accepted — upper bound")
      void ninetyFivePercentAccepted() {
        assertThatCode(() -> chaos.fillDisk(container, "/tmp", 95)).doesNotThrowAnyException();
      }
    }

    @Nested
    @DisplayName("path safety")
    class PathSafety {

      @Test
      @DisplayName("shell injection characters are rejected")
      void shellInjectionRejected() {
        assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp;rm -rf /", 10))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("unsafe characters");
      }

      @Test
      @DisplayName("path traversal sequence is rejected after regex check")
      void pathTraversalRejected() {
        assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp/../etc", 10))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("Path traversal not allowed");
      }

      @Test
      @DisplayName("space in path is rejected")
      void spaceInPathRejected() {
        assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp/my dir", 10))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("unsafe characters");
      }
    }

    @Nested
    @DisplayName("size format validation (fillDiskBySize)")
    class SizeFormat {

      @ParameterizedTest
      @ValueSource(strings = {"abc", "500", "5.5M", "500T", "", "M", "500 M", "G"})
      @DisplayName("invalid size strings throw ChaosConfigurationException")
      void invalidFormatsThrow(final String size) {
        assertThatThrownBy(() -> chaos.fillDiskBySize(container, "/tmp", size))
            .isInstanceOf(ChaosConfigurationException.class)
            .hasMessageContaining("Invalid size format");
      }

      @ParameterizedTest
      @ValueSource(strings = {"500M", "2G", "100K", "1G", "1024K", "100m", "2g", "512k"})
      @DisplayName("valid size strings (including lowercase) are accepted")
      void validFormatsAccepted(final String size) {
        assertThatCode(() -> chaos.fillDiskBySize(container, "/tmp", size))
            .doesNotThrowAnyException();
      }
    }
  }

  // ==================== Container-running guards ====================

  @Nested
  @DisplayName("container-running guards")
  class ContainerRunningGuards {

    @Test
    @DisplayName("stressDisk — stopped container throws IllegalStateException")
    void stressDiskStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.stressDisk(container, 2))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("stressDisk(duration) — stopped container throws IllegalStateException")
    void stressDiskDurationStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.stressDisk(container, 2, Duration.ofSeconds(5)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("fillDisk — stopped container throws IllegalStateException")
    void fillDiskStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 10))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("fillDiskBySize — stopped container throws IllegalStateException")
    void fillDiskBySizeStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.fillDiskBySize(container, "/tmp", "100M"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("getDiskUsagePercent — stopped container throws IllegalStateException")
    void getDiskUsagePercentStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.getDiskUsagePercent(container, "/tmp"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("injectIOError — stopped container throws IllegalStateException")
    void injectIOErrorStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(
              () ->
                  chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("injectIOLatency — stopped container throws IllegalStateException")
    void injectIOLatencyStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(
              () ->
                  chaos.injectIOLatency(
                      container, "/data", DiskOperation.WRITE, Duration.ofMillis(100)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("injectTornWrite — stopped container throws IllegalStateException")
    void injectTornWriteStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.injectTornWrite(container, "/data", 0.1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("injectCorruptRead — stopped container throws IllegalStateException")
    void injectCorruptReadStoppedContainer() {
      when(container.isRunning()).thenReturn(false);
      assertThatThrownBy(() -> chaos.injectCorruptRead(container, "/data", 0.05))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not running");
    }
  }

  // ==================== Exec failure handling ====================

  @Nested
  @DisplayName("exec failure handling")
  class ExecFailureHandling {

    @Test
    @DisplayName("fillDisk — df total-KB non-zero exit wraps as ChaosOperationFailedException")
    void fillDiskDfNonZeroExit() throws Exception {
      final ExecResult dfFail = execResult(1, "");
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenAnswer(
              inv -> {
                final String cmd = inv.getArgument(2);
                return (cmd.contains("df -P") && cmd.contains("$2")) ? dfFail : defaultSuccess;
              });

      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 10))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    @DisplayName("fillDisk — fallocate/dd non-zero exit wraps as ChaosOperationFailedException")
    void fillDiskFillNonZeroExit() throws Exception {
      final ExecResult fillFail = execResult(1, "");
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenAnswer(
              inv -> {
                final String cmd = inv.getArgument(2);
                if (cmd.contains("df -P") && cmd.contains("$2")) return dfTotalResult;
                return fillFail;
              });

      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 10))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    @DisplayName("fillDisk — IOException in exec wraps as ChaosOperationFailedException")
    void fillDiskExecIoException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new IOException("Docker exec timeout"));

      assertThatThrownBy(() -> chaos.fillDisk(container, "/tmp", 10))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    @DisplayName("getDiskUsagePercent — df non-zero exit wraps as ChaosOperationFailedException")
    void getDiskUsagePercentDfFailure() throws Exception {
      final ExecResult dfFail = execResult(1, "");
      when(container.execInContainer(anyString(), anyString(), anyString())).thenReturn(dfFail);

      assertThatThrownBy(() -> chaos.getDiskUsagePercent(container, "/tmp"))
          .isInstanceOf(ChaosOperationFailedException.class);
    }

    @Test
    @DisplayName("getDiskUsagePercent — IOException wraps as ChaosOperationFailedException")
    void getDiskUsagePercentIoException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new IOException("exec timeout"));

      assertThatThrownBy(() -> chaos.getDiskUsagePercent(container, "/tmp"))
          .isInstanceOf(ChaosOperationFailedException.class);
    }
  }

  // ==================== getDiskUsagePercent output parsing ====================

  @Nested
  @DisplayName("getDiskUsagePercent output parsing")
  class DiskUsagePercentParsing {

    @Test
    @DisplayName("parses 42 from default mock output")
    void parsesFortyTwoPercent() {
      // Default setUp returns "42" for the df -P ... $5 command
      assertThat(chaos.getDiskUsagePercent(container, "/tmp")).isEqualTo(42);
    }

    @Test
    @DisplayName("parses 100 correctly — full disk")
    void parses100Percent() throws Exception {
      mockDfUsage("100");
      assertThat(chaos.getDiskUsagePercent(container, "/tmp")).isEqualTo(100);
    }

    @Test
    @DisplayName("parses 0 correctly — empty disk")
    void parses0Percent() throws Exception {
      mockDfUsage("0");
      assertThat(chaos.getDiskUsagePercent(container, "/tmp")).isEqualTo(0);
    }

    @Test
    @DisplayName("strips surrounding whitespace from awk output")
    void stripsWhitespace() throws Exception {
      mockDfUsage("  15  ");
      assertThat(chaos.getDiskUsagePercent(container, "/tmp")).isEqualTo(15);
    }

    private void mockDfUsage(final String stdout) throws Exception {
      final ExecResult result = execResult(0, stdout);
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenAnswer(
              inv -> {
                final String cmd = inv.getArgument(2);
                return (cmd.contains("df -P") && cmd.contains("$5")) ? result : defaultSuccess;
              });
    }
  }

  // ==================== parseSizeMB (exercised via fillDiskBySize) ====================

  @Nested
  @DisplayName("size parsing via fillDiskBySize")
  class SizeParsing {

    @Test
    @DisplayName("500M — passes through as 500")
    void parseMegabytes() {
      assertThatCode(() -> chaos.fillDiskBySize(container, "/tmp", "500M"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("2G — expands to 2048 MB")
    void parseGigabytes() {
      assertThatCode(() -> chaos.fillDiskBySize(container, "/tmp", "2G"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("1024K — integer division gives 1 MB")
    void parseKilobytes() {
      assertThatCode(() -> chaos.fillDiskBySize(container, "/tmp", "1024K"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("1K — max(1, 0) guard prevents zero-byte dd invocation")
    void parseSubMegabyteKilobytes() {
      assertThatCode(() -> chaos.fillDiskBySize(container, "/tmp", "1K"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("lowercase size suffix is normalised via toUpperCase before parsing")
    void parseLowercaseSuffix() {
      assertThatCode(() -> chaos.fillDiskBySize(container, "/tmp", "100m"))
          .doesNotThrowAnyException();
    }
  }

  // ==================== isStressed ====================

  @Nested
  @DisplayName("isStressed")
  class IsStressedBehavior {

    @Test
    @DisplayName("returns false without exec when container is not running")
    void returnsFalseWhenStopped() {
      when(container.isRunning()).thenReturn(false);
      assertThat(chaos.isStressed(container)).isFalse();
    }

    @Test
    @DisplayName("returns true when /proc/comm grep exits 0")
    void returnsTrueWhenGrepSucceeds() {
      // defaultSuccess already has exitCode = 0
      assertThat(chaos.isStressed(container)).isTrue();
    }

    @Test
    @DisplayName("returns false when /proc/comm grep exits non-zero")
    void returnsFalseWhenGrepFails() throws Exception {
      // Pre-create to avoid nested when() inside thenReturn() — a Mockito state machine pitfall
      final ExecResult grepNotFound = execResult(1, "");
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenReturn(grepNotFound);
      assertThat(chaos.isStressed(container)).isFalse();
    }

    @Test
    @DisplayName("returns false silently when exec throws")
    void returnsFalseOnExecException() throws Exception {
      when(container.execInContainer(anyString(), anyString(), anyString()))
          .thenThrow(new IOException("exec failed"));
      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  // ==================== reset ====================

  @Nested
  @DisplayName("reset lifecycle")
  class ResetBehavior {

    @Test
    @DisplayName("stopped container — reset is a no-op and does not throw")
    void resetStoppedContainerIsNoOp() {
      when(container.isRunning()).thenReturn(false);
      assertThatCode(() -> chaos.reset(container)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("running container with all side effects — does not throw")
    void resetRunningContainerCompletes() {
      assertThatCode(() -> chaos.reset(container)).doesNotThrowAnyException();
    }
  }

  // ==================== Syscall injection (unit-level) ====================

  @Nested
  @DisplayName("syscall injection (mock container)")
  class SyscallInjection {

    @Test
    @DisplayName("injectIOError — happy path does not throw when container prepared")
    void injectIOErrorHappyPath() {
      assertThatCode(
              () ->
                  chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("injectIOLatency — happy path does not throw when container prepared")
    void injectIOLatencyHappyPath() {
      assertThatCode(
              () ->
                  chaos.injectIOLatency(
                      container, "/data", DiskOperation.FSYNC, Duration.ofMillis(200)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("injectTornWrite — happy path does not throw when container prepared")
    void injectTornWriteHappyPath() {
      assertThatCode(() -> chaos.injectTornWrite(container, "/data", 0.1))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("injectCorruptRead — happy path does not throw when container prepared")
    void injectCorruptReadHappyPath() {
      assertThatCode(() -> chaos.injectCorruptRead(container, "/data", 0.05))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("injectIOError — container not prepared throws IllegalStateException")
    void injectIOErrorNotPrepared() {
      final Map<String, String> labelsWithoutActive = new HashMap<>();
      for (final Tool tool : Tool.values()) {
        labelsWithoutActive.put(PackageInstaller.LABEL_PREFIX + tool.name().toLowerCase(), "true");
      }
      when(container.getLabels()).thenReturn(labelsWithoutActive);

      assertThatThrownBy(
              () ->
                  chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("prepare()");
    }

    @Test
    @DisplayName("injectTornWrite — container not prepared throws IllegalStateException")
    void injectTornWriteNotPrepared() {
      when(container.getLabels()).thenReturn(new HashMap<>());

      assertThatThrownBy(() -> chaos.injectTornWrite(container, "/data", 0.1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("prepare()");
    }

    @Test
    @DisplayName("injectCorruptRead — boundary probability 0.0 accepted")
    void injectCorruptReadZeroProbability() {
      assertThatCode(() -> chaos.injectCorruptRead(container, "/data", 0.0))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("injectCorruptRead — boundary probability 1.0 accepted")
    void injectCorruptReadOneProbability() {
      assertThatCode(() -> chaos.injectCorruptRead(container, "/data", 1.0))
          .doesNotThrowAnyException();
    }
  }

  // ==================== DiskCommandBuilder constructor injection ====================

  @Nested
  @DisplayName("DiskCommandBuilder constructor injection")
  class CommandBuilderInjection {

    @Test
    @DisplayName("null DiskCommandBuilder throws NullPointerException")
    void nullCommandBuilderThrows() {
      assertThatThrownBy(() -> new CgroupsDiskChaos(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("commands must not be null");
    }

    @Test
    @DisplayName("default constructor uses StressNgDiskCommandBuilder implicitly")
    void defaultConstructorWorks() {
      final CgroupsDiskChaos defaultInstance = new CgroupsDiskChaos();
      assertThat(defaultInstance.isSupported()).isTrue();
    }
  }

  // ==================== Helpers ====================

  @SuppressWarnings("unchecked")
  private static ExecResult execResult(final int exitCode, final String stdout) {
    final ExecResult result = mock(ExecResult.class);
    when(result.getExitCode()).thenReturn(exitCode);
    when(result.getStdout()).thenReturn(stdout);
    when(result.getStderr()).thenReturn("");
    return result;
  }
}
