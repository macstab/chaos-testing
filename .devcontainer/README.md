# Dev Container for Sentinel Integration Tests

This dev container provides a Linux environment for running Sentinel integration tests (both Spring Boot 3 and Spring Boot 4 modules) that are incompatible with macOS/Windows due to Testcontainers networking limitations.

## Problem

Sentinel integration tests fail on macOS/Windows because:
- Redis Sentinel returns Docker internal IPs (e.g., `172.19.0.2:6379`)
- Spring Boot (running on host) cannot reach Docker internal IPs
- Testcontainers port mapping (`localhost:12345`) doesn't help because Sentinel announces internal addresses

## Solution

Run tests inside a dev container where:
- Spring Boot runs **inside** Docker network
- All Redis containers (master, replicas, sentinels) are on same network
- Internal IPs work directly (no host-to-container mapping needed)

## Usage

### VS Code (Recommended)

1. Install **Dev Containers** extension
2. Open project in VS Code
3. Command Palette → **Dev Containers: Reopen in Container**
4. Wait for container build + Redis cluster startup
5. Run tests inside container:
   ```bash
   # Spring Boot 3
   ./gradlew :redis-laned-spring-boot-3-starter:test --tests SentinelReadFromIntegrationTest
   
   # Spring Boot 4
   ./gradlew :redis-laned-spring-boot-4-starter:test --tests SentinelReadFromIntegrationTest
   
   # Or run both
   ./gradlew test --tests SentinelReadFromIntegrationTest
   ```

### Manual (Docker Compose)

```bash
cd .devcontainer

# Start dev container + Redis cluster
docker compose up -d

# Enter dev container
docker compose exec devcontainer bash

# Inside container
cd /workspace
./gradlew :redis-laned-spring-boot-3-starter:test --tests SentinelReadFromIntegrationTest
./gradlew :redis-laned-spring-boot-4-starter:test --tests SentinelReadFromIntegrationTest

# Cleanup
docker compose down -v
```

## What's Included

**Redis Sentinel Cluster:**
- 1 master (port 6379)
- 2 replicas (ports 6380, 6381)
- 3 sentinels (ports 26379, 26380, 26381)

**Dev Environment:**
- Java 25
- Gradle 9.3.1 (auto-detected)
- Docker-in-Docker (for Testcontainers)

## Troubleshooting

**Cluster not starting:**
```bash
# Check logs
docker compose logs redis-master redis-replica-1 sentinel-1

# Restart cluster
docker compose restart redis-master redis-replica-1 redis-replica-2 sentinel-1 sentinel-2 sentinel-3
```

**Tests still failing:**
- Ensure you're running **inside** the container, not from host
- Check cluster is healthy: `docker compose ps` (all should be "Up (healthy)")
- Verify network connectivity: `docker compose exec devcontainer ping redis-master`

## Architecture

```
┌─────────────────────────────────────────────┐
│  Dev Container (devcontainer)               │
│  ┌───────────────────────────────────────┐  │
│  │  Spring Boot Test                     │  │
│  │  ↓ connects to                        │  │
│  │  redis-master:6379                    │  │
│  │  (via Sentinel: sentinel-1:26379)     │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
         │
         ├─ same Docker network ─┐
         │                        │
    redis-master           sentinel-1
    redis-replica-1        sentinel-2
    redis-replica-2        sentinel-3
```

All containers share the `redis-sentinel` network → internal IPs work directly.

## CI/CD

This setup works on **Linux CI** (GitHub Actions, GitLab CI) without dev container:
- Linux Docker has native networking
- No host-to-container IP mapping issues
- Tests run directly on CI agents

**GitHub Actions example:**
```yaml
jobs:
  test:
    runs-on: ubuntu-latest  # ← Linux = works!
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
      - run: ./gradlew :redis-laned-spring-boot-3-starter:test --tests SentinelReadFromIntegrationTest
```
