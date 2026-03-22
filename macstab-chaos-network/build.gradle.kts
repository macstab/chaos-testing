/* (C)2026 Christian Schnapka / Macstab GmbH */

plugins {
    `java-library`
    `maven-publish`
}

group = findProperty("group") as String
version = findProperty("version") as String

java {
    sourceCompatibility = JavaVersion.valueOf("VERSION_${findProperty("javaVersion")}")
    targetCompatibility = JavaVersion.valueOf("VERSION_${findProperty("javaVersion")}")
}

dependencies {
    // Core module (package installation, utilities)
    api(project(":macstab-chaos-core"))

    // JUnit Jupiter API (for DisabledOnNonLinuxHost condition)
    api("org.junit.jupiter:junit-jupiter-api:${findProperty("junitVersion")}")

    // Testcontainers
    api("org.testcontainers:testcontainers:1.20.4")

    // Test dependencies
    testImplementation("org.assertj:assertj-core:3.24.2")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.4.14")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Xlint:unchecked",
            "-Xlint:deprecation"
        )
    )
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Java 25 compatibility for Mockito/Byte Buddy
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "-Dnet.bytebuddy.experimental=true"
    )
}

// Publishing configured in root build.gradle.kts
