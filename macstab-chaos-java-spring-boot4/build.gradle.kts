/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Spring Boot 4 — PRODUCTION starter. Bundles the agent's regular Spring Boot 4 starter
// (NOT the test variant) with the container-side transport. Use this when you want chaos
// available inside a running Spring Boot 4 app.
//
//   implementation(project(":macstab-chaos-java-spring-boot4"))
//
// For JUnit-driven @SpringBootTest chaos use `macstab-chaos-java-spring-boot4-test` instead.
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-spring-boot4-starter:${findProperty("jvmAgentVersion")}")
}
