/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Spring Boot 3 — TEST starter. Bundles the agent's @SpringBootTest test-starter
// (gives @ChaosTest meta-annotation, JUnit 5 ChaosAgentExtension, ChaosControlPlane bean)
// with the container-side transport. Use this in @SpringBootTest classes.
//
//   testImplementation(project(":macstab-chaos-java-spring-boot3-test"))
//
// For non-test (production runtime) Spring Boot 3 chaos use `macstab-chaos-java-spring-boot3`.
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-spring-boot3-test-starter:${findProperty("jvmAgentVersion")}")
}
