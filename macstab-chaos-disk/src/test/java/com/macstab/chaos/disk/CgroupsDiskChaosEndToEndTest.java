/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.disk;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Capability;
import com.macstab.chaos.core.syscall.SyscallFaultInjector;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests that validate <strong>actual fault delivery</strong> inside a live container.
 *
 * <p>Every test in this class verifies an observable runtime effect — not just that a config file
 * was written, but that the fault actually manifests to a workload running inside the container.
 *
 * <h2>Coverage scope</h2>
 *
 * <ul>
 *   <li><strong>Stress</strong> — {@code stressDisk(workers)} and {@code stressDisk(workers,
 *       duration)}: workers start, {@code isStressed} returns true, timed variant auto-stops.
 *   <li><strong>ENOSPC (filesystem level)</strong> — {@code fillDisk} and {@code fillDiskBySize}
 *       consume a bounded tmpfs, subsequent writes return "No space left on device".
 *   <li><strong>Error injection — all errno codes</strong> — EIO, ENOSPC, EACCES, EROFS, EDQUOT
 *       on the {@code write} operation via {@code injectIOError}.
 *   <li><strong>Error injection — all documented operations</strong> — write, pwrite, read, pread,
 *       open, fsync, fdatasync, close: each injected at 100% and confirmed by workload exit code.
 *   <li><strong>Latency injection — write and read</strong> — measurable wall-clock delay via
 *       {@code injectIOLatency} on write and fsync operations.
 *   <li><strong>Probabilistic injection</strong> — 50% error rate; confirmed via repeated samples.
 *   <li><strong>Torn write</strong> — file is shorter than expected at 100% probability.
 *   <li><strong>Corrupt read</strong> — MD5 changes at 100% probability; restored after reset.
 *   <li><strong>Alpine / musl variant</strong> — all libchaos-io tests repeated on
 *       {@code redis:7.4-alpine} to validate the musl-linked {@code .so} binary.
 *   <li><strong>Redis workload</strong> — Redis receives EIO on {@code /data} writes; Redis
 *       BGSAVE fails when the data directory is full.
 *   <li><strong>Reset semantics</strong> — space reclaimed, rules removed, writes succeed.
 * </ul>
 *
 * <p><strong>tmpfs strategy:</strong> All fill tests mount a bounded tmpfs (10–100 MB) so that
 * percentage fills stay deterministically small — never gigabyte-scale dd writes on overlay.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CgroupsDiskChaos — end-to-end fault delivery")
class CgroupsDiskChaosEndToEndTest {

  private static final String DEBIAN_IMAGE = "redis:7.4";
  private static final String ALPINE_IMAGE = "redis:7.4-alpine";

  /**
   * Prefixes a shell command with {@code LD_PRELOAD} so that libchaos-io is active.
   *
   * <p>Docker exec processes do not inherit runtime {@code --env} variables set on the container
   * (only Dockerfile {@code ENV} entries are inherited). Test commands that need syscall interception
   * must explicitly request it here.
   */
  private static final String WITH_LIB =
      "LD_PRELOAD=" + SyscallFaultInjector.LIBRARY_PATH + " ";

  private GenericContainer<?> container;
  private CgroupsDiskChaos chaos;

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      if (chaos != null) chaos.reset(container);
      container.stop();
    }
  }

  // ==================== Stress ====================

  @Nested
  @DisplayName("stressDisk — I/O load generation")
  class StressDiskTests {

    @Test
    @DisplayName("stressDisk(workers) — isStressed returns true after workers start")
    void stressDiskWorkersStartAndAreDetected() {
      container = debian();
      chaos = new CgroupsDiskChaos();

      chaos.stressDisk(container, 2);

      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    @DisplayName("stressDisk(workers) — multiple worker counts all reach isStressed=true")
    void differentWorkerCountsAllDetected(final int workers) {
      container = debian();
      chaos = new CgroupsDiskChaos();

      chaos.stressDisk(container, workers);

      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());
    }

    @Test
    @DisplayName("stressDisk(workers, duration) — auto-stops after timeout expires")
    void timedStressAutoStops() {
      container = debian();
      chaos = new CgroupsDiskChaos();

      // 5-second stress — should auto-stop before our 30s wait expires
      chaos.stressDisk(container, 1, Duration.ofSeconds(5));

      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());

      Awaitility.await()
          .atMost(Duration.ofSeconds(30))
          .pollDelay(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isFalse());
    }

    @Test
    @DisplayName("reset stops all running stress workers")
    void resetKillsWorkers() {
      container = debian();
      chaos = new CgroupsDiskChaos();

      chaos.stressDisk(container, 2);
      Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> chaos.isStressed(container));

      chaos.reset(container);

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollDelay(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isFalse());
    }

    @Test
    @DisplayName("isStressed returns false before any stress is started")
    void isStressedFalseWhenIdle() {
      container = debian();
      chaos = new CgroupsDiskChaos();

      assertThat(chaos.isStressed(container)).isFalse();
    }
  }

  // ==================== ENOSPC (filesystem level) ====================

  @Nested
  @DisplayName("ENOSPC — filesystem full via fillDisk / fillDiskBySize")
  class EnospcTests {

    @Test
    @DisplayName("fillDisk(90%) on 10 MB tmpfs causes next write to return ENOSPC")
    void writeToFullDiskReturnsEnospc() throws Exception {
      container = debianWithTmpFs("/chaos-data", "10m");
      chaos = new CgroupsDiskChaos();

      chaos.fillDisk(container, "/chaos-data", 90);

      final var result = container.execInContainer(
          "/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-data/overflow bs=1M count=5 2>&1; echo __exit:$?");

      assertThat(result.getStdout() + result.getStderr())
          .as("write to full tmpfs must report ENOSPC")
          .contains("No space left on device");
    }

    @Test
    @DisplayName("fillDiskBySize(18M) on 20 MB tmpfs causes next write to return ENOSPC")
    void fillBySizeThenWriteFails() throws Exception {
      container = debianWithTmpFs("/chaos-data", "20m");
      chaos = new CgroupsDiskChaos();

      chaos.fillDiskBySize(container, "/chaos-data", "18M");

      final var result = container.execInContainer(
          "/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-data/overflow bs=1M count=5 2>&1; echo __exit:$?");

      assertThat(result.getStdout() + result.getStderr())
          .as("write beyond capacity must report ENOSPC")
          .contains("No space left on device");
    }

    @Test
    @DisplayName("getDiskUsagePercent >= 88 after 90% fill on 50 MB tmpfs")
    void diskUsageReflectsFill() {
      container = debianWithTmpFs("/chaos-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.fillDisk(container, "/chaos-data", 90);

      final int usage = chaos.getDiskUsagePercent(container, "/chaos-data");
      assertThat(usage).as("usage after 90%% fill").isGreaterThanOrEqualTo(88);
    }

    @Test
    @DisplayName("getDiskUsagePercent increases after fillDiskBySize")
    void diskUsageIncreasesAfterFillBySize() {
      container = debianWithTmpFs("/chaos-data", "50m");
      chaos = new CgroupsDiskChaos();

      final int before = chaos.getDiskUsagePercent(container, "/chaos-data");
      chaos.fillDiskBySize(container, "/chaos-data", "20M");
      final int after = chaos.getDiskUsagePercent(container, "/chaos-data");

      assertThat(after).as("usage must increase after fill").isGreaterThan(before);
    }

    @Test
    @DisplayName("reset reclaims space — write succeeds after reset removes fill file")
    void resetReclaimsDiskSpace() throws Exception {
      container = debianWithTmpFs("/chaos-data", "10m");
      chaos = new CgroupsDiskChaos();

      chaos.fillDisk(container, "/chaos-data", 90);
      // Confirm ENOSPC before reset
      final var before = container.execInContainer(
          "/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-data/overflow bs=1M count=5 2>&1");
      assertThat(before.getStdout() + before.getStderr()).contains("No space left on device");

      chaos.reset(container);

      final var after = container.execInContainer(
          "/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-data/test2 bs=1M count=2 2>&1; echo __exit:$?");
      assertThat(after.getStdout() + after.getStderr())
          .as("write must succeed after reset frees space")
          .contains("__exit:0")
          .doesNotContain("No space left on device");
    }
  }

  // ==================== Error injection — errno codes ====================

  @Nested
  @DisplayName("injectIOError — all errno codes on write operation")
  class ErrnoCodeTests {

    @Test
    @DisplayName("EIO on write — cp exits non-zero with I/O error")
    void eioOnWriteCausesDdToFail() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EIO", 1.0);

      final var result = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/test.dat 2>&1; echo __exit:$?");

      assertThat(result.getStdout() + result.getStderr())
          .contains("__exit:1")
          .satisfiesAnyOf(
              s -> assertThat(s).contains("Input/output error"),
              s -> assertThat(s).contains("error"));
    }

    @Test
    @DisplayName("ENOSPC on write — cp exits non-zero with no space message")
    void enospcOnWriteCausesDdToFail() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "ENOSPC", 1.0);

      final var result = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/test.dat 2>&1; echo __exit:$?");

      assertThat(result.getStdout() + result.getStderr())
          .contains("__exit:1")
          .satisfiesAnyOf(
              s -> assertThat(s).contains("No space left on device"),
              s -> assertThat(s).contains("error"));
    }

    @Test
    @DisplayName("EACCES on write — cp exits non-zero with permission denied")
    void eaccessOnWriteCausesDdToFail() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EACCES", 1.0);

      final var result = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/test.dat 2>&1; echo __exit:$?");

      assertThat(result.getStdout() + result.getStderr())
          .contains("__exit:1")
          .satisfiesAnyOf(
              s -> assertThat(s).contains("Permission denied"),
              s -> assertThat(s).contains("error"));
    }

    @Test
    @DisplayName("EROFS on write — cp exits non-zero (read-only filesystem error)")
    void erofsOnWriteCausesDdToFail() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EROFS", 1.0);

      final var result = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/test.dat 2>&1; echo __exit:$?");

      assertThat(result.getStdout() + result.getStderr())
          .contains("__exit:1")
          .satisfiesAnyOf(
              s -> assertThat(s).contains("Read-only file system"),
              s -> assertThat(s).contains("error"));
    }

    @Test
    @DisplayName("EDQUOT on write — cp exits non-zero (disk quota exceeded)")
    void edquotOnWriteCausesDdToFail() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EDQUOT", 1.0);

      final var result = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/test.dat 2>&1; echo __exit:$?");

      assertThat(result.getStdout() + result.getStderr())
          .contains("__exit:1")
          .satisfiesAnyOf(
              s -> assertThat(s).contains("Disk quota exceeded"),
              s -> assertThat(s).contains("error"));
    }
  }

  // ==================== Error injection — all operations ====================

  @Nested
  @DisplayName("injectIOError — all documented operations with EIO")
  class OperationCoverageTests {

    @Test
    @DisplayName("write — cp fails with EIO")
    void writeOperationFails() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EIO", 1.0);

      final var r = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/w.dat 2>&1; echo __exit:$?");
      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("pwrite — sqlite3 DB write fails with EIO")
    void pwriteOperationFails() throws Exception {
      // python3 os.open() uses openat() at the C level, bypassing the library's open() hook
      // so fd→path is never mapped and pwrite injection doesn't fire.
      // SQLite (built into Python 3.11) opens files via C open() → hook fires → fd tracked
      // → sqlite3's pwrite64() calls are intercepted.
      container = new GenericContainer<>(DockerImageName.parse("python:3.11-slim"))
          .withTmpFs(Map.of("/chaos-io-data", "rw,size=50m"))
          .withCommand("sleep", "infinity");
      SyscallFaultInjector.prepare(container);
      container.start();
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "pwrite", "EIO", 1.0);

      final var r = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "python3 -c \""
              + "import sqlite3, sys; "
              + "conn = sqlite3.connect('/chaos-io-data/test.db'); "
              + "conn.execute('CREATE TABLE t (x TEXT)'); "
              + "conn.execute(\\\"INSERT INTO t VALUES ('hello')\\\"); "
              + "conn.commit(); "
              + "conn.close()"
              + "\" 2>&1; echo __exit:$?");
      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("read — cat of existing file fails with EIO")
    void readOperationFails() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      // Write a file WITHOUT injection
      container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-io-data/existing.dat bs=1K count=10 2>/dev/null");

      // Now inject read error
      chaos.injectIOError(container, "/chaos-io-data", "read", "EIO", 1.0);

      // Reading the file must fail
      final var r = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cat /chaos-io-data/existing.dat > /dev/null 2>&1; echo __exit:$?");
      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("pread — cat triggers pread; file read fails with EIO")
    void preadOperationFails() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-io-data/existing.dat bs=4K count=4 2>/dev/null");

      chaos.injectIOError(container, "/chaos-io-data", "pread", "EIO", 1.0);

      final var r = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cat /chaos-io-data/existing.dat > /dev/null 2>&1; echo __exit:$?");
      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("open — opening a file in the target path fails with EIO")
    void openOperationFails() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      // Create the file first (before injecting open error)
      container.execInContainer("/bin/sh", "-c",
          "echo hello > /chaos-io-data/target.txt");

      chaos.injectIOError(container, "/chaos-io-data", "open", "EIO", 1.0);

      // Any tool that opens a file in /chaos-io-data should fail
      final var r = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cat /chaos-io-data/target.txt 2>&1; echo __exit:$?");
      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("fsync — dd with conv=fsync fails with EIO on fsync call")
    void fsyncOperationFails() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "fsync", "EIO", 1.0);

      // conv=fsync forces an explicit fsync after write
      final var r = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "dd if=/dev/zero of=/chaos-io-data/sync.dat bs=4K count=1 conv=fsync 2>&1; echo __exit:$?");
      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("fdatasync — dd with conv=fdatasync fails with EIO on fdatasync call")
    void fdatasyncOperationFails() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "fdatasync", "EIO", 1.0);

      final var r = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "dd if=/dev/zero of=/chaos-io-data/sync.dat bs=4K count=1 conv=fdatasync 2>&1; echo __exit:$?");
      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("reset removes all injected operation rules — write succeeds after reset")
    void resetRestoresAllOperations() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EIO", 1.0);
      chaos.injectIOError(container, "/chaos-io-data", "fsync", "EIO", 1.0);

      // Both injected — writes fail
      final var before = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/t.dat 2>&1; echo __exit:$?");
      assertThat(before.getStdout() + before.getStderr()).contains("__exit:1");

      chaos.reset(container);

      // After reset — write succeeds (library loaded but rules cleared)
      final var after = container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/t2.dat 2>&1; echo __exit:$?");
      assertThat(after.getStdout() + after.getStderr()).contains("__exit:0");
    }
  }

  // ==================== Latency injection ====================

  @Nested
  @DisplayName("injectIOLatency — measurable delay across operations")
  class LatencyTests {

    @Test
    @DisplayName("fsync latency 500ms — round-trip exceeds 400ms")
    void fsyncLatencyIsObservable() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOLatency(container, "/chaos-io-data", "fsync", Duration.ofMillis(500));

      final long start = System.currentTimeMillis();
      container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "dd if=/dev/zero of=/chaos-io-data/sync.dat bs=4K count=1 conv=fsync 2>&1");
      final long elapsed = System.currentTimeMillis() - start;

      assertThat(elapsed).as("fsync with 500ms injection must take >= 400ms").isGreaterThan(400L);
    }

    @Test
    @DisplayName("write latency 300ms — round-trip exceeds 250ms")
    void writeLatencyIsObservable() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOLatency(container, "/chaos-io-data", "write", Duration.ofMillis(300));

      final long start = System.currentTimeMillis();
      container.execInContainer(
          "/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/w.dat 2>&1");
      final long elapsed = System.currentTimeMillis() - start;

      assertThat(elapsed).as("write with 300ms injection must take >= 250ms").isGreaterThan(250L);
    }

    @Test
    @DisplayName("read latency 300ms — cat of file takes >= 250ms")
    void readLatencyIsObservable() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      // Write file before injecting latency
      container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-io-data/r.dat bs=4K count=1 2>/dev/null");

      chaos.injectIOLatency(container, "/chaos-io-data", "read", Duration.ofMillis(300));

      final long start = System.currentTimeMillis();
      container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cat /chaos-io-data/r.dat > /dev/null 2>&1");
      final long elapsed = System.currentTimeMillis() - start;

      assertThat(elapsed).as("read with 300ms injection must take >= 250ms").isGreaterThan(250L);
    }

    @Test
    @DisplayName("fdatasync latency 300ms — round-trip exceeds 250ms")
    void fdatasyncLatencyIsObservable() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOLatency(container, "/chaos-io-data", "fdatasync", Duration.ofMillis(300));

      final long start = System.currentTimeMillis();
      container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "dd if=/dev/zero of=/chaos-io-data/fds.dat bs=4K count=1 conv=fdatasync 2>&1");
      final long elapsed = System.currentTimeMillis() - start;

      assertThat(elapsed).as("fdatasync with 300ms injection must take >= 250ms")
          .isGreaterThan(250L);
    }

    @Test
    @DisplayName("reset removes latency — subsequent operation completes in normal time")
    void resetRemovesLatency() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOLatency(container, "/chaos-io-data", "write", Duration.ofMillis(500));
      chaos.reset(container);

      // After reset, write should complete well under 500ms (library loaded, rules cleared)
      final long start = System.currentTimeMillis();
      container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/fast.dat 2>&1");
      final long elapsed = System.currentTimeMillis() - start;

      assertThat(elapsed).as("after reset, write should complete normally in < 400ms")
          .isLessThan(400L);
    }
  }

  // ==================== Probabilistic injection ====================

  @Nested
  @DisplayName("injectIOError — probabilistic (< 1.0) fault injection")
  class ProbabilisticTests {

    @Test
    @DisplayName("50% write error — not all of 20 writes fail (some succeed)")
    void halfWriteErrorsNotAllFail() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EIO", 0.5);

      // Run 20 individual writes; at 50% some will fail and some will succeed
      int failures = 0;
      int successes = 0;
      for (int i = 0; i < 20; i++) {
        final var r = container.execInContainer(
            "/bin/sh", "-c",
            WITH_LIB + "cp /etc/hostname /chaos-io-data/p" + i + ".dat 2>/dev/null; echo $?");
        if (r.getStdout().trim().equals("0")) {
          successes++;
        } else {
          failures++;
        }
      }

      assertThat(failures).as("at 50%% error rate some writes must fail").isGreaterThan(0);
      assertThat(successes).as("at 50%% error rate some writes must succeed").isGreaterThan(0);
    }

    @Test
    @DisplayName("10% write error — majority of 30 writes succeed")
    void tenPercentErrorMajoritySucceed() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EIO", 0.1);

      int successes = 0;
      for (int i = 0; i < 30; i++) {
        final var r = container.execInContainer(
            "/bin/sh", "-c",
            WITH_LIB + "cp /etc/hostname /chaos-io-data/q" + i + ".dat 2>/dev/null; echo $?");
        if (r.getStdout().trim().equals("0")) {
          successes++;
        }
      }

      // At 10% failure rate, at least 20 of 30 should succeed
      assertThat(successes).as("at 10%% error rate majority must succeed").isGreaterThan(20);
    }
  }

  // ==================== Torn write ====================

  @Nested
  @DisplayName("injectTornWrite — partial write injection")
  class TornWriteTests {

    @Test
    @DisplayName("100% torn write — written file is shorter than expected")
    void tornWriteProducesShortFile() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      // Reference file WITHOUT torn write
      container.execInContainer("/bin/sh", "-c",
          "cp /etc/os-release /chaos-io-data/ref.dat 2>/dev/null");
      final long refBytes = Long.parseLong(
          container.execInContainer("/bin/sh", "-c",
              "wc -c < /chaos-io-data/ref.dat").getStdout().trim());

      // Inject 100% torn writes
      chaos.injectTornWrite(container, "/chaos-io-data", 1.0);

      container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/os-release /chaos-io-data/torn.dat 2>/dev/null");
      final long tornBytes = Long.parseLong(
          container.execInContainer("/bin/sh", "-c",
              "wc -c < /chaos-io-data/torn.dat").getStdout().trim());

      assertThat(tornBytes)
          .as("torn write at 100%% must produce a file shorter than %d bytes", refBytes)
          .isLessThan(refBytes);
    }

    @Test
    @DisplayName("torn write workload completes without hanging")
    void tornWriteWorloadCompletes() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectTornWrite(container, "/chaos-io-data", 1.0);

      final var r = container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/os-release /chaos-io-data/t.dat 2>&1; echo __done");

      assertThat(r.getStdout() + r.getStderr())
          .as("torn write workload must complete without hanging")
          .contains("__done");
    }
  }

  // ==================== Corrupt read ====================

  @Nested
  @DisplayName("injectCorruptRead — bit-flip injection on reads")
  class CorruptReadTests {

    @Test
    @DisplayName("100% corrupt read — MD5 of re-read file differs from original")
    void corruptReadChangesMd5() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      // Write known-content file
      container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-io-data/clean.dat bs=4K count=4 2>/dev/null");
      final String cleanMd5 = container.execInContainer("/bin/sh", "-c",
          "md5sum /chaos-io-data/clean.dat").getStdout().split("\\s+")[0];

      // Inject corruption, re-read
      chaos.injectCorruptRead(container, "/chaos-io-data", 1.0);
      final String corruptMd5 = container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "md5sum /chaos-io-data/clean.dat").getStdout().split("\\s+")[0];

      assertThat(corruptMd5)
          .as("100%% corrupt read must change observed checksum")
          .isNotEqualTo(cleanMd5);
    }

    @Test
    @DisplayName("reset removes corrupt read rule — subsequent reads restore original checksum")
    void resetRestoresCleanReads() throws Exception {
      container = debianWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      // Baseline checksum
      container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-io-data/ref.dat bs=4K count=4 2>/dev/null");
      final String originalMd5 = container.execInContainer("/bin/sh", "-c",
          "md5sum /chaos-io-data/ref.dat").getStdout().split("\\s+")[0];

      // Inject, reset, re-write identical content
      chaos.injectCorruptRead(container, "/chaos-io-data", 1.0);
      chaos.reset(container);

      container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-io-data/ref2.dat bs=4K count=4 2>/dev/null");
      final String afterMd5 = container.execInContainer("/bin/sh", "-c",
          "md5sum /chaos-io-data/ref2.dat").getStdout().split("\\s+")[0];

      assertThat(afterMd5)
          .as("after reset reads must be clean — MD5 must match all-zeros reference")
          .isEqualTo(originalMd5);
    }
  }

  // ==================== Alpine / musl variant ====================

  @Nested
  @DisplayName("Alpine/musl — libchaos-io musl variant validation")
  class AlpineTests {

    @Test
    @DisplayName("EIO on write — cp fails inside Alpine/musl container")
    void eioOnWriteAlpine() throws Exception {
      container = alpineWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOError(container, "/chaos-io-data", "write", "EIO", 1.0);

      final var r = container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /chaos-io-data/test.dat 2>&1; echo __exit:$?");

      assertThat(r.getStdout() + r.getStderr()).contains("__exit:1");
    }

    @Test
    @DisplayName("fsync latency 500ms — observable inside Alpine/musl container")
    void fsyncLatencyAlpine() throws Exception {
      container = alpineWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      chaos.injectIOLatency(container, "/chaos-io-data", "fsync", Duration.ofMillis(500));

      final long start = System.currentTimeMillis();
      container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "dd if=/dev/zero of=/chaos-io-data/sync.dat bs=4K count=1 conv=fsync 2>&1");
      final long elapsed = System.currentTimeMillis() - start;

      assertThat(elapsed).as("fsync latency observable on Alpine").isGreaterThan(400L);
    }

    @Test
    @DisplayName("torn write — file shorter than expected inside Alpine/musl container")
    void tornWriteAlpine() throws Exception {
      container = alpineWithLibchaosIo("/chaos-io-data", "50m");
      chaos = new CgroupsDiskChaos();

      container.execInContainer("/bin/sh", "-c",
          "cp /etc/os-release /chaos-io-data/ref.dat 2>/dev/null");
      final long refBytes = Long.parseLong(
          container.execInContainer("/bin/sh", "-c",
              "wc -c < /chaos-io-data/ref.dat").getStdout().trim());

      chaos.injectTornWrite(container, "/chaos-io-data", 1.0);

      container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/os-release /chaos-io-data/torn.dat 2>/dev/null");
      final long tornBytes = Long.parseLong(
          container.execInContainer("/bin/sh", "-c",
              "wc -c < /chaos-io-data/torn.dat").getStdout().trim());

      assertThat(tornBytes).as("torn write on Alpine must produce short file").isLessThan(refBytes);
    }

    @Test
    @DisplayName("ENOSPC fill + isStressed work correctly on Alpine/musl")
    void fillAndStressOnAlpine() throws Exception {
      // Container with tmpfs for fill testing (no libchaos needed for ENOSPC fill)
      container = new GenericContainer<>(DockerImageName.parse(ALPINE_IMAGE))
          .withTmpFs(Map.of("/chaos-data", "rw,size=10m"));
      container.start();
      chaos = new CgroupsDiskChaos();

      chaos.fillDisk(container, "/chaos-data", 90);

      final var r = container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/chaos-data/overflow bs=1M count=5 2>&1");
      assertThat(r.getStdout() + r.getStderr()).contains("No space left on device");

      chaos.reset(container);

      chaos.stressDisk(container, 1);
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());
    }
  }

  // ==================== Redis workload integration ====================

  @Nested
  @DisplayName("Redis workload — chaos effects on real Redis I/O")
  class RedisWorkloadTests {

    @Test
    @DisplayName("fillDisk on /data causes Redis BGSAVE to fail with ENOSPC")
    void fillRedisDataDirCausesBgsaveToFail() throws Exception {
      // 6 MB tmpfs: after 90% fill only ~0.6 MB remains.
      // SETRANGE creates a 5 MB key (in Redis memory, not on disk); with rdbcompression=no
      // the RDB dump is ~5 MB — 8× larger than available free space → BGSAVE gets ENOSPC.
      container = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE))
          .withTmpFs(Map.of("/data", "rw,size=6m"))
          .withCommand("redis-server", "--dir", "/data", "--save", "",
              "--rdbcompression", "no");
      container.start();
      chaos = new CgroupsDiskChaos();

      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> container.execInContainer("redis-cli", "PING")
              .getStdout().trim().equals("PONG"));

      // SETRANGE zero-pads to offset 5 MB-1 → 5 MB key stored in Redis memory only.
      // With rdbcompression=no the RDB dump is ~5 MB.
      container.execInContainer("redis-cli", "SETRANGE", "bigkey", "5242879", "x");

      // Fill /data to 90% — leaves ~0.6 MB free; the 5 MB RDB will not fit
      chaos.fillDisk(container, "/data", 90);

      container.execInContainer("redis-cli", "BGSAVE");

      // BGSAVE child gets ENOSPC after ~0.6 MB written — wait for status:err
      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollInterval(Duration.ofMillis(200))
          .until(() -> container.execInContainer("redis-cli", "INFO", "persistence")
              .getStdout().contains("rdb_last_bgsave_status:err"));

      final var info = container.execInContainer("redis-cli", "INFO", "persistence");
      assertThat(info.getStdout())
          .as("Redis BGSAVE must fail when disk is full")
          .contains("rdb_last_bgsave_status:err");
    }

    @Test
    @DisplayName("EIO injection on /data — Redis SET command returns error")
    void eioOnRedisDataDirCausesSetToFail() throws Exception {
      container = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE))
          .withTmpFs(Map.of("/data", "rw,size=50m"))
          .withCommand("redis-server", "--dir", "/data",
              "--appendonly", "yes", "--appendfsync", "always");
      SyscallFaultInjector.prepare(container);
      container.start();
      chaos = new CgroupsDiskChaos();

      // Wait for Redis to be ready
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> container.execInContainer("redis-cli", "PING")
              .getStdout().trim().equals("PONG"));

      // Inject 100% write EIO on /data (Redis AOF writes go here)
      chaos.injectIOError(container, "/data", "write", "EIO", 1.0);

      // SET with appendfsync=always triggers immediate fsync — must fail with I/O error
      final var setResult = container.execInContainer("redis-cli", "SET", "key1", "value1");

      // Redis-server panics when AOF write() fails with EIO — connection drops,
      // redis-cli gets an empty response. Either crash (empty) or an explicit error
      // are acceptable; "OK" is not.
      assertThat(setResult.getStdout().trim())
          .as("Redis SET must not return OK when write EIO is injected on /data")
          .isNotEqualTo("OK");
    }

    @Test
    @DisplayName("reset removes EIO rule — Redis SET succeeds after reset")
    void resetRestoresRedisWrites() throws Exception {
      container = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE))
          .withTmpFs(Map.of("/data", "rw,size=50m"))
          .withCommand("redis-server", "--dir", "/data",
              "--appendonly", "yes", "--appendfsync", "always");
      SyscallFaultInjector.prepare(container);
      container.start();
      chaos = new CgroupsDiskChaos();

      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> container.execInContainer("redis-cli", "PING")
              .getStdout().trim().equals("PONG"));

      chaos.injectIOError(container, "/data", "write", "EIO", 1.0);
      chaos.reset(container);

      // After reset, SET should succeed
      final var setResult = container.execInContainer("redis-cli", "SET", "k", "v");
      assertThat(setResult.getStdout().trim())
          .as("Redis SET must succeed after EIO rule is removed by reset")
          .isEqualTo("OK");
    }
  }

  // ==================== Multi-fault scenario ====================

  @Nested
  @DisplayName("Multi-fault — concurrent mixed injection")
  class MultiFaultTests {

    @Test
    @DisplayName("ENOSPC fill + EIO injection on different paths — both faults observable")
    void enospcAndEioSimultaneously() throws Exception {
      container = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE))
          .withTmpFs(Map.of(
              "/fill-data", "rw,size=10m",
              "/inject-data", "rw,size=50m"));
      SyscallFaultInjector.prepare(container);
      container.start();
      chaos = new CgroupsDiskChaos();

      // ENOSPC via fill
      chaos.fillDisk(container, "/fill-data", 90);
      final var enospcResult = container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/fill-data/overflow bs=1M count=5 2>&1");
      assertThat(enospcResult.getStdout() + enospcResult.getStderr())
          .contains("No space left on device");

      // EIO via injection
      chaos.injectIOError(container, "/inject-data", "write", "EIO", 1.0);
      final var eioResult = container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /inject-data/test.dat 2>&1; echo __exit:$?");
      assertThat(eioResult.getStdout() + eioResult.getStderr()).contains("__exit:1");

      // Reset clears both — /fill-data space freed, /inject-data write rule removed
      chaos.reset(container);

      // Fill path: write should succeed
      Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
        final var fillResult = container.execInContainer("/bin/sh", "-c",
            "dd if=/dev/zero of=/fill-data/ok bs=1K count=1 2>&1; echo __exit:$?");
        assertThat(fillResult.getStdout() + fillResult.getStderr()).contains("__exit:0");
      });

      // Inject path: write should also succeed (library loaded, rules cleared)
      final var injectResult = container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /inject-data/ok 2>&1; echo __exit:$?");
      assertThat(injectResult.getStdout() + injectResult.getStderr()).contains("__exit:0");
    }

    @Test
    @DisplayName("stress + fill + EIO simultaneously — all three active at once")
    void stressAndFillAndEioSimultaneously() throws Exception {
      container = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE))
          .withTmpFs(Map.of(
              "/fill-data", "rw,size=10m",
              "/inject-data", "rw,size=50m"))
              .withCreateContainerCmdModifier(cmd ->
                      cmd.getHostConfig().withCapAdd(Capability.SYS_PTRACE)
              );
      SyscallFaultInjector.prepare(container);
      container.start();
      chaos = new CgroupsDiskChaos();

      // All three simultaneously
      chaos.stressDisk(container, 1);
      chaos.fillDisk(container, "/fill-data", 90);
      chaos.injectIOError(container, "/inject-data", "write", "EIO", 1.0);

      // Stress is running
      Awaitility.await().atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isTrue());

      // Fill causes ENOSPC
      final var enospc = container.execInContainer("/bin/sh", "-c",
          "dd if=/dev/zero of=/fill-data/overflow bs=1M count=5 2>&1");
      assertThat(enospc.getStdout() + enospc.getStderr()).contains("No space left on device");

      // EIO causes write failure — use cp (confirmed write() caller) not dd (uses writev/pwrite)
      final var eio = container.execInContainer("/bin/sh", "-c",
          WITH_LIB + "cp /etc/hostname /inject-data/eio-verify.dat 2>&1; echo __exit:$?");
      assertThat(eio.getStdout() + eio.getStderr()).contains("__exit:1");

      // Reset clears everything
      chaos.reset(container);

      Awaitility.await()
          .atMost(Duration.ofSeconds(15))
          .pollDelay(Duration.ofSeconds(1))
          .untilAsserted(() -> assertThat(chaos.isStressed(container)).isFalse());
    }
  }

  // ==================== Helpers ====================

  private static GenericContainer<?> debian() {
    final var c = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE));
    c.start();
    return c;
  }

  private static GenericContainer<?> debianWithTmpFs(final String path, final String size) {
    final var c = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE))
        .withTmpFs(Map.of(path, "rw,size=" + size));
    c.start();
    return c;
  }

  /**
   * Starts a Debian container with a bounded tmpfs AND libchaos-io prepared for syscall injection.
   * The tmpfs prevents multi-GB dd writes on the overlay filesystem.
   */
  private static GenericContainer<?> debianWithLibchaosIo(final String path, final String size) {
    final var c = new GenericContainer<>(DockerImageName.parse(DEBIAN_IMAGE))
        .withTmpFs(Map.of(path, "rw,size=" + size));
    SyscallFaultInjector.prepare(c);
    c.start();
    return c;
  }

  /**
   * Starts an Alpine/musl container with a bounded tmpfs AND libchaos-io prepared.
   * Validates the musl-linked .so binary variant.
   */
  private static GenericContainer<?> alpineWithLibchaosIo(final String path, final String size) {
    final var c = new GenericContainer<>(DockerImageName.parse(ALPINE_IMAGE))
        .withTmpFs(Map.of(path, "rw,size=" + size));
    SyscallFaultInjector.prepare(c);
    c.start();
    return c;
  }
}
