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
    api(project(":macstab-chaos-testpacks-connection"))
    api(project(":macstab-chaos-testpacks-dns"))
    api(project(":macstab-chaos-testpacks-memory"))
    api(project(":macstab-chaos-testpacks-process"))
    api(project(":macstab-chaos-testpacks-time"))
    api(project(":macstab-chaos-testpacks-filesystem"))
    api(project(":macstab-chaos-testpacks-java"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.4.14")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked", "-Xlint:deprecation"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "-Dnet.bytebuddy.experimental=true"
    )
}
