/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Spring Boot 3 — bundles the agent's Spring Boot 3 test-starter (gives @ChaosTest + auto-config)
// with the container-side transport. Use for both in-process Spring tests and tests that drive a
// Spring container under test via testcontainers.
//
//   testImplementation(project(":macstab-chaos-java-spring-boot3"))
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-spring-boot3-test-starter:${findProperty("jvmAgentVersion")}")
}
