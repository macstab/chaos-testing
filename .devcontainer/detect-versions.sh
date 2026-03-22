#!/bin/bash
# Auto-detect versions from project files

set -e

# Find script directory and navigate relative to it
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Detect Gradle version from wrapper
GRADLE_VERSION=$(grep 'distributionUrl' "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties" | sed -E 's/.*gradle-([0-9.]+)-bin.*/\1/')

# Detect Java version from build.gradle.kts (check root project)
JAVA_VERSION=$(grep -r 'JavaVersion.VERSION_' "$REPO_ROOT" | grep build.gradle.kts | head -1 | sed -E 's/.*VERSION_([0-9]+).*/\1/')

# Fallback to default if not found
GRADLE_VERSION=${GRADLE_VERSION:-9.3.1}
JAVA_VERSION=${JAVA_VERSION:-25}

# Write to .env file in .devcontainer directory
cat > "$SCRIPT_DIR/.env" <<EOF
GRADLE_VERSION=${GRADLE_VERSION}
JAVA_VERSION=${JAVA_VERSION}
EOF

echo "Detected versions:"
echo "  Gradle: ${GRADLE_VERSION}"
echo "  Java: ${JAVA_VERSION}"
echo "Written to: $SCRIPT_DIR/.env"
