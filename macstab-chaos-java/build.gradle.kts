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
    // The agent jar is delivered into target containers via JavaAgentTransport (classpath
    // scan). It must be on the test runtime classpath; declared as `api` so consumers also
    // get it transitively without having to repeat the coordinate.
    api("com.macstab.chaos.jvm:chaos-agent-bootstrap:${findProperty("jvmAgentVersion")}")

    compileOnly("org.projectlombok:lombok:${findProperty("lombokVersion")}")
    annotationProcessor("org.projectlombok:lombok:${findProperty("lombokVersion")}")
    implementation("org.slf4j:slf4j-api:${findProperty("slf4jVersion")}")
    implementation("org.testcontainers:testcontainers:1.20.4")

    testCompileOnly("org.projectlombok:lombok:${findProperty("lombokVersion")}")
    testAnnotationProcessor("org.projectlombok:lombok:${findProperty("lombokVersion")}")
    testImplementation("org.junit.jupiter:junit-jupiter:${findProperty("junitVersion")}")
    testImplementation("org.assertj:assertj-core:${findProperty("assertjVersion")}")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.18")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
