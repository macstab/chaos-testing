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

    // Network chaos module (OPTIONAL - only needed if using chaos features)
    compileOnly(project(":macstab-chaos-network"))

    // JUnit Jupiter API (execution conditions, extensions)
    api("org.junit.jupiter:junit-jupiter-api:${findProperty("junitVersion")}")

    // Testcontainers (shared container infrastructure)
    api("org.testcontainers:testcontainers:1.20.4")
    api("org.testcontainers:junit-jupiter:1.20.4")

    // Redis client (for auto-configuration and command tracking)
    compileOnly("io.lettuce:lettuce-core:6.5.4.RELEASE")

    // Test dependencies (needed for integration tests)
    testImplementation(project(":macstab-chaos-network"))
    testImplementation("io.lettuce:lettuce-core:6.5.4.RELEASE")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
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
