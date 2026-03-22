rootProject.name = "macstab-chaos-testing"

// Apply Testcontainers DinD configuration BEFORE any projects load
apply(from = "gradle/testcontainers-dind.init.gradle.kts")

include(
    "macstab-chaos-core",
    "macstab-chaos-network",
    "macstab-chaos-redis"
)
