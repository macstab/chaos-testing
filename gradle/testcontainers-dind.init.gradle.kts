/**
 * Gradle init script for Testcontainers + Docker-in-Docker.
 * 
 * This runs BEFORE build.gradle.kts and sets JVM system properties
 * that Testcontainers reads during initialization.
 * 
 * Usage (automatic in this project):
 * - Detects /.dockerenv (DinD marker)
 * - Sets docker.host system property
 * - Disables Ryuk (cleanup container)
 */

if (File("/.dockerenv").exists()) {
    // Running in Docker container (DinD)
    println("✓ [Init Script] DinD detected - configuring Testcontainers for Unix socket")
    
    gradle.beforeProject {
        allprojects {
            tasks.withType<Test> {
                // Set as JVM system properties (read by Testcontainers)
                systemProperty("docker.host", "unix:///var/run/docker.sock")
                systemProperty("testcontainers.ryuk.disabled", "true")
                
                // Also set as environment (fallback)
                environment("DOCKER_HOST", "unix:///var/run/docker.sock")
                environment("TESTCONTAINERS_RYUK_DISABLED", "true")
            }
        }
    }
} else {
    println("✓ [Init Script] Running on host - using Testcontainers auto-detection")
}
