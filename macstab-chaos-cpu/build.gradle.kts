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
    // Core API (interfaces)
    api(project(":macstab-chaos-core"))
    
    // Testcontainers (already provided by core)
    // SLF4J (already provided by core)
    
    // Test annotation shared with network module
    testImplementation(project(":macstab-chaos-network"))

    // Test dependencies
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
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
