/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;

@DisplayName("ProcessRuleSerializer (unit)")
class ProcessRuleSerializerTest {

  @Test
  void errnoOnPthreadCreate() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN)))
        .isEqualTo("pthread_create:ERRNO:EAGAIN");
  }

  @Test
  void errnoWithProbability() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN, 0.01)))
        .isEqualTo("fork:ERRNO:EAGAIN@0.01");
  }

  @Test
  void latencyOnExecve() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.latency(ProcessSelector.EXECVE, Duration.ofMillis(50))))
        .isEqualTo("execve:LATENCY:50");
  }

  @Test
  @DisplayName("FAIL_AFTER renders selector:FAIL_AFTER:ERRNO,N")
  void failAfterPthread() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.failAfter(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN, 128L)))
        .isEqualTo("pthread_create:FAIL_AFTER:EAGAIN,128");
  }

  @Test
  void failAfterFork() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.failAfter(ProcessSelector.FORK, ProcessErrno.EAGAIN, 0L)))
        .isEqualTo("fork:FAIL_AFTER:EAGAIN,0");
  }

  @Test
  void wildcardErrno() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.errno(ProcessSelector.WILDCARD, ProcessErrno.EINTR, 0.1)))
        .isEqualTo("*:ERRNO:EINTR@0.1");
  }

  @Test
  void waitpidEintr() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.errno(ProcessSelector.WAITPID, ProcessErrno.EINTR, 0.05)))
        .isEqualTo("waitpid:ERRNO:EINTR@0.05");
  }

  @Test
  void posixSpawnpEnoent() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.errno(ProcessSelector.POSIX_SPAWNP, ProcessErrno.ENOENT)))
        .isEqualTo("posix_spawnp:ERRNO:ENOENT");
  }

  @Test
  void execveatLatency() {
    assertThat(
            ProcessRuleSerializer.serialize(
                ProcessRule.latency(ProcessSelector.EXECVEAT, Duration.ofMillis(25))))
        .isEqualTo("execveat:LATENCY:25");
  }

  @Test
  void nullRejected() {
    assertThatThrownBy(() -> ProcessRuleSerializer.serialize(null))
        .isInstanceOf(NullPointerException.class);
  }
}
