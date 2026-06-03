/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.system.testpack.l3;

import org.junit.jupiter.api.Test;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every system L3 incident annotation carries the {@link ChaosL3} meta-annotation
 * with a non-blank composer class name and the expected severity.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class L3SystemComposersTest {

    // --- IncidentChaosSystemInodeExhaustion ---

    @Test
    void inodeExhaustion_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSystemInodeExhaustion.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void inodeExhaustion_severityIsCritical() {
        final var meta = IncidentChaosSystemInodeExhaustion.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void inodeExhaustion_composerIsNonBlank() {
        final var meta = IncidentChaosSystemInodeExhaustion.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void inodeExhaustion_isRepeatable() {
        assertThat(IncidentChaosSystemInodeExhaustion.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- IncidentChaosSystemTcpTimeWaitStorm ---

    @Test
    void tcpTimeWaitStorm_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSystemTcpTimeWaitStorm.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void tcpTimeWaitStorm_severityIsSevere() {
        final var meta = IncidentChaosSystemTcpTimeWaitStorm.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
    }

    @Test
    void tcpTimeWaitStorm_composerIsNonBlank() {
        final var meta = IncidentChaosSystemTcpTimeWaitStorm.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void tcpTimeWaitStorm_isRepeatable() {
        assertThat(IncidentChaosSystemTcpTimeWaitStorm.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- IncidentChaosSystemFdExhaustion ---

    @Test
    void fdExhaustion_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSystemFdExhaustion.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void fdExhaustion_severityIsCritical() {
        final var meta = IncidentChaosSystemFdExhaustion.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void fdExhaustion_composerIsNonBlank() {
        final var meta = IncidentChaosSystemFdExhaustion.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void fdExhaustion_isRepeatable() {
        assertThat(IncidentChaosSystemFdExhaustion.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- IncidentChaosSystemDirectMemoryLeak ---

    @Test
    void directMemoryLeak_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSystemDirectMemoryLeak.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void directMemoryLeak_severityIsSevere() {
        final var meta = IncidentChaosSystemDirectMemoryLeak.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
    }

    @Test
    void directMemoryLeak_composerIsNonBlank() {
        final var meta = IncidentChaosSystemDirectMemoryLeak.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void directMemoryLeak_isRepeatable() {
        assertThat(IncidentChaosSystemDirectMemoryLeak.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- IncidentChaosSystemSwapDeathSpiral ---

    @Test
    void swapDeathSpiral_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSystemSwapDeathSpiral.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void swapDeathSpiral_severityIsCritical() {
        final var meta = IncidentChaosSystemSwapDeathSpiral.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void swapDeathSpiral_composerIsNonBlank() {
        final var meta = IncidentChaosSystemSwapDeathSpiral.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void swapDeathSpiral_isRepeatable() {
        assertThat(IncidentChaosSystemSwapDeathSpiral.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- cross-cutting ---

    @Test
    void allAnnotationsHaveIdAttribute() throws Exception {
        assertThat(IncidentChaosSystemInodeExhaustion.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosSystemTcpTimeWaitStorm.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosSystemFdExhaustion.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosSystemDirectMemoryLeak.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosSystemSwapDeathSpiral.class.getDeclaredMethod("id")).isNotNull();
    }

    @Test
    void allComposerClassNamesReferenceCorrectPackage() {
        final String expectedPackage = "com.macstab.chaos.system.testpack.l3.composers.";
        assertThat(IncidentChaosSystemInodeExhaustion.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosSystemTcpTimeWaitStorm.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosSystemFdExhaustion.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosSystemDirectMemoryLeak.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosSystemSwapDeathSpiral.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
    }
}
