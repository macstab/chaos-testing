/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

dependencies {
    api(project(":macstab-chaos-core"))
    api("com.macstab.chaos.jvm:chaos-agent-api:${findProperty("jvmAgentVersion")}")
    implementation("com.macstab.chaos.jvm:chaos-agent-bootstrap:${findProperty("jvmAgentVersion")}")

    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
}
