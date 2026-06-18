/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

// Thin wrapper that re-exports the agent's JUnit 5 testkit + the container-side transport
// from macstab-chaos-java. Users add a single line:
//
//   testImplementation(project(":macstab-chaos-java-junit5"))
//
// …and get the @ExtendWith(ChaosAgentExtension.class) hook + parameter injection for
// ChaosControlPlane / ChaosSession, plus the option to drive container-side targets via
// JavaAgentTransport. No additional coordinates needed.
dependencies {
    api(project(":macstab-chaos-java"))
    api("com.macstab.chaos.jvm:chaos-agent-testkit:${findProperty("jvmAgentVersion")}")
}
