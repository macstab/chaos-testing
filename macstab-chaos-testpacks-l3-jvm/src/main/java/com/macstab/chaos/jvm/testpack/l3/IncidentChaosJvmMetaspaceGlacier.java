/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.jvm.testpack.l3;

import java.lang.annotation.*;

import com.macstab.chaos.core.extension.ChaosL3;
import com.macstab.chaos.core.extension.Severity;

/**
 *
 *
 * <h2>What this is</h2>
 *
 * <p>Simulates a classloader leak that exhausts Metaspace over hours: synthetic classes are
 * generated and loaded with strong references retained so they can never be GC'd. Heap metrics
 * remain green throughout; the problem is only discovered at the eventual Metaspace OOM.
 *
 * <h2>Composed of</h2>
 *
 * <ul>
 *   <li>JVM: MetaspacePressure with {@code generatedClassCount} classes × {@code fieldsPerClass}
 *       fields, strong references retained (classloader leak mode)
 * </ul>
 *
 * <h2>How bad it is</h2>
 *
 * <p>Severity: <strong>Severe</strong><br>
 * Heap metrics are completely clean throughout; the failure is invisible until the final {@code
 * OutOfMemoryError: Metaspace}. In production this manifests as a pod that crashes unpredictably
 * after days of operation.
 *
 * <h2>Industry references</h2>
 *
 * <p>Classloader leaks causing Metaspace OOM: frequently observed in OSGi containers, application
 * servers doing dynamic class generation (Hibernate, CGLIB, ByteBuddy), and plugin-loading
 * frameworks that do not properly unload classloaders on reload/undeploy cycles.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @AppContainer
 * @IncidentChaosJvmMetaspaceGlacier(generatedClassCount = 1000, fieldsPerClass = 50)
 * class MetaspaceGlacierTest { ... }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(IncidentChaosJvmMetaspaceGlacier.List.class)
@ChaosL3(
    composer = "com.macstab.chaos.jvm.testpack.l3.composers.JvmMetaspaceGlacierComposer",
    severity = Severity.SEVERE)
public @interface IncidentChaosJvmMetaspaceGlacier {

  /** Container filter id; empty string matches all containers. */
  String id() default "";

  /** Number of synthetic classes to generate and retain strongly. */
  int generatedClassCount() default 1000;

  /** Number of fields per generated class (controls per-class metaspace footprint). */
  int fieldsPerClass() default 50;

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    IncidentChaosJvmMetaspaceGlacier[] value();
  }
}
