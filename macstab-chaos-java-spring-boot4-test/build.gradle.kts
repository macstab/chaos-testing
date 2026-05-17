/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Spring Boot 4 — TEST starter. Bundles the agent's @SpringBootTest test-starter
// (gives @ChaosTest meta-annotation, JUnit 5 ChaosAgentExtension, ChaosControlPlane bean)
// with the container-side transport.
//
//   testImplementation(project(":macstab-chaos-java-spring-boot4-test"))
//
// For non-test (production runtime) Spring Boot 4 chaos use `macstab-chaos-java-spring-boot4`.
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-spring-boot4-test-starter:${findProperty("jvmAgentVersion")}")
}
