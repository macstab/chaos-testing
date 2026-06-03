/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jdbc.testpack.l3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.macstab.chaos.core.extension.ChaosL3;

/**
 * Structural contract tests for all JDBC L3 incident scenario annotations.
 *
 * <p>Verifies that every annotation carries the required {@link ChaosL3} meta-annotation
 * and declares a non-blank composer class name.
 */
class L3JdbcComposersTest {

    @Test
    void eachAnnotationHasChaosL3MetaAnnotation() {
        final List<Class<?>> annotations = List.of(
                IncidentChaosJdbcConnectionStorm.class,
                IncidentChaosJdbcPrimaryFailover.class,
                IncidentChaosJdbcWalPressure.class,
                IncidentChaosJdbcNetworkPartition.class,
                IncidentChaosJdbcDiskFull.class,
                IncidentChaosJdbcSequenceIdJump.class);

        for (final Class<?> ann : annotations) {
            assertThat(ann.isAnnotationPresent(ChaosL3.class))
                    .as(ann.getSimpleName() + " must carry @ChaosL3")
                    .isTrue();
        }
    }

    @Test
    void composerClassNamesAreNonBlank() {
        final List<Class<?>> annotations = List.of(
                IncidentChaosJdbcConnectionStorm.class,
                IncidentChaosJdbcPrimaryFailover.class,
                IncidentChaosJdbcWalPressure.class,
                IncidentChaosJdbcNetworkPartition.class,
                IncidentChaosJdbcDiskFull.class,
                IncidentChaosJdbcSequenceIdJump.class);

        for (final Class<?> ann : annotations) {
            final ChaosL3 meta = ann.getAnnotation(ChaosL3.class);
            assertThat(meta.composer())
                    .as(ann.getSimpleName() + " composer must not be blank")
                    .isNotBlank();
        }
    }

    @Test
    void eachAnnotationIsRepeatable() {
        final List<Class<?>> annotations = List.of(
                IncidentChaosJdbcConnectionStorm.class,
                IncidentChaosJdbcPrimaryFailover.class,
                IncidentChaosJdbcWalPressure.class,
                IncidentChaosJdbcNetworkPartition.class,
                IncidentChaosJdbcDiskFull.class,
                IncidentChaosJdbcSequenceIdJump.class);

        for (final Class<?> ann : annotations) {
            assertThat(ann.getAnnotation(java.lang.annotation.Repeatable.class))
                    .as(ann.getSimpleName() + " must be @Repeatable")
                    .isNotNull();
        }
    }

    @Test
    void connectionStormHasCriticalSeverity() {
        final ChaosL3 meta = IncidentChaosJdbcConnectionStorm.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.CRITICAL);
    }

    @Test
    void primaryFailoverHasCriticalSeverity() {
        final ChaosL3 meta = IncidentChaosJdbcPrimaryFailover.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.CRITICAL);
    }

    @Test
    void walPressureHasSevereSeverity() {
        final ChaosL3 meta = IncidentChaosJdbcWalPressure.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.SEVERE);
    }

    @Test
    void networkPartitionHasCriticalSeverity() {
        final ChaosL3 meta = IncidentChaosJdbcNetworkPartition.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.CRITICAL);
    }

    @Test
    void diskFullHasSevereSeverity() {
        final ChaosL3 meta = IncidentChaosJdbcDiskFull.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.SEVERE);
    }

    @Test
    void sequenceIdJumpHasSevereSeverity() {
        final ChaosL3 meta = IncidentChaosJdbcSequenceIdJump.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(com.macstab.chaos.core.extension.Severity.SEVERE);
    }

    @Test
    void composerNamesMatchExpectedPattern() {
        assertThat(IncidentChaosJdbcConnectionStorm.class.getAnnotation(ChaosL3.class).composer())
                .endsWith("JdbcConnectionStormComposer");
        assertThat(IncidentChaosJdbcPrimaryFailover.class.getAnnotation(ChaosL3.class).composer())
                .endsWith("JdbcPrimaryFailoverComposer");
        assertThat(IncidentChaosJdbcWalPressure.class.getAnnotation(ChaosL3.class).composer())
                .endsWith("JdbcWalPressureComposer");
        assertThat(IncidentChaosJdbcNetworkPartition.class.getAnnotation(ChaosL3.class).composer())
                .endsWith("JdbcNetworkPartitionComposer");
        assertThat(IncidentChaosJdbcDiskFull.class.getAnnotation(ChaosL3.class).composer())
                .endsWith("JdbcDiskFullComposer");
        assertThat(IncidentChaosJdbcSequenceIdJump.class.getAnnotation(ChaosL3.class).composer())
                .endsWith("JdbcSequenceIdJumpComposer");
    }
}
