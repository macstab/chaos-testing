/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.http.testpack.l3;

import org.junit.jupiter.api.Test;
import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

import static org.assertj.core.api.Assertions.assertThat;

class L3HttpComposersTest {

    @Test
    void httpCascadingTimeoutHasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosHttpCascadingTimeout.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void httpCascadingTimeoutSeverityIsCritical() {
        final var meta = IncidentChaosHttpCascadingTimeout.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void httpCascadingTimeoutComposerIsNonBlank() {
        final var meta = IncidentChaosHttpCascadingTimeout.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void httpCascadingTimeoutIsRepeatable() {
        assertThat(IncidentChaosHttpCascadingTimeout.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // ---

    @Test
    void httpRetryAmplificationHasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosHttpRetryAmplification.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void httpRetryAmplificationSeverityIsCritical() {
        final var meta = IncidentChaosHttpRetryAmplification.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void httpRetryAmplificationComposerIsNonBlank() {
        final var meta = IncidentChaosHttpRetryAmplification.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void httpRetryAmplificationIsRepeatable() {
        assertThat(IncidentChaosHttpRetryAmplification.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // ---

    @Test
    void httpPartialOutageHasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosHttpPartialOutage.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void httpPartialOutageSeverityIsSevere() {
        final var meta = IncidentChaosHttpPartialOutage.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
    }

    @Test
    void httpPartialOutageComposerIsNonBlank() {
        final var meta = IncidentChaosHttpPartialOutage.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void httpPartialOutageIsRepeatable() {
        assertThat(IncidentChaosHttpPartialOutage.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // ---

    @Test
    void httpSslHandshakeStormHasChaosL3MetaAnnotation() {
        assertThat(IncidentChaosHttpSslHandshakeStorm.class.isAnnotationPresent(ChaosL3.class)).isTrue();
    }

    @Test
    void httpSslHandshakeStormSeverityIsSevere() {
        final var meta = IncidentChaosHttpSslHandshakeStorm.class.getAnnotation(ChaosL3.class);
        assertThat(meta.severity()).isEqualTo(Severity.SEVERE);
    }

    @Test
    void httpSslHandshakeStormComposerIsNonBlank() {
        final var meta = IncidentChaosHttpSslHandshakeStorm.class.getAnnotation(ChaosL3.class);
        assertThat(meta.composer()).isNotBlank();
    }

    @Test
    void httpSslHandshakeStormIsRepeatable() {
        assertThat(IncidentChaosHttpSslHandshakeStorm.class.isAnnotationPresent(java.lang.annotation.Repeatable.class)).isTrue();
    }

    // ---

    @Test
    void allAnnotationsHaveIdAttribute() throws Exception {
        assertThat(IncidentChaosHttpCascadingTimeout.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosHttpRetryAmplification.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosHttpPartialOutage.class.getDeclaredMethod("id")).isNotNull();
        assertThat(IncidentChaosHttpSslHandshakeStorm.class.getDeclaredMethod("id")).isNotNull();
    }

    @Test
    void allComposerClassNamesReferenceCorrectPackage() {
        final String expectedPackage = "com.macstab.chaos.http.testpack.l3.composers.";
        assertThat(IncidentChaosHttpCascadingTimeout.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosHttpRetryAmplification.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosHttpPartialOutage.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
        assertThat(IncidentChaosHttpSslHandshakeStorm.class.getAnnotation(ChaosL3.class).composer())
                .startsWith(expectedPackage);
    }
}
