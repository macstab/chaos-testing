/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.libchaos;

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
import com.macstab.chaos.filesystem.api.RuleHandle;
import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

@DisplayName("LibchaosIoFilesystemChaos (unit)")
class LibchaosIoFilesystemChaosUnitTest {

  private LibchaosTransport transport;
  private GenericContainer<?> container;
  private LibchaosIoFilesystemChaos chaos;

  @BeforeEach
  void setUp() {
    transport = mock(LibchaosTransport.class);
    container = mock(GenericContainer.class);
    chaos = new LibchaosIoFilesystemChaos(transport);
    when(transport.isActive(container)).thenReturn(true);
  }

  // ==================== supports ====================

  @Nested
  @DisplayName("supports")
  class Supports {

    @Test
    @DisplayName("delegates to transport.isActive")
    void delegatesActive() {
      assertThat(chaos.supports(container)).isTrue();
      when(transport.isActive(container)).thenReturn(false);
      assertThat(chaos.supports(container)).isFalse();
    }

    @Test
    @DisplayName("returns false on probe failure (defensive)")
    void probeFailure() {
      when(transport.isActive(container)).thenThrow(new RuntimeException("docker died"));
      assertThat(chaos.supports(container)).isFalse();
    }

    @Test
    @DisplayName("null container rejected")
    void nullContainer() {
      assertThatThrownBy(() -> chaos.supports(null)).isInstanceOf(NullPointerException.class);
    }
  }

  // ==================== requirePrepared gate ====================

  @Nested
  @DisplayName("requirePrepared gate")
  class RequirePrepared {

    @BeforeEach
    void setNotActive() {
      when(transport.isActive(container)).thenReturn(false);
    }

    @Test
    @DisplayName("apply throws LibchaosNotPreparedException")
    void applyThrows() {
      final IoRule r = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 1.0);
      assertThatThrownBy(() -> chaos.apply(container, r))
          .isInstanceOf(LibchaosNotPreparedException.class)
          .hasMessageContaining("io")
          .hasMessageContaining("@SyscallLevelChaos");
    }

    @Test
    @DisplayName("applyAll throws LibchaosNotPreparedException")
    void applyAllThrows() {
      assertThatThrownBy(() -> chaos.applyAll(container, List.of()))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    @DisplayName("remove throws LibchaosNotPreparedException")
    void removeThrows() {
      assertThatThrownBy(() -> chaos.remove(container, new RuleHandle("r1")))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    @DisplayName("removeAll throws LibchaosNotPreparedException")
    void removeAllThrows() {
      assertThatThrownBy(() -> chaos.removeAll(container))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }

    @Test
    @DisplayName("failWrite (convenience) throws LibchaosNotPreparedException")
    void convenienceThrows() {
      assertThatThrownBy(() -> chaos.failWrite(container, PathPrefix.path("/data"), Errno.EIO, 1.0))
          .isInstanceOf(LibchaosNotPreparedException.class);
    }
  }

  // ==================== unsupported portable verbs ====================

  @Nested
  @DisplayName("portable verbs route via UnsupportedOperationException")
  class PortableVerbsUnsupported {

    @Test
    @DisplayName("fillDisk throws ChaosUnsupportedOperationException")
    void fillDiskUnsupported() {
      assertThatThrownBy(() -> chaos.fillDisk(container, "10M"))
          .isInstanceOf(ChaosUnsupportedOperationException.class)
          .hasMessageContaining("shell strategy");
    }

    @Test
    @DisplayName("injectPermissionErrors throws ChaosUnsupportedOperationException")
    void permissionUnsupported() {
      assertThatThrownBy(() -> chaos.injectPermissionErrors(container, "/data", 1.0))
          .isInstanceOf(ChaosUnsupportedOperationException.class)
          .hasMessageContaining("shell strategy");
    }
  }

  // ==================== apply / remove (generic) ====================

  @Nested
  @DisplayName("apply")
  class Apply {

    @Test
    @DisplayName("calls transport.addRule with serialized rule and returns a handle")
    void appliesRule() {
      final IoRule r = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 0.5);
      final RuleHandle handle = chaos.apply(container, r);
      assertThat(handle.owner()).matches("r[0-9]+");
      verify(transport).addRule(eq(container), eq(handle.owner()), contains("/data:write:EIO:0.5"));
    }

    @Test
    @DisplayName("each apply mints a fresh handle")
    void freshHandles() {
      final IoRule r = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 0.5);
      final RuleHandle h1 = chaos.apply(container, r);
      final RuleHandle h2 = chaos.apply(container, r);
      assertThat(h1).isNotEqualTo(h2);
    }
  }

  @Nested
  @DisplayName("applyAll")
  class ApplyAll {

    @Test
    @DisplayName("empty list returns empty list, no exec")
    void emptyList() {
      final List<RuleHandle> handles = chaos.applyAll(container, List.of());
      assertThat(handles).isEmpty();
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("non-empty list emits one addRule per rule, returns handles in order")
    void nonEmpty() {
      final IoRule r1 = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 0.5);
      final IoRule r2 =
          IoRule.errno(PathPrefix.path("/logs"), IoOperation.WRITE, Errno.ENOSPC, 1.0);
      final List<RuleHandle> handles = chaos.applyAll(container, List.of(r1, r2));
      assertThat(handles).hasSize(2);
      verify(transport, times(2)).addRule(eq(container), anyString(), anyString());
    }

    @Test
    @DisplayName("null rule inside list rejected")
    void nullRuleInList() {
      final List<IoRule> withNull = new java.util.ArrayList<>();
      withNull.add(null);
      assertThatThrownBy(() -> chaos.applyAll(container, withNull))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("remove / removeAll")
  class RemoveRules {

    @Test
    @DisplayName("remove of applied handle calls transport.removeRules")
    void removeApplied() {
      final IoRule r = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 1.0);
      final RuleHandle h = chaos.apply(container, r);

      chaos.remove(container, h);

      verify(transport).removeRules(container, h.owner());
    }

    @Test
    @DisplayName("remove of unknown handle is silent (idempotent)")
    void removeUnknown() {
      chaos.remove(container, new RuleHandle("r_does_not_exist"));
      verify(transport, never()).removeRules(any(), anyString());
    }

    @Test
    @DisplayName("removeAll cleans every applied rule")
    void removeAllApplied() {
      final IoRule r = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 1.0);
      final RuleHandle h1 = chaos.apply(container, r);
      final RuleHandle h2 = chaos.apply(container, r);

      chaos.removeAll(container);

      verify(transport).removeRules(container, h1.owner());
      verify(transport).removeRules(container, h2.owner());
    }
  }

  // ==================== convenience verbs ====================

  @Nested
  @DisplayName("convenience verbs render expected wire form")
  class ConvenienceVerbs {

    @Test
    @DisplayName("failOpen → open:ERRNO_NAME:probability")
    void failOpen() {
      chaos.failOpen(container, PathPrefix.path("/data"), Errno.ENOENT, 1.0);
      verify(transport).addRule(eq(container), anyString(), contains("/data:open:ENOENT:1.0"));
    }

    @Test
    @DisplayName("failWrite → write:ERRNO_NAME:probability")
    void failWrite() {
      chaos.failWrite(container, PathPrefix.path("/data"), Errno.EIO, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("/data:write:EIO:0.5"));
    }

    @Test
    @DisplayName("failRead → read:ERRNO_NAME:probability")
    void failRead() {
      chaos.failRead(container, PathPrefix.path("/data"), Errno.EIO, 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("/data:read:EIO:0.5"));
    }

    @Test
    @DisplayName("exhaustFds → *:open:EMFILE:probability")
    void exhaustFds() {
      chaos.exhaustFds(container, 0.05);
      verify(transport).addRule(eq(container), anyString(), contains("*:open:EMFILE:0.05"));
    }

    @Test
    @DisplayName("makeReadOnly → write:EROFS")
    void makeReadOnly() {
      chaos.makeReadOnly(container, PathPrefix.path("/data"), 1.0);
      verify(transport).addRule(eq(container), anyString(), contains("/data:write:EROFS:1.0"));
    }

    @Test
    @DisplayName("fillQuota → write:EDQUOT")
    void fillQuota() {
      chaos.fillQuota(container, PathPrefix.path("/data"), 1.0);
      verify(transport).addRule(eq(container), anyString(), contains("/data:write:EDQUOT:1.0"));
    }

    @Test
    @DisplayName("tornWrite → write:TORN:probability")
    void tornWrite() {
      chaos.tornWrite(container, PathPrefix.path("/data"), 0.1);
      verify(transport).addRule(eq(container), anyString(), contains("/data:write:TORN:0.1"));
    }

    @Test
    @DisplayName("corruptRead → read:CORRUPT:probability")
    void corruptRead() {
      chaos.corruptRead(container, PathPrefix.path("/data"), 0.5);
      verify(transport).addRule(eq(container), anyString(), contains("/data:read:CORRUPT:0.5"));
    }

    @Test
    @DisplayName("slowFsync → fsync:LATENCY:millis")
    void slowFsync() {
      chaos.slowFsync(container, PathPrefix.path("/srv/wal"), Duration.ofMillis(250));
      verify(transport).addRule(eq(container), anyString(), contains("/srv/wal:fsync:LATENCY:250"));
    }

    @Test
    @DisplayName("failFsync → fsync:ERRNO_NAME:probability")
    void failFsync() {
      chaos.failFsync(container, PathPrefix.path("/srv/wal"), Errno.EIO, 0.05);
      verify(transport).addRule(eq(container), anyString(), contains("/srv/wal:fsync:EIO:0.05"));
    }

    @Test
    @DisplayName("slowOpen → open:LATENCY:millis")
    void slowOpen() {
      chaos.slowOpen(container, PathPrefix.path("/data"), Duration.ofMillis(50));
      verify(transport).addRule(eq(container), anyString(), contains("/data:open:LATENCY:50"));
    }

    @Test
    @DisplayName("failRename → rename_from:ERRNO_NAME:probability")
    void failRename() {
      chaos.failRename(container, PathPrefix.path("/data"), Errno.EACCES, 1.0);
      verify(transport)
          .addRule(eq(container), anyString(), contains("/data:rename_from:EACCES:1.0"));
    }
  }

  // ==================== reset / installTools ====================

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("reset on unprepared container is silent")
    void resetSilentOnUnprepared() {
      when(transport.isActive(container)).thenReturn(false);
      chaos.reset(container); // must not throw
      verify(transport, never()).removeRules(any(), anyString());
    }

    @Test
    @DisplayName("reset on prepared container removes all rules")
    void resetRemovesAll() {
      final IoRule r = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 1.0);
      final RuleHandle h = chaos.apply(container, r);

      chaos.reset(container);

      verify(transport).removeRules(container, h.owner());
    }

    @Test
    @DisplayName("installTools is a no-op")
    void installToolsNoOp() {
      chaos.installTools(container);
      verify(transport, never()).addRule(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("isSupported is true unconditionally (capability declaration)")
    void isSupportedTrue() {
      assertThat(chaos.isSupported()).isTrue();
    }
  }
}
