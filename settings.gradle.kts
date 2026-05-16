rootProject.name = "macstab-chaos-testing"

// Apply Testcontainers DinD configuration BEFORE any projects load
apply(from = "gradle/testcontainers-dind.init.gradle.kts")

include(
    "macstab-chaos-core",
    "macstab-chaos-patterns",
    "macstab-chaos-cpu",
    "macstab-chaos-memory",
    "macstab-chaos-disk",
    "macstab-chaos-process",
    "macstab-chaos-time",
    "macstab-chaos-dns",
    "macstab-chaos-network",
    "macstab-chaos-redis",
    "macstab-chaos-connection",
    "macstab-chaos-cache",
    "macstab-chaos-filesystem",
    "macstab-chaos-proxy",
    "macstab-chaos-toxi-core",
    "macstab-chaos-java",
    "macstab-chaos-java-junit5",
    "macstab-chaos-java-spring-boot3",
    "macstab-chaos-java-spring-boot4",
    "macstab-chaos-java-micronaut",
    "macstab-chaos-java-quarkus"
)

// Note: macstab-chaos-core now renamed to avoid conflict
// Old chaos-core was package manager only
// Will be merged/renamed in future commits
