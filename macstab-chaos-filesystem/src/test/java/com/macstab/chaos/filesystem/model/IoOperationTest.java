/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IoOperation (unit)")
class IoOperationTest {

  @Test
  @DisplayName("wireForm is lowercase of name(), preserving underscores")
  void wireFormLowercase() {
    assertThat(IoOperation.OPEN.wireForm()).isEqualTo("open");
    assertThat(IoOperation.WRITE.wireForm()).isEqualTo("write");
    assertThat(IoOperation.FDATASYNC.wireForm()).isEqualTo("fdatasync");
    assertThat(IoOperation.RENAME_FROM.wireForm()).isEqualTo("rename_from");
    assertThat(IoOperation.RENAME_TO.wireForm()).isEqualTo("rename_to");
  }

  @Test
  @DisplayName("13 logical operations match libchaos-io grammar coverage")
  void coverageBound() {
    assertThat(IoOperation.values()).hasSize(13);
  }
}
