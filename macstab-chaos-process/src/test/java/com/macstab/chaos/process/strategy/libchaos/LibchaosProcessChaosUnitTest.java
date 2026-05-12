/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.strategy.libchaos;

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
import com.macstab.chaos.core.model.Signal;
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.process.api.RuleHandle;
import com.macstab.chaos.process.model.ProcessErrno;
import com.macstab.chaos.process.model.ProcessRule;
import com.macstab.chaos.process.model.ProcessSelector;

@DisplayName("LibchaosProcessChaos (unit)")
class LibchaosProcessChaosUnitTest {

  private LibchaosTransport transport;
  private GenericContainer<?> container;
  private LibchaosProcessChaos chaos;

  @BeforeEach
  void setUp() {
    transport = mock(LibchaosTransport.class);
    container = mock(GenericContainer.class);
    chaos = new LibchaosProcessChaos(transport);
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
                      container,
                      ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN)))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("process")
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
      assertThatThrownBy(() -> chaos.failThreadCreation(container, 0.5))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.exhaustThreadPool(container, 128))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }
  }

  @Nested
  @DisplayName("portable verbs route via ChaosUnsupportedOperationException")
  class PortableUnsupported {
    @Test
    void kill() {
      assertThatThrownBy(() -> chaos.kill(container, "nginx", Signal.SIGTERM))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void pause() {
      assertThatThrownBy(() -> chaos.pause(container, "nginx", Duration.ofSeconds(1)))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void limitProcesses() {
      assertThatThrownBy(() -> chaos.limitProcesses(container, 100))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void listProcesses() {
      assertThatThrownBy(() -> chaos.listProcesses(container))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("apply / applyAll / remove plumbing")
  class Plumbing {
    @Test
    void apply() {
      final RuleHandle h =
          chaos.apply(container, ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN));
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
              ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN),
              ProcessRule.latency(ProcessSelector.EXECVE, Duration.ofMillis(50))));
      verify(transport, times(2)).addRule(eq(container), anyString(), anyString());
    }

    @Test
    void applyAllNullElement() {
      final java.util.ArrayList<ProcessRule> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> chaos.applyAll(container, withNull))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void removeApplied() {
      final RuleHandle h =
          chaos.apply(container, ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN));
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
          chaos.apply(container, ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN));
      final RuleHandle h2 =
          chaos.apply(
              container, ProcessRule.errno(ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN));
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
      chaos.errno(container, ProcessSelector.FORK, ProcessErrno.EAGAIN, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("fork:ERRNO:EAGAIN@0.5"));
    }

    @Test
    void latencyEscapeHatch() {
      chaos.latency(container, ProcessSelector.WAITPID, Duration.ofMillis(20));
      verify(transport).addRule(eq(container), anyString(), contains("waitpid:LATENCY:20"));
    }

    @Test
    void failAfterEscapeHatch() {
      chaos.failAfter(container, ProcessSelector.PTHREAD_CREATE, ProcessErrno.EAGAIN, 256);
      verify(transport)
          .addRule(eq(container), anyString(), contains("pthread_create:FAIL_AFTER:EAGAIN,256"));
    }
  }

  @Nested
  @DisplayName("thread creation verbs")
  class Threads {
    @Test
    void failThreadCreation() {
      chaos.failThreadCreation(container, 0.01);
      verify(transport)
          .addRule(eq(container), anyString(), contains("pthread_create:ERRNO:EAGAIN@0.01"));
    }

    @Test
    void failThreadCreationCustomErrno() {
      chaos.failThreadCreation(container, ProcessErrno.EPERM, 0.5);
      verify(transport)
          .addRule(eq(container), anyString(), contains("pthread_create:ERRNO:EPERM@0.5"));
    }

    @Test
    @DisplayName("exhaustThreadPool → FAIL_AFTER:EAGAIN,N")
    void exhaustThreadPool() {
      chaos.exhaustThreadPool(container, 128);
      verify(transport)
          .addRule(eq(container), anyString(), contains("pthread_create:FAIL_AFTER:EAGAIN,128"));
    }

    @Test
    void slowThreadCreation() {
      chaos.slowThreadCreation(container, Duration.ofMillis(100));
      verify(transport).addRule(eq(container), anyString(), contains("pthread_create:LATENCY:100"));
    }
  }

  @Nested
  @DisplayName("fork verbs")
  class Fork {
    @Test
    void failFork() {
      chaos.failFork(container, 0.01);
      verify(transport).addRule(eq(container), anyString(), contains("fork:ERRNO:EAGAIN@0.01"));
    }

    @Test
    void failForkCustom() {
      chaos.failFork(container, ProcessErrno.ENOMEM, 0.1);
      verify(transport).addRule(eq(container), anyString(), contains("fork:ERRNO:ENOMEM@0.1"));
    }

    @Test
    @DisplayName("exhaustProcessLimit → FAIL_AFTER:EAGAIN,N")
    void exhaustProcessLimit() {
      chaos.exhaustProcessLimit(container, 64);
      verify(transport).addRule(eq(container), anyString(), contains("fork:FAIL_AFTER:EAGAIN,64"));
    }

    @Test
    void slowFork() {
      chaos.slowFork(container, Duration.ofMillis(50));
      verify(transport).addRule(eq(container), anyString(), contains("fork:LATENCY:50"));
    }
  }

  @Nested
  @DisplayName("spawn verbs")
  class Spawn {
    @Test
    void failSpawn() {
      chaos.failSpawn(container, 0.5);
      verify(transport)
          .addRule(eq(container), anyString(), contains("posix_spawn:ERRNO:ENOENT@0.5"));
    }

    @Test
    void failSpawnCustom() {
      chaos.failSpawn(container, ProcessErrno.EAGAIN, 0.1);
      verify(transport)
          .addRule(eq(container), anyString(), contains("posix_spawn:ERRNO:EAGAIN@0.1"));
    }

    @Test
    void failSpawnByPath() {
      chaos.failSpawnByPath(container, 0.5);
      verify(transport)
          .addRule(eq(container), anyString(), contains("posix_spawnp:ERRNO:ENOENT@0.5"));
    }

    @Test
    void slowSpawn() {
      chaos.slowSpawn(container, Duration.ofMillis(20));
      verify(transport).addRule(eq(container), anyString(), contains("posix_spawn:LATENCY:20"));
    }
  }

  @Nested
  @DisplayName("exec verbs — full 8-errno palette")
  class Exec {
    @Test
    void failExec() {
      chaos.failExec(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("execve:ERRNO:ENOENT@0.5"));
    }

    @Test
    void failExecCustom() {
      chaos.failExec(container, ProcessErrno.ETXTBSY, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("execve:ERRNO:ETXTBSY@0.5"));
    }

    @Test
    void failExecPermission() {
      chaos.failExecPermission(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("execve:ERRNO:EACCES@0.5"));
    }

    @Test
    void failExecMissingBinary() {
      chaos.failExecMissingBinary(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("execve:ERRNO:ENOENT@0.5"));
    }

    @Test
    void failExecTooLarge() {
      chaos.failExecTooLarge(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("execve:ERRNO:E2BIG@0.5"));
    }

    @Test
    void failExecBadFormat() {
      chaos.failExecBadFormat(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("execve:ERRNO:ENOEXEC@0.5"));
    }

    @Test
    void failExecRelative() {
      chaos.failExecRelative(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("execveat:ERRNO:ENOENT@0.5"));
    }

    @Test
    void slowExec() {
      chaos.slowExec(container, Duration.ofMillis(50));
      verify(transport).addRule(eq(container), anyString(), contains("execve:LATENCY:50"));
    }
  }

  @Nested
  @DisplayName("wait verbs — EINTR / ECHILD")
  class Wait {
    @Test
    void failWait() {
      chaos.failWait(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("waitpid:ERRNO:ECHILD@0.5"));
    }

    @Test
    void failWaitCustom() {
      chaos.failWait(container, ProcessErrno.EINVAL, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("waitpid:ERRNO:EINVAL@0.5"));
    }

    @Test
    @DisplayName("signalInterruptWait → waitpid:ERRNO:EINTR")
    void signalInterruptWait() {
      chaos.signalInterruptWait(container, 0.1);
      verify(transport).addRule(eq(container), anyString(), contains("waitpid:ERRNO:EINTR@0.1"));
    }

    @Test
    @DisplayName("phantomWait → waitpid:ERRNO:ECHILD alias")
    void phantomWait() {
      chaos.phantomWait(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("waitpid:ERRNO:ECHILD@0.5"));
    }

    @Test
    void slowWait() {
      chaos.slowWait(container, Duration.ofMillis(10));
      verify(transport).addRule(eq(container), anyString(), contains("waitpid:LATENCY:10"));
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
          chaos.apply(container, ProcessRule.errno(ProcessSelector.FORK, ProcessErrno.EAGAIN));
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
