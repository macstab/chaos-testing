/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.spring.testpack.l3;

import org.junit.jupiter.api.Test;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every Spring L3 incident annotation carries the {@link ChaosL3} meta-annotation
 * with a non-blank composer class name and the expected severity.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
class L3SpringComposersTest {

    // --- IncidentChaosSpringTransactionalPoolDeadlock ---

    @Test
    void transactionalPoolDeadlock_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSpringTransactionalPoolDeadlock.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void transactionalPoolDeadlock_severityIsCritical() {
        final var meta = IncidentChaosSpringTransactionalPoolDeadlock.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void transactionalPoolDeadlock_composerIsNonBlank() {
        final var meta = IncidentChaosSpringTransactionalPoolDeadlock.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void transactionalPoolDeadlock_isRepeatable() {
        assertThat(IncidentChaosSpringTransactionalPoolDeadlock.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- IncidentChaosSpringWebFluxReactorStarvation ---

    @Test
    void webFluxReactorStarvation_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSpringWebFluxReactorStarvation.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void webFluxReactorStarvation_severityIsCritical() {
        final var meta = IncidentChaosSpringWebFluxReactorStarvation.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void webFluxReactorStarvation_composerIsNonBlank() {
        final var meta = IncidentChaosSpringWebFluxReactorStarvation.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void webFluxReactorStarvation_isRepeatable() {
        assertThat(IncidentChaosSpringWebFluxReactorStarvation.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- IncidentChaosSpringOsivConnectionStarvation ---

    @Test
    void osivConnectionStarvation_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSpringOsivConnectionStarvation.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void osivConnectionStarvation_severityIsSevere() {
        final var meta = IncidentChaosSpringOsivConnectionStarvation.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
    }

    @Test
    void osivConnectionStarvation_composerIsNonBlank() {
        final var meta = IncidentChaosSpringOsivConnectionStarvation.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void osivConnectionStarvation_isRepeatable() {
        assertThat(IncidentChaosSpringOsivConnectionStarvation.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- IncidentChaosSpringConfigRefreshWave ---

    @Test
    void configRefreshWave_hasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosSpringConfigRefreshWave.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void configRefreshWave_severityIsSevere() {
        final var meta = IncidentChaosSpringConfigRefreshWave.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
    }

    @Test
    void configRefreshWave_composerIsNonBlank() {
        final var meta = IncidentChaosSpringConfigRefreshWave.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void configRefreshWave_isRepeatable() {
        assertThat(IncidentChaosSpringConfigRefreshWave.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // --- cross-cutting ---

    @Test
    void allAnnotationsHaveIdAttribute() throws Exception {
        assertThat(IncidentChaosSpringTransactionalPoolDeadlock.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosSpringWebFluxReactorStarvation.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosSpringOsivConnectionStarvation.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosSpringConfigRefreshWave.class.getDeclaredMethod("id")).isNotNull();
    }

    @Test
    void allComposerClassNamesReferenceCorrectPackage() {
        final String expectedPackage = "com.macstab.chaos.spring.testpack.l3.composers.";
        assertThat(IncidentChaosSpringTransactionalPoolDeadlock.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosSpringWebFluxReactorStarvation.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosSpringOsivConnectionStarvation.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosSpringConfigRefreshWave.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
    }
}
