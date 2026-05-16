/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Quarkus — bundles the agent's Quarkus extension with the container-side transport.
// Note: Quarkus extension deployment artifact is pulled transitively for dev-mode hot-reload
// support; consumers using bare runtime can exclude it.
//
//   testImplementation(project(":macstab-chaos-java-quarkus"))
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-quarkus-extension:${findProperty("jvmAgentVersion")}")
}
