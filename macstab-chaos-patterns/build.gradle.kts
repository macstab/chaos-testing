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
    // NO chaos-core dependency - fully standalone!
    // Only SLF4J for logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.4.14")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked", "-Xlint:deprecation"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED", "--add-opens", "java.base/java.util=ALL-UNNAMED", "-Dnet.bytebuddy.experimental=true")
}
