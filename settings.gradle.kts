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
    "macstab-chaos-java-spring-boot3-test",
    "macstab-chaos-java-spring-boot4",
    "macstab-chaos-java-spring-boot4-test",
    "macstab-chaos-java-micronaut",
    "macstab-chaos-java-quarkus",
    // L2 testpack modules — per-module scenario annotations
    "macstab-chaos-testpacks-dns",
    "macstab-chaos-testpacks-connection",
    "macstab-chaos-testpacks-memory",
    "macstab-chaos-testpacks-process",
    "macstab-chaos-testpacks-time",
    "macstab-chaos-testpacks-filesystem",
    "macstab-chaos-testpacks-java",
    // L3 testpack modules — cross-domain named production-incident scenarios
    "macstab-chaos-testpacks-l3-redis",
    "macstab-chaos-testpacks-l3-jdbc",
    "macstab-chaos-testpacks-l3-http",
    "macstab-chaos-testpacks-l3-grpc",
    "macstab-chaos-testpacks-l3-kafka",
    "macstab-chaos-testpacks-l3-spring-boot",
    "macstab-chaos-testpacks-l3-jvm",
    "macstab-chaos-testpacks-l3-kubernetes",
    "macstab-chaos-testpacks-l3-cache",
    "macstab-chaos-testpacks-l3-spring",
    "macstab-chaos-testpacks-l3-feign",
    "macstab-chaos-testpacks-l3-system"
)

// Note: macstab-chaos-core now renamed to avoid conflict
// Old chaos-core was package manager only
// Will be merged/renamed in future commits
