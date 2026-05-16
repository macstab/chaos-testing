/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Spring Boot 4 — bundles the agent's Spring Boot 4 test-starter with the container-side transport.
//
//   testImplementation(project(":macstab-chaos-java-spring-boot4"))
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-spring-boot4-test-starter:${findProperty("jvmAgentVersion")}")
}
