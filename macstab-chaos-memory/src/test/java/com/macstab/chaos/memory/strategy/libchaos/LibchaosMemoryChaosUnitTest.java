/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.memory.strategy.libchaos;

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
import com.macstab.chaos.core.syscall.LibchaosTransport;
import com.macstab.chaos.memory.api.RuleHandle;
import com.macstab.chaos.memory.model.MemoryRule;
import com.macstab.chaos.memory.model.MemorySelector;
import com.macstab.chaos.memory.model.MmapErrno;

@DisplayName("LibchaosMemoryChaos (unit)")
class LibchaosMemoryChaosUnitTest {

  private LibchaosTransport transport;
  private GenericContainer<?> container;
  private LibchaosMemoryChaos chaos;

  @BeforeEach
  void setUp() {
    transport = mock(LibchaosTransport.class);
    container = mock(GenericContainer.class);
    chaos = new LibchaosMemoryChaos(transport);
    when(transport.isActive(container)).thenReturn(true);
  }

  @Nested
  @DisplayName("supports")
  class Supports {
    @Test
    @DisplayName("delegates to transport.isActive")
    void delegates() {
      assertThat(chaos.supports(container)).isTrue();
      when(transport.isActive(container)).thenReturn(false);
      assertThat(chaos.supports(container)).isFalse();
    }

    @Test
    @DisplayName("returns false on probe failure")
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
    @DisplayName("apply throws LibchaosNotPreparedException")
    void apply() {
      assertThatThrownBy(
              () ->
                  chaos.apply(
                      container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM, 0.1)))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("memory")
          .hasMessageContaining("@SyscallLevelChaos");
    }

    @Test
    @DisplayName("applyAll / remove / removeAll throw")
    void otherGenericVerbs() {
      assertThatThrownBy(() -> chaos.applyAll(container, List.of()))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.remove(container, new RuleHandle("r1")))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.removeAll(container))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    @DisplayName("typed verbs throw")
    void typed() {
      assertThatThrownBy(() -> chaos.failHeapAllocation(container, 0.001))
          .isInstanceOf(LibchaosNotPreparedException.class);
      assertThatThrownBy(() -> chaos.failJitCompilation(container, 0.5))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }
  }

  @Nested
  @DisplayName("portable verbs route via ChaosUnsupportedOperationException")
  class PortableUnsupported {
    @Test
    void setLimit() {
      assertThatThrownBy(() -> chaos.setLimit(container, "512M"))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void setPressure() {
      assertThatThrownBy(() -> chaos.setPressure(container, "400M"))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void stress() {
      assertThatThrownBy(() -> chaos.stress(container, "300M"))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void getCurrentUsage() {
      assertThatThrownBy(() -> chaos.getCurrentUsage(container))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }

    @Test
    void getPressure() {
      assertThatThrownBy(() -> chaos.getPressure(container))
          .isInstanceOf(ChaosUnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("apply / applyAll / remove plumbing")
  class Plumbing {
    @Test
    @DisplayName("apply emits one addRule and returns a handle")
    void apply() {
      final RuleHandle h =
          chaos.apply(container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM));
      assertThat(h.owner()).matches("r[0-9]+");
      verify(transport).addRule(eq(container), eq(h.owner()), anyString());
    }

    @Test
    @DisplayName("applyAll empty list — no exec")
    void empty() {
      assertThat(chaos.applyAll(container, List.of())).isEmpty();
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("applyAll N → N addRule calls")
    void multi() {
      chaos.applyAll(
          container,
          List.of(
              MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM),
              MemoryRule.latency(MemorySelector.MMAP_FILE, Duration.ofMillis(20))));
      verify(transport, times(2)).addRule(eq(container), anyString(), anyString());
    }

    @Test
    @DisplayName("applyAll with a null rule in the list is rejected")
    void applyAllNullElement() {
      final java.util.ArrayList<MemoryRule> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> chaos.applyAll(container, withNull))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("remove of applied handle calls transport.removeRules")
    void removeApplied() {
      final RuleHandle h =
          chaos.apply(container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM));
      chaos.remove(container, h);
      verify(transport).removeRules(container, h.owner());
    }

    @Test
    @DisplayName("remove of unknown handle is silent")
    void removeUnknown() {
      chaos.remove(container, new RuleHandle("r_missing"));
      verify(transport, never()).removeRules(any(), anyString());
    }

    @Test
    @DisplayName("removeAll wipes every applied rule")
    void removeAll() {
      final RuleHandle h1 =
          chaos.apply(container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM));
      final RuleHandle h2 =
          chaos.apply(container, MemoryRule.errno(MemorySelector.MMAP_FILE, MmapErrno.ENOMEM));
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
      chaos.errno(container, MemorySelector.MMAP, MmapErrno.EACCES, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("mmap:ERRNO:EACCES@0.5"));
    }

    @Test
    void latencyEscapeHatch() {
      chaos.latency(container, MemorySelector.MADVISE, Duration.ofMillis(10));
      verify(transport).addRule(eq(container), anyString(), contains("madvise:LATENCY:10"));
    }
  }

  @Nested
  @DisplayName("heap / allocation verbs")
  class Heap {
    @Test
    void failHeapAllocation() {
      chaos.failHeapAllocation(container, 0.001);
      verify(transport)
          .addRule(eq(container), anyString(), contains("mmap/anon:ERRNO:ENOMEM@0.001"));
    }

    @Test
    void failLargeAllocation() {
      chaos.failLargeAllocation(container, 0.01);
      verify(transport).addRule(eq(container), anyString(), contains("mmap:ERRNO:ENOMEM@0.01"));
    }

    @Test
    void simulateOomKiller() {
      chaos.simulateOomKiller(container, 0.05);
      verify(transport).addRule(eq(container), anyString(), contains("mmap:ERRNO:ENOMEM@0.05"));
    }

    @Test
    void simulateMemoryPressure() {
      chaos.simulateMemoryPressure(container, 0.001);
      verify(transport).addRule(eq(container), anyString(), contains("mmap:ERRNO:ENOMEM@0.001"));
    }

    @Test
    void slowHeapAllocation() {
      chaos.slowHeapAllocation(container, Duration.ofMillis(15));
      verify(transport).addRule(eq(container), anyString(), contains("mmap/anon:LATENCY:15"));
    }
  }

  @Nested
  @DisplayName("file mapping / dlopen verbs")
  class FileMapping {
    @Test
    void failFileMappingDefault() {
      chaos.failFileMapping(container, 0.01);
      verify(transport)
          .addRule(eq(container), anyString(), contains("mmap/file:ERRNO:ENOMEM@0.01"));
    }

    @Test
    void failFileMappingCustomErrno() {
      chaos.failFileMapping(container, MmapErrno.EACCES, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("mmap/file:ERRNO:EACCES@0.5"));
    }

    @Test
    void failLibraryLoad() {
      chaos.failLibraryLoad(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("mmap/file:ERRNO:ENOMEM@0.5"));
    }

    @Test
    void failPluginLoad() {
      chaos.failPluginLoad(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("mmap/file:ERRNO:ENOMEM@0.5"));
    }

    @Test
    void slowFileMapping() {
      chaos.slowFileMapping(container, Duration.ofMillis(50));
      verify(transport).addRule(eq(container), anyString(), contains("mmap/file:LATENCY:50"));
    }
  }

  @Nested
  @DisplayName("thread / stack verbs")
  class ThreadStack {
    @Test
    void failThreadCreation() {
      chaos.failThreadCreation(container, 0.01);
      verify(transport)
          .addRule(eq(container), anyString(), contains("mmap/anon:ERRNO:ENOMEM@0.01"));
    }

    @Test
    void failGuardPageSetup() {
      chaos.failGuardPageSetup(container, 0.01);
      verify(transport).addRule(eq(container), anyString(), contains("mprotect:ERRNO:ENOMEM@0.01"));
    }
  }

  @Nested
  @DisplayName("page permission (mprotect) verbs")
  class Mprotect {
    @Test
    void failPermissionChange() {
      chaos.failPermissionChange(container, MmapErrno.EACCES, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("mprotect:ERRNO:EACCES@0.5"));
    }

    @Test
    void failJitCompilation() {
      chaos.failJitCompilation(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("mprotect:ERRNO:EACCES@0.5"));
    }

    @Test
    void slowPermissionChange() {
      chaos.slowPermissionChange(container, Duration.ofMillis(10));
      verify(transport).addRule(eq(container), anyString(), contains("mprotect:LATENCY:10"));
    }
  }

  @Nested
  @DisplayName("kernel hints (madvise) verbs")
  class Madvise {
    @Test
    void failMadvise() {
      chaos.failMadvise(container, MmapErrno.EAGAIN, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("madvise:ERRNO:EAGAIN@0.5"));
    }

    @Test
    void failHugepageHint() {
      chaos.failHugepageHint(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("madvise:ERRNO:EINVAL@0.5"));
    }

    @Test
    void failPagePurge() {
      chaos.failPagePurge(container, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("madvise:ERRNO:ENOMEM@0.5"));
    }

    @Test
    void slowMadvise() {
      chaos.slowMadvise(container, Duration.ofMillis(5));
      verify(transport).addRule(eq(container), anyString(), contains("madvise:LATENCY:5"));
    }
  }

  @Nested
  @DisplayName("cleanup verbs")
  class Cleanup {
    @Test
    void failUnmap() {
      chaos.failUnmap(container, 0.01);
      verify(transport).addRule(eq(container), anyString(), contains("munmap:ERRNO:EINVAL@0.01"));
    }

    @Test
    void simulateLeak() {
      chaos.simulateLeak(container, 0.01);
      verify(transport).addRule(eq(container), anyString(), contains("munmap:ERRNO:EINVAL@0.01"));
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
          chaos.apply(container, MemoryRule.errno(MemorySelector.MMAP_ANON, MmapErrno.ENOMEM));
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
