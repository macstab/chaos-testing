/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.process.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessSelector (unit)")
class ProcessSelectorTest {

  @Test
  @DisplayName("wireForm tokens match libchaos-process grammar")
  void wireForms() {
    assertThat(ProcessSelector.PTHREAD_CREATE.wireForm()).isEqualTo("pthread_create");
    assertThat(ProcessSelector.FORK.wireForm()).isEqualTo("fork");
    assertThat(ProcessSelector.POSIX_SPAWN.wireForm()).isEqualTo("posix_spawn");
    assertThat(ProcessSelector.POSIX_SPAWNP.wireForm()).isEqualTo("posix_spawnp");
    assertThat(ProcessSelector.EXECVE.wireForm()).isEqualTo("execve");
    assertThat(ProcessSelector.EXECVEAT.wireForm()).isEqualTo("execveat");
    assertThat(ProcessSelector.WAITPID.wireForm()).isEqualTo("waitpid");
    assertThat(ProcessSelector.WILDCARD.wireForm()).isEqualTo("*");
  }

  @Test
  @DisplayName("pthread_create accepts EAGAIN, EINVAL, EPERM")
  void pthreadErrnos() {
    assertThat(ProcessSelector.PTHREAD_CREATE.validErrnos())
        .containsExactlyInAnyOrder(ProcessErrno.EAGAIN, ProcessErrno.EINVAL, ProcessErrno.EPERM);
  }

  @Test
  @DisplayName("fork accepts EAGAIN, ENOMEM")
  void forkErrnos() {
    assertThat(ProcessSelector.FORK.validErrnos())
        .containsExactlyInAnyOrder(ProcessErrno.EAGAIN, ProcessErrno.ENOMEM);
  }

  @Test
  @DisplayName("posix_spawn and posix_spawnp share the same 4-errno set")
  void spawnErrnos() {
    assertThat(ProcessSelector.POSIX_SPAWN.validErrnos())
        .containsExactlyInAnyOrder(
            ProcessErrno.EAGAIN, ProcessErrno.EINVAL, ProcessErrno.ENOENT, ProcessErrno.ENOMEM);
    assertThat(ProcessSelector.POSIX_SPAWNP.validErrnos())
        .isEqualTo(ProcessSelector.POSIX_SPAWN.validErrnos());
  }

  @Test
  @DisplayName("execve and execveat share the same 8-errno set")
  void execErrnos() {
    assertThat(ProcessSelector.EXECVE.validErrnos())
        .containsExactlyInAnyOrder(
            ProcessErrno.EACCES,
            ProcessErrno.E2BIG,
            ProcessErrno.ELOOP,
            ProcessErrno.ENOEXEC,
            ProcessErrno.ENOENT,
            ProcessErrno.ENOMEM,
            ProcessErrno.EPERM,
            ProcessErrno.ETXTBSY);
    assertThat(ProcessSelector.EXECVEAT.validErrnos())
        .isEqualTo(ProcessSelector.EXECVE.validErrnos());
  }

  @Test
  @DisplayName("waitpid accepts ECHILD, EINTR, EINVAL")
  void waitpidErrnos() {
    assertThat(ProcessSelector.WAITPID.validErrnos())
        .containsExactlyInAnyOrder(ProcessErrno.ECHILD, ProcessErrno.EINTR, ProcessErrno.EINVAL);
  }

  @Test
  @DisplayName("wildcard accepts the full 12-errno union")
  void wildcardErrnos() {
    assertThat(ProcessSelector.WILDCARD.validErrnos())
        .containsExactlyInAnyOrder(ProcessErrno.values());
  }

  @Test
  @DisplayName("accepts() probe matches validErrnos contents")
  void acceptsProbe() {
    assertThat(ProcessSelector.PTHREAD_CREATE.accepts(ProcessErrno.EAGAIN)).isTrue();
    assertThat(ProcessSelector.PTHREAD_CREATE.accepts(ProcessErrno.ENOMEM)).isFalse();
    assertThat(ProcessSelector.WAITPID.accepts(ProcessErrno.EINTR)).isTrue();
    assertThat(ProcessSelector.WAITPID.accepts(ProcessErrno.EAGAIN)).isFalse();
    assertThat(ProcessSelector.WILDCARD.accepts(ProcessErrno.EAGAIN)).isTrue();
    assertThat(ProcessSelector.WILDCARD.accepts(ProcessErrno.EINTR)).isTrue();
  }
}
