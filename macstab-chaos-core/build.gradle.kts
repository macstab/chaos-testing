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
    // Testcontainers (API dependency - users need this)
    api("org.testcontainers:testcontainers:1.20.4")
    
    // JUnit 5 (API dependency - needed for extensions)
    api("org.junit.jupiter:junit-jupiter-api:5.10.1")
    
    // SLF4J (logging facade)
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Test dependencies
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
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

// Version property file generation (for runtime access)
tasks.register("generateVersionProperties") {
    val outputDir = layout.buildDirectory.dir("generated/resources")
    outputs.dir(outputDir)
    
    doLast {
        val propsFile = outputDir.get().file("chaos-version.properties").asFile
        propsFile.parentFile.mkdirs()
        propsFile.writeText("version=${project.version}\n")
    }
}

tasks.named("processResources") {
    dependsOn("generateVersionProperties")
}

sourceSets {
    main {
        output.dir(tasks.named("generateVersionProperties"))
    }
}

// Publishing configured in root build.gradle.kts
