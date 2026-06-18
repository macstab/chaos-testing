/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Micronaut — bundles the agent's Micronaut integration with the container-side transport.
//
//   testImplementation(project(":macstab-chaos-java-micronaut"))
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-micronaut-integration:${findProperty("jvmAgentVersion")}")
}
