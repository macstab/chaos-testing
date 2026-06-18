/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.strategy.libchaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.filesystem.model.Errno;
import com.macstab.chaos.filesystem.model.IoOperation;
import com.macstab.chaos.filesystem.model.IoRule;
import com.macstab.chaos.filesystem.model.PathPrefix;

@DisplayName("IoRuleSerializer (unit)")
class IoRuleSerializerTest {

  @Test
  @DisplayName("errno on write — path:write:EIO:probability")
  void errnoOnWrite() {
    final IoRule r = IoRule.errno(PathPrefix.path("/data"), IoOperation.WRITE, Errno.EIO, 0.3);
    assertThat(IoRuleSerializer.serialize(r)).isEqualTo("/data:write:EIO:0.3");
  }

  @Test
  @DisplayName("latency on fsync — path:fsync:LATENCY:millis")
  void latencyOnFsync() {
    final IoRule r =
        IoRule.latency(PathPrefix.path("/srv/wal"), IoOperation.FSYNC, Duration.ofMillis(250));
    assertThat(IoRuleSerializer.serialize(r)).isEqualTo("/srv/wal:fsync:LATENCY:250");
  }

  @Test
  @DisplayName("torn on write — path:write:TORN:probability")
  void tornOnWrite() {
    final IoRule r = IoRule.torn(PathPrefix.path("/srv/wal"), IoOperation.WRITE, 0.1);
    assertThat(IoRuleSerializer.serialize(r)).isEqualTo("/srv/wal:write:TORN:0.1");
  }

  @Test
  @DisplayName("corrupt on read — path:read:CORRUPT:probability")
  void corruptOnRead() {
    final IoRule r = IoRule.corrupt(PathPrefix.path("/srv/data"), IoOperation.READ, 0.5);
    assertThat(IoRuleSerializer.serialize(r)).isEqualTo("/srv/data:read:CORRUPT:0.5");
  }

  @Test
  @DisplayName("wildcard renders as '*'")
  void wildcard() {
    final IoRule r = IoRule.errno(PathPrefix.wildcard(), IoOperation.OPEN, Errno.EMFILE, 0.05);
    assertThat(IoRuleSerializer.serialize(r)).isEqualTo("*:open:EMFILE:0.05");
  }

  @Test
  @DisplayName("rename_from / rename_to wire form preserves underscore")
  void renameOps() {
    final IoRule from =
        IoRule.errno(PathPrefix.path("/old"), IoOperation.RENAME_FROM, Errno.EACCES, 1.0);
    final IoRule to =
        IoRule.errno(PathPrefix.path("/new"), IoOperation.RENAME_TO, Errno.EACCES, 1.0);
    assertThat(IoRuleSerializer.serialize(from)).isEqualTo("/old:rename_from:EACCES:1.0");
    assertThat(IoRuleSerializer.serialize(to)).isEqualTo("/new:rename_to:EACCES:1.0");
  }

  @Test
  @DisplayName("rejects null rule")
  void rejectsNull() {
    assertThatThrownBy(() -> IoRuleSerializer.serialize(null))
        .isInstanceOf(NullPointerException.class);
  }
}
