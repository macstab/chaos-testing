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

    // Syscall-level fault injection: vendors the libchaos-io .so resources
    // onto the classpath so LibchaosTransport can resolve them at prepare() time.
    api(project(":macstab-chaos-disk"))

    // Test dependencies (junit/assertj/awaitility/mockito are injected globally by root build)
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
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

description = "Filesystem chaos — shell-level (disk fill, chmod) plus syscall-level (libchaos-io)"
