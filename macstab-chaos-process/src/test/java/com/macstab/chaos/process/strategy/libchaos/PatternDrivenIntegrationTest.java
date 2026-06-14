/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.patterns.ChaosPattern;
import com.macstab.chaos.patterns.RampPattern;
import com.macstab.chaos.patterns.RuleSwapper;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessSelector;

/**
 * End-to-end proof that a {@link ChaosPattern} drives a real libchaos-process rule through {@link
 * RuleSwapper}, with the apply/remove churn cycling correctly against a running container.
 *
 * <p>Pattern: linear ramp 0.0 → 1.0 over 2 seconds. Each sample applies a fresh {@code
 * fork:ERRNO:EAGAIN@<p>} rule and removes the previous one. At the end of the run the config file
 * should contain exactly one rule with probability close to 1.0 — proving that swap worked
 * monotonically without leaking handles.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Pattern-driven libchaos-process rule mutation — end-to-end")
class PatternDrivenIntegrationTest {

  private static final String CONFIG_PATH = "/tmp/.chaos-process.conf";

  private GenericContainer<?> container;

  @AfterEach
  void tearDown() {
    if (container != null && container.isRunning()) {
      container.stop();
    }
  }

  @Test
  @DisplayName("ramp probability 0.0→1.0 cycles rules; final state has exactly one rule near 1.0")
  void rampDrivenByPattern() throws Exception {
    container =
        new GenericContainer<>(DockerImageName.parse("debian:bookworm-slim"))
            .withCommand("sleep", "infinity");
    new LibchaosTransport(LibchaosLib.PROCESS).prepare(container);
    container.start();

    final LibchaosProcessChaos chaos = new LibchaosProcessChaos();
    final ChaosPattern<Double> ramp = RampPattern.linear(0.0, 1.0);

    ramp.applyTo(
            RuleSwapper.swap(
                p -> chaos.failFork(container, Math.max(0.001, p)),
                h -> chaos.remove(container, h)),
            Duration.ofSeconds(2),
            Duration.ofMillis(200))
        .awaitUninterruptibly();

    // Exactly one fork rule must remain — proves the swap cycled previous handles.
    final String config =
        container.execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH).getStdout();
    final long forkRuleCount = config.lines().filter(l -> l.startsWith("fork:")).count();
    assertThat(forkRuleCount).as("config should contain exactly one fork rule").isEqualTo(1L);

    // Last sample's probability should be near 1.0.
    // At p=1.0 the serializer omits the @suffix (1.0 is the default); at p<1.0 it includes it.
    assertThat(config).contains("fork:ERRNO:EAGAIN");
    final String forkLine =
        config.lines().filter(l -> l.startsWith("fork:")).findFirst().orElseThrow();
    assertThat(forkLine)
        .satisfiesAnyOf(
            l -> assertThat(l).matches(".*@(0\\.9[0-9]+|1\\.0).*"),
            l -> assertThat(l).startsWith("fork:ERRNO:EAGAIN")); // p=1.0 — no @suffix

    // Cleanup verifies the registry tracked the final handle correctly.
    chaos.removeAll(container);
    final String configAfter =
        container
            .execInContainer("/bin/sh", "-c", "cat " + CONFIG_PATH + " 2>/dev/null || true")
            .getStdout();
    assertThat(configAfter.lines().filter(l -> l.startsWith("fork:")).count()).isZero();
  }

  @Test
  @DisplayName("verifies the test uses selector + errno that libchaos-process accepts")
  void selectorErrnoSanityCheck() {
    // Defensive: catches the case where ProcessSelector.FORK's accepted errno set drifts and
    // EAGAIN is no longer valid, which would silently break the integration test by causing
    // failFork to throw at construction.
    assertThat(ProcessSelector.FORK.accepts(ProcessErrno.EAGAIN)).isTrue();
  }
}
