/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.syscall.DiskErrno;
import com.macstab.chaos.core.syscall.DiskOperation;
import com.macstab.chaos.core.syscall.SyscallFaultInjector;

/**
 * Integration tests for the syscall-level fault injection methods of {@link CgroupsDiskChaos}.
 *
 * <p>These tests require a separate container setup from {@link CgroupsDiskChaosTest} because
 * {@link SyscallFaultInjector#prepare} must be called <strong>before</strong> {@code
 * container.start()}. The library is copied in via {@code withCopyToContainer}; the active label is
 * set; and the config file is written at runtime via {@code echo >> /tmp/.chaos-io.conf}.
 *
 * <p>The assertion strategy: after injecting a rule, {@code cat /tmp/.chaos-io.conf} inside the
 * container confirms that the rule was written correctly. End-to-end effect (actual fault delivery)
 * is not tested here — that requires a workload that exercises the intercepted syscall path.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsDiskChaos — syscall injection integration")
class CgroupsDiskChaosSyscallIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-io.conf";

  private GenericContainer<?> container;
  private CgroupsDiskChaos chaos;

  @BeforeEach
  void setUp() {
    container = new GenericContainer<>(DockerImageName.parse("redis:7.4"));
    // Must be called BEFORE start() — copies .so and sets LD_PRELOAD + label
    SyscallFaultInjector.prepare(container);
    container.start();
    chaos = new CgroupsDiskChaos();
  }

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      chaos.reset(container);
      container.stop();
    }
  }

  // ==================== injectIOError ====================

  @Nested
  @DisplayName("injectIOError")
  class InjectIOError {

    @Test
    @DisplayName("writes an ERRNO rule to the config file with correct format")
    void writesErrnoRuleToConfig() throws Exception {
      chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3);

      final String config =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
      assertThat(config)
          .contains("# disk")
          .contains("/data")
          .contains("write")
          // ERRNO is the error code token — see below
          .contains("EIO");
    }

    @Test
    @DisplayName("ENOSPC variant writes correctly")
    void writesEnospcVariant() throws Exception {
      chaos.injectIOError(container, "/var/lib/redis", DiskOperation.WRITE, DiskErrno.ENOSPC, 1.0);

      final String config =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
      assertThat(config).contains("ENOSPC").contains("/var/lib/redis");
    }

    @Test
    @DisplayName("multiple rules accumulate in the config file")
    void multipleRulesAccumulate() throws Exception {
      chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3);
      chaos.injectIOError(container, "/logs", DiskOperation.READ, DiskErrno.EIO, 0.5);

      final String config =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
      assertThat(config).contains("/data").contains("/logs");
    }
  }

  // ==================== injectIOLatency ====================

  @Nested
  @DisplayName("injectIOLatency")
  class InjectIOLatency {

    @Test
    @DisplayName("writes a LATENCY rule with millisecond value")
    void writesLatencyRule() throws Exception {
      chaos.injectIOLatency(
          container, "/data/wal.log", DiskOperation.FSYNC, Duration.ofMillis(200));

      final String config =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
      assertThat(config)
          .contains("# disk")
          .contains("/data/wal.log")
          .contains("fsync")
          .contains("LATENCY")
          .contains("200");
    }

    @Test
    @DisplayName("1-second latency writes 1000 ms to config")
    void oneSecondLatencyWritesMilliseconds() throws Exception {
      chaos.injectIOLatency(container, "/data", DiskOperation.WRITE, Duration.ofSeconds(1));

      final String config =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
      assertThat(config).contains("LATENCY").contains("1000");
    }
  }

  // ==================== injectTornWrite ====================

  @Nested
  @DisplayName("injectTornWrite")
  class InjectTornWrite {

    @Test
    @DisplayName("writes a TORN rule with the given probability")
    void writesTornRule() throws Exception {
      chaos.injectTornWrite(container, "/data", 0.1);

      final String config =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
      assertThat(config).contains("# disk").contains("/data").contains("write").contains("TORN");
    }
  }

  // ==================== injectCorruptRead ====================

  @Nested
  @DisplayName("injectCorruptRead")
  class InjectCorruptRead {

    @Test
    @DisplayName("writes a CORRUPT rule for read operations")
    void writesCorruptRule() throws Exception {
      chaos.injectCorruptRead(container, "/data", 0.05);

      final String config =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
      assertThat(config).contains("# disk").contains("/data").contains("read").contains("CORRUPT");
    }
  }

  // ==================== reset clears syscall rules ====================

  @Nested
  @DisplayName("reset — syscall rule cleanup")
  class ResetClearsRules {

    @Test
    @DisplayName("reset removes all disk-owner rules from the config file")
    void resetRemovesDiskRules() throws Exception {
      chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3);
      chaos.injectIOLatency(container, "/data", DiskOperation.FSYNC, Duration.ofMillis(100));

      chaos.reset(container);

      final var catResult =
          container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH + " 2>/dev/null");
      // Either the file is gone or no disk: lines remain
      final String remaining = catResult.getStdout();
      assertThat(remaining).doesNotContain("disk:");
    }

    @Test
    @DisplayName("reset preserves rules from other owners")
    void resetPreservesOtherOwnerRules() throws Exception {
      // Manually write a rule for a different owner to verify selective removal
      container.execInContainer(
          "/bin/sh", "-c", "echo 'net:/data:write:ERRNO:EIO:0.5' >> " + CONFIG_PATH);
      chaos.injectIOError(container, "/data", DiskOperation.WRITE, DiskErrno.EIO, 0.3);

      chaos.reset(container);

      final String remaining =
          container
              .execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH + " 2>/dev/null")
              .getStdout();
      assertThat(remaining).doesNotContain("disk:").contains("net:");
    }
  }
}
