/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.api.FilesystemChaos;

/**
 * Verify ServiceLoader registration for {@link FuseFilesystemChaos}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class ServiceLoaderRegistrationTest {

  @Test
  @DisplayName("should load FuseFilesystemChaos via ServiceLoader")
  void shouldLoadFuseFilesystemChaosViaServiceLoader() {
    final ServiceLoader<FilesystemChaos> loader = ServiceLoader.load(FilesystemChaos.class);

    final FilesystemChaos chaos = loader.findFirst().orElse(null);

    assertThat(chaos).isNotNull();
    assertThat(chaos).isInstanceOf(FuseFilesystemChaos.class);
  }
}
