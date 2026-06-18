/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.core.extension;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.macstab.chaos.core.annotation.InstallPackages;

/**
 * Basic integration test for {@link CoreExtension}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CoreExtension Basic Test")
@Testcontainers
@ExtendWith(CoreExtension.class)
@InstallPackages(value = "curl", verify = false)
class CoreExtensionBasicTest {

  @Container
  GenericContainer<?> debian =
      new GenericContainer<>(DockerImageName.parse("debian:12-slim"))
          .withCommand("sleep", "infinity");

  @Test
  @DisplayName("should process annotations via extension")
  void shouldProcessAnnotationsViaExtension() throws Exception {
    // Just verify the extension ran without errors
    assertThat(debian.isRunning()).isTrue();

    // Verify curl was installed (CLASS-level annotation)
    final var result = debian.execInContainer("which", "curl");
    assertThat(result.getExitCode()).isZero();
  }
}
