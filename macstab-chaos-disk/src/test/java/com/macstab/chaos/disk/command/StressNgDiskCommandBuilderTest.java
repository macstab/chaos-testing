/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.macstab.chaos.core.command.disk.DiskCommandBuilder;
import com.macstab.chaos.core.exception.ChaosConfigurationException;

/**
 * Unit tests for {@link StressNgDiskCommandBuilder}.
 *
 * <p>All tests are pure unit tests — no Docker, no I/O, no side effects. Every method is a pure
 * string function; tests verify structural correctness of the produced shell commands.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("StressNgDiskCommandBuilder")
class StressNgDiskCommandBuilderTest {

  private StressNgDiskCommandBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new StressNgDiskCommandBuilder();
  }

  // ==================== buildStressHddCommand ====================

  @Nested
  @DisplayName("buildStressHddCommand")
  class StressHddCommand {

    @Test
    @DisplayName("uses stress-ng --hdd stressor")
    void usesHddStressor() {
      assertThat(builder.buildStressHddCommand(2)).contains("stress-ng --hdd 2");
    }

    @Test
    @DisplayName("runs indefinitely with --timeout 0")
    void runsIndefinitely() {
      assertThat(builder.buildStressHddCommand(1)).contains("--timeout 0");
    }

    @Test
    @DisplayName("runs in background")
    void runsInBackground() {
      assertThat(builder.buildStressHddCommand(1)).endsWith("&");
    }

    @Test
    @DisplayName("suppresses output to /dev/null")
    void suppressesOutput() {
      assertThat(builder.buildStressHddCommand(1)).contains(">/dev/null 2>&1");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    @DisplayName("embeds worker count correctly")
    void embedsWorkerCount(final int workers) {
      assertThat(builder.buildStressHddCommand(workers)).contains("--hdd " + workers);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -10})
    @DisplayName("rejects invalid worker counts")
    void rejectsInvalidWorkers(final int workers) {
      assertThatThrownBy(() -> builder.buildStressHddCommand(workers))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("workers must be >= 1");
    }
  }

  // ==================== buildStressHddWithTimeoutCommand ====================

  @Nested
  @DisplayName("buildStressHddWithTimeoutCommand")
  class StressHddWithTimeoutCommand {

    @Test
    @DisplayName("appends seconds suffix to timeout value")
    void appendsSecondsSuffix() {
      assertThat(builder.buildStressHddWithTimeoutCommand(1, 30)).contains("--timeout 30s");
    }

    @Test
    @DisplayName("embeds both workers and timeout")
    void embedsBothParams() {
      assertThat(builder.buildStressHddWithTimeoutCommand(3, 60))
          .contains("--hdd 3")
          .contains("--timeout 60s");
    }

    @Test
    @DisplayName("runs in background")
    void runsInBackground() {
      assertThat(builder.buildStressHddWithTimeoutCommand(1, 10)).endsWith("&");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -60})
    @DisplayName("rejects zero or negative seconds")
    void rejectsInvalidSeconds(final long seconds) {
      assertThatThrownBy(() -> builder.buildStressHddWithTimeoutCommand(1, seconds))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("seconds must be >= 1");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("rejects invalid workers")
    void rejectsInvalidWorkers(final int workers) {
      assertThatThrownBy(() -> builder.buildStressHddWithTimeoutCommand(workers, 10))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("workers must be >= 1");
    }
  }

  // ==================== buildIsStressedCommand ====================

  @Nested
  @DisplayName("buildIsStressedCommand")
  class IsStressedCommand {

    @Test
    @DisplayName("uses prefix match on stress-ng comm")
    void usesPrefixMatch() {
      assertThat(builder.buildIsStressedCommand())
          .contains("grep -rl '^stress-ng' /proc/[0-9]*/comm");
    }

    @Test
    @DisplayName("filters zombie state Z via /proc/stat")
    void filtersZombieState() {
      assertThat(builder.buildIsStressedCommand())
          .contains("awk")
          .contains("!= \"Z\"");
    }

    @Test
    @DisplayName("exits 0 on first non-zombie match, 1 if all zombies or none")
    void exitCodeSemantics() {
      assertThat(builder.buildIsStressedCommand())
          .contains("exit 0")
          .contains("exit 1");
    }

    @Test
    @DisplayName("suppresses errors from missing /proc entries")
    void suppressesErrors() {
      assertThat(builder.buildIsStressedCommand()).contains("2>/dev/null");
    }
  }

  // ==================== buildKillStressNgCommand ====================

  @Nested
  @DisplayName("buildKillStressNgCommand")
  class KillStressNgCommand {

    @Test
    @DisplayName("uses grep to find stress-ng comm files")
    void usesGrepForPrefixMatch() {
      assertThat(builder.buildKillStressNgCommand())
          .contains("grep -rl '^stress-ng' /proc/[0-9]*/comm");
    }

    @Test
    @DisplayName("sends SIGKILL (-9)")
    void sendsSigKill() {
      assertThat(builder.buildKillStressNgCommand()).contains("kill -9");
    }

    @Test
    @DisplayName("is idempotent — always exits 0 via trailing true")
    void isIdempotent() {
      assertThat(builder.buildKillStressNgCommand()).contains("; true");
    }

    @Test
    @DisplayName("suppresses kill errors")
    void suppressesKillErrors() {
      assertThat(builder.buildKillStressNgCommand()).contains("2>/dev/null");
    }
  }

  // ==================== buildGetDiskTotalKBCommand ====================

  @Nested
  @DisplayName("buildGetDiskTotalKBCommand")
  class GetDiskTotalKBCommand {

    @Test
    @DisplayName("uses POSIX df -P")
    void usesPosixDf() {
      assertThat(builder.buildGetDiskTotalKBCommand("/data")).contains("df -P /data");
    }

    @Test
    @DisplayName("extracts total from column 2 via awk NR==2")
    void extractsTotalColumn() {
      assertThat(builder.buildGetDiskTotalKBCommand("/data"))
          .contains("awk")
          .contains("NR==2")
          .contains("$2");
    }

    @Test
    @DisplayName("embeds mount point correctly")
    void embedsMountPoint() {
      assertThat(builder.buildGetDiskTotalKBCommand("/var/lib/redis"))
          .contains("/var/lib/redis");
    }

    @Test
    @DisplayName("does NOT use GNU --output flag")
    void doesNotUseGnuOutputFlag() {
      assertThat(builder.buildGetDiskTotalKBCommand("/data")).doesNotContain("--output");
    }

    @Test
    @DisplayName("rejects null mount point")
    void rejectsNull() {
      assertThatThrownBy(() -> builder.buildGetDiskTotalKBCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("mountPoint must not be null");
    }
  }

  // ==================== buildGetDiskUsagePercentCommand ====================

  @Nested
  @DisplayName("buildGetDiskUsagePercentCommand")
  class GetDiskUsagePercentCommand {

    @Test
    @DisplayName("uses POSIX df -P")
    void usesPosixDf() {
      assertThat(builder.buildGetDiskUsagePercentCommand("/tmp")).contains("df -P /tmp");
    }

    @Test
    @DisplayName("extracts usage percent from column 5 via awk NR==2")
    void extractsUsageColumn() {
      assertThat(builder.buildGetDiskUsagePercentCommand("/tmp"))
          .contains("awk")
          .contains("NR==2")
          .contains("$5");
    }

    @Test
    @DisplayName("strips percent sign from output")
    void stripsPercentSign() {
      // gsub(/%/,...) or gsub(/%%/,...) removes the % from column 5
      final String cmd = builder.buildGetDiskUsagePercentCommand("/tmp");
      assertThat(cmd).contains("gsub");
    }

    @Test
    @DisplayName("does NOT use GNU --output=pcent")
    void doesNotUseGnuOutputPcent() {
      assertThat(builder.buildGetDiskUsagePercentCommand("/tmp")).doesNotContain("--output");
    }

    @Test
    @DisplayName("embeds mount point correctly")
    void embedsMountPoint() {
      assertThat(builder.buildGetDiskUsagePercentCommand("/mnt/data")).contains("/mnt/data");
    }

    @Test
    @DisplayName("rejects null mount point")
    void rejectsNull() {
      assertThatThrownBy(() -> builder.buildGetDiskUsagePercentCommand(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("mountPoint must not be null");
    }
  }

  // ==================== buildFillDiskByCountKBCommand ====================

  @Nested
  @DisplayName("buildFillDiskByCountKBCommand")
  class FillDiskByCountKBCommand {

    @Test
    @DisplayName("removes existing load file first")
    void removesExistingFile() {
      assertThat(builder.buildFillDiskByCountKBCommand("/tmp/chaos-disk-load", 1024))
          .contains("rm -f /tmp/chaos-disk-load");
    }

    @Test
    @DisplayName("attempts fallocate first")
    void attemptsFallocateFirst() {
      assertThat(builder.buildFillDiskByCountKBCommand("/tmp/chaos-disk-load", 1024))
          .contains("fallocate -l 1024K /tmp/chaos-disk-load");
    }

    @Test
    @DisplayName("falls back to dd with same count")
    void fallsBackToDd() {
      assertThat(builder.buildFillDiskByCountKBCommand("/tmp/chaos-disk-load", 2048))
          .contains("dd if=/dev/zero of=/tmp/chaos-disk-load bs=1K count=2048");
    }

    @Test
    @DisplayName("uses || for fallocate→dd fallback")
    void usesFallbackOperator() {
      assertThat(builder.buildFillDiskByCountKBCommand("/tmp/chaos-disk-load", 1024))
          .contains("||");
    }

    @Test
    @DisplayName("silences fallocate errors (unsupported filesystem)")
    void silencesFallocateErrors() {
      assertThat(builder.buildFillDiskByCountKBCommand("/tmp/chaos-disk-load", 1024))
          .contains("2>/dev/null");
    }

    @Test
    @DisplayName("rejects null loadFile")
    void rejectsNullLoadFile() {
      assertThatThrownBy(() -> builder.buildFillDiskByCountKBCommand(null, 1024))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("loadFile must not be null");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100})
    @DisplayName("rejects zero or negative countKB")
    void rejectsInvalidCount(final long countKB) {
      assertThatThrownBy(
              () -> builder.buildFillDiskByCountKBCommand("/tmp/chaos-disk-load", countKB))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("countKB must be >= 1");
    }
  }

  // ==================== buildFillDiskBySizeCommand ====================

  @Nested
  @DisplayName("buildFillDiskBySizeCommand")
  class FillDiskBySizeCommand {

    @Test
    @DisplayName("removes existing load file first")
    void removesExistingFile() {
      assertThat(builder.buildFillDiskBySizeCommand("/tmp/chaos-disk-load", "500M", 500))
          .contains("rm -f /tmp/chaos-disk-load");
    }

    @Test
    @DisplayName("attempts fallocate with given size string")
    void attemptsFallocateWithSizeString() {
      assertThat(builder.buildFillDiskBySizeCommand("/tmp/chaos-disk-load", "500M", 500))
          .contains("fallocate -l 500M /tmp/chaos-disk-load");
    }

    @Test
    @DisplayName("falls back to dd with sizeMb")
    void fallsBackToDdWithSizeMb() {
      assertThat(builder.buildFillDiskBySizeCommand("/tmp/chaos-disk-load", "100M", 100))
          .contains("dd if=/dev/zero of=/tmp/chaos-disk-load bs=1M count=100");
    }

    @Test
    @DisplayName("uses || for fallocate→dd fallback")
    void usesFallbackOperator() {
      assertThat(builder.buildFillDiskBySizeCommand("/tmp/chaos-disk-load", "10M", 10))
          .contains("||");
    }

    @Test
    @DisplayName("silences fallocate errors")
    void silencesFallocateErrors() {
      assertThat(builder.buildFillDiskBySizeCommand("/tmp/chaos-disk-load", "10M", 10))
          .contains("2>/dev/null");
    }

    @Test
    @DisplayName("rejects null loadFile")
    void rejectsNullLoadFile() {
      assertThatThrownBy(() -> builder.buildFillDiskBySizeCommand(null, "10M", 10))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects null size string")
    void rejectsNullSize() {
      assertThatThrownBy(() -> builder.buildFillDiskBySizeCommand("/tmp/chaos-disk-load", null, 10))
          .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("rejects zero or negative sizeMb")
    void rejectsInvalidSizeMb(final int sizeMb) {
      assertThatThrownBy(
              () -> builder.buildFillDiskBySizeCommand("/tmp/chaos-disk-load", "10M", sizeMb))
          .isInstanceOf(ChaosConfigurationException.class)
          .hasMessageContaining("sizeMb must be >= 1");
    }
  }

  // ==================== buildRemoveFillFilesCommand ====================

  @Nested
  @DisplayName("buildRemoveFillFilesCommand")
  class RemoveFillFilesCommand {

    @Test
    @DisplayName("uses find to search the whole filesystem")
    void usesFind() {
      assertThat(builder.buildRemoveFillFilesCommand())
          .contains("find /")
          .contains("chaos-disk-load");
    }

    @Test
    @DisplayName("deletes matched files via -delete")
    void deletesMatchedFiles() {
      assertThat(builder.buildRemoveFillFilesCommand()).contains("-delete");
    }

    @Test
    @DisplayName("is idempotent — always exits 0")
    void isIdempotent() {
      assertThat(builder.buildRemoveFillFilesCommand()).contains("|| true");
    }

    @Test
    @DisplayName("suppresses errors from inaccessible directories")
    void suppressesErrors() {
      assertThat(builder.buildRemoveFillFilesCommand()).contains("2>/dev/null");
    }
  }

  // ==================== Singleton Contract ====================

  @Nested
  @DisplayName("INSTANCE singleton")
  class SingletonContract {

    @Test
    @DisplayName("INSTANCE is not null")
    void instanceIsNotNull() {
      assertThat(StressNgDiskCommandBuilder.INSTANCE).isNotNull();
    }

    @Test
    @DisplayName("INSTANCE implements DiskCommandBuilder")
    void instanceImplementsDiskCommandBuilder() {
      assertThat(StressNgDiskCommandBuilder.INSTANCE).isInstanceOf(DiskCommandBuilder.class);
    }

    @Test
    @DisplayName("new instance produces same commands as INSTANCE (stateless)")
    void newInstanceBehavesLikeSingleton() {
      final StressNgDiskCommandBuilder fresh = new StressNgDiskCommandBuilder();

      assertThat(StressNgDiskCommandBuilder.INSTANCE.buildStressHddCommand(2))
          .isEqualTo(fresh.buildStressHddCommand(2));
    }
  }
}
