/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.syscall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

@DisplayName("LibchaosTransport (unit)")
class LibchaosTransportTest {

  private static final LibchaosLib LIB = LibchaosLib.NET;
  private static final String LABEL = "macstab.chaos.net.active";
  private static final String CONFIG = "/tmp/.chaos-net.conf";

  @SuppressWarnings("rawtypes")
  private GenericContainer container;

  private LibchaosTransport transport;
  private ExecResult success;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    transport = new LibchaosTransport(LIB);
    container = mock(GenericContainer.class);
    when(container.isRunning()).thenReturn(true);

    final Map<String, String> labels = new HashMap<>();
    labels.put(LABEL, "glibc-amd64");
    when(container.getLabels()).thenReturn(labels);

    success = mock(ExecResult.class);
    when(success.getExitCode()).thenReturn(0);
    when(container.execInContainer(anyString(), anyString(), anyString())).thenReturn(success);
  }

  @Nested
  @DisplayName("constructor")
  class Constructor {

    @Test
    @DisplayName("null lib throws NPE")
    void nullLib() {
      assertThatThrownBy(() -> new LibchaosTransport(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null command builder throws NPE")
    void nullCommandBuilder() {
      assertThatThrownBy(() -> new LibchaosTransport(LibchaosLib.IO, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getLib exposes the configured library")
    void getLibExposesLib() {
      assertThat(new LibchaosTransport(LibchaosLib.TIME).getLib()).isEqualTo(LibchaosLib.TIME);
    }
  }

  @Nested
  @DisplayName("isActive")
  class IsActive {

    @Test
    @DisplayName("returns true when label present")
    void trueWhenLabelled() {
      assertThat(transport.isActive(container)).isTrue();
    }

    @Test
    @DisplayName("returns false when label absent")
    @SuppressWarnings("unchecked")
    void falseWhenNoLabel() {
      when(container.getLabels()).thenReturn(Map.of());
      assertThat(transport.isActive(container)).isFalse();
    }

    @Test
    @DisplayName("null container throws NPE")
    void nullContainer() {
      assertThatThrownBy(() -> transport.isActive(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("addRule")
  class AddRule {

    @Test
    @DisplayName("null container throws NPE")
    void nullContainer() {
      assertThatThrownBy(() -> transport.addRule(null, "net", "rule"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null owner throws NPE")
    void nullOwner() {
      assertThatThrownBy(() -> transport.addRule(container, null, "rule"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null rule throws NPE")
    void nullRule() {
      assertThatThrownBy(() -> transport.addRule(container, "net", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("unsafe owner throws IllegalArgumentException")
    void unsafeOwner() {
      assertThatThrownBy(() -> transport.addRule(container, "bad owner!", "rule"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("[a-z0-9_]+");
    }

    @Test
    @DisplayName("throws when not prepared")
    @SuppressWarnings("unchecked")
    void throwsWhenNotActive() {
      when(container.getLabels()).thenReturn(Map.of());
      assertThatThrownBy(() -> transport.addRule(container, "net", "rule"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("prepare()");
    }

    @Test
    @DisplayName("happy path — exec appends rule with owner comment")
    @SuppressWarnings("unchecked")
    void appendsRuleWithOwner() throws Exception {
      transport.addRule(container, "net", "*:connect:ERRNO:ECONNREFUSED:0.5");
      verify(container)
          .execInContainer(
              anyString(), anyString(), org.mockito.ArgumentMatchers.contains("# net"));
    }
  }

  @Nested
  @DisplayName("addRules")
  class AddRules {

    @Test
    @DisplayName("null container throws NPE")
    void nullContainer() {
      assertThatThrownBy(() -> transport.addRules(null, "net", List.of("r")))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null owner throws NPE — but only when rules non-empty")
    void nullOwner() {
      assertThatThrownBy(() -> transport.addRules(container, null, List.of("r")))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null rules throws NPE")
    void nullRules() {
      assertThatThrownBy(() -> transport.addRules(container, "net", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("empty list is a no-op — no exec")
    @SuppressWarnings("unchecked")
    void emptyListNoOp() throws Exception {
      transport.addRules(container, "net", List.of());
      verify(container, never()).execInContainer(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("non-empty list executes once")
    @SuppressWarnings("unchecked")
    void nonEmptyExecOnce() throws Exception {
      transport.addRules(container, "net", List.of("r1", "r2"));
      verify(container).execInContainer(anyString(), anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("removeRules")
  class RemoveRules {

    @Test
    @DisplayName("null container throws NPE")
    void nullContainer() {
      assertThatThrownBy(() -> transport.removeRules(null, "net"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null owner throws NPE")
    void nullOwner() {
      assertThatThrownBy(() -> transport.removeRules(container, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("throws when not prepared")
    @SuppressWarnings("unchecked")
    void throwsWhenNotActive() {
      when(container.getLabels()).thenReturn(Map.of());
      assertThatThrownBy(() -> transport.removeRules(container, "net"))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("happy path — exec uses sed to remove owner lines")
    @SuppressWarnings("unchecked")
    void sedRemovesOwnerLines() throws Exception {
      transport.removeRules(container, "net");
      verify(container)
          .execInContainer(anyString(), anyString(), org.mockito.ArgumentMatchers.contains("sed"));
    }
  }

  @Nested
  @DisplayName("clearRules")
  class ClearRules {

    @Test
    @DisplayName("null container throws NPE")
    void nullContainer() {
      assertThatThrownBy(() -> transport.clearRules(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("throws when not prepared")
    @SuppressWarnings("unchecked")
    void throwsWhenNotActive() {
      when(container.getLabels()).thenReturn(Map.of());
      assertThatThrownBy(() -> transport.clearRules(container))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("happy path — exec removes config file")
    @SuppressWarnings("unchecked")
    void removesConfigFile() throws Exception {
      transport.clearRules(container);
      verify(container)
          .execInContainer(anyString(), anyString(), org.mockito.ArgumentMatchers.contains(CONFIG));
    }
  }

  @Nested
  @DisplayName("composeLdPreload")
  class ComposeLdPreload {

    private static final String NET = "/usr/local/lib/libchaos-net.so";
    private static final String IO = "/usr/local/lib/libchaos-io.so";

    @Test
    @DisplayName("empty existing returns the new path verbatim")
    void emptyExisting() {
      assertThat(LibchaosTransport.composeLdPreload("", NET)).isEqualTo(NET);
    }

    @Test
    @DisplayName("null existing returns the new path verbatim")
    void nullExisting() {
      assertThat(LibchaosTransport.composeLdPreload(null, NET)).isEqualTo(NET);
    }

    @Test
    @DisplayName("non-empty existing → appends with colon separator")
    void appendsWithColon() {
      assertThat(LibchaosTransport.composeLdPreload(IO, NET)).isEqualTo(IO + ":" + NET);
    }

    @Test
    @DisplayName("path already present → returns existing unchanged")
    void deduplicatesTailMatch() {
      final String existing = IO + ":" + NET;
      assertThat(LibchaosTransport.composeLdPreload(existing, NET)).isEqualTo(existing);
    }

    @Test
    @DisplayName("path already at head → returns existing unchanged")
    void deduplicatesHeadMatch() {
      final String existing = NET + ":" + IO;
      assertThat(LibchaosTransport.composeLdPreload(existing, NET)).isEqualTo(existing);
    }

    @Test
    @DisplayName("substring of an existing entry is not treated as a duplicate")
    void noFalsePositiveOnSubstring() {
      final String existing = NET + ".1";
      assertThat(LibchaosTransport.composeLdPreload(existing, NET))
          .isEqualTo(existing + ":" + NET);
    }

    @Test
    @DisplayName("user-provided pre-existing entry is preserved")
    void preservesUserProvidedEntry() {
      final String userLib = "/opt/custom/preload.so";
      assertThat(LibchaosTransport.composeLdPreload(userLib, NET)).isEqualTo(userLib + ":" + NET);
    }
  }

  // ==================== Helpers ====================

  @SuppressWarnings("unused")
  private static ExecResult execResult(final int exit, final String stdout) throws IOException {
    final var r = mock(ExecResult.class);
    when(r.getExitCode()).thenReturn(exit);
    when(r.getStdout()).thenReturn(stdout);
    when(r.getStderr()).thenReturn("");
    return r;
  }
}
