 #!/usr/bin/env bash
set -euo pipefail

# Usage: sync-libchaos.sh <version>
#
# Downloads all libchaos .so binaries from the macstab-chaos-testing-libraries
# GitHub release, verifies SHA256 checksums, and places each file in the
# owning Java module's classpath resources.
#
# Requires: curl, sha256sum (or shasum on macOS)

RELEASE_VERSION="${1:-}"
if [[ -z "$RELEASE_VERSION" ]]; then
  echo "Usage: $0 <version>" >&2
  echo "  Example: $0 1.0.0" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GITHUB_BASE="https://github.com/macstab/macstab-chaos-testing-libraries/releases/download/${RELEASE_VERSION}"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

LIBS=(io net memory time process dns)
LIBC_VARIANTS=(glibc musl)
ARCH_VARIANTS=(amd64 arm64)

lib_module() {
  case "$1" in
    io)      echo "macstab-chaos-disk" ;;
    net)     echo "macstab-chaos-network" ;;
    memory)  echo "macstab-chaos-memory" ;;
    time)    echo "macstab-chaos-time" ;;
    process) echo "macstab-chaos-process" ;;
    dns)     echo "macstab-chaos-dns" ;;
  esac
}

# ── helpers ──────────────────────────────────────────────────────────────────

sha256_check() {
  if command -v sha256sum &>/dev/null; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

download() {
  local url="$1" dest="$2"
  echo "  → ${url##*/}"
  curl -fsSL -o "$dest" "$url"
}

# ── download SHA256SUMS ───────────────────────────────────────────────────────

echo "Fetching SHA256SUMS for ${RELEASE_VERSION}..."
download "${GITHUB_BASE}/SHA256SUMS" "${TMPDIR}/SHA256SUMS"

# ── download and verify each .so ─────────────────────────────────────────────

for lib in "${LIBS[@]}"; do
  for libc in "${LIBC_VARIANTS[@]}"; do
    for arch in "${ARCH_VARIANTS[@]}"; do
      filename="libchaos-${lib}-${libc}-${arch}.so"
      download "${GITHUB_BASE}/${filename}" "${TMPDIR}/${filename}"
    done
  done
done

echo ""
echo "Verifying checksums..."
(cd "$TMPDIR" && sha256_check --check --ignore-missing SHA256SUMS)
echo "All checksums OK."

# ── place files into owning module resource dirs ──────────────────────────────

# Remove legacy location in core (libchaos-io was previously vendored there)
LEGACY_DIR="${REPO_ROOT}/macstab-chaos-core/src/main/resources/libchaos-io"
if [[ -d "$LEGACY_DIR" ]]; then
  echo ""
  echo "Removing legacy libchaos-io from macstab-chaos-core..."
  rm -rf "$LEGACY_DIR"
fi

echo ""
echo "Installing .so files..."

for lib in "${LIBS[@]}"; do
  module="$(lib_module "$lib")"
  resource_dir="${REPO_ROOT}/${module}/src/main/resources/libchaos-${lib}"
  mkdir -p "$resource_dir"

  for libc in "${LIBC_VARIANTS[@]}"; do
    for arch in "${ARCH_VARIANTS[@]}"; do
      filename="libchaos-${lib}-${libc}-${arch}.so"
      cp "${TMPDIR}/${filename}" "${resource_dir}/${filename}"
      echo "  ${module}/src/main/resources/libchaos-${lib}/${filename}"
    done
  done

  echo "${RELEASE_VERSION}" > "${resource_dir}/LIBCHAOS_VERSION"
  echo "  ${module}/src/main/resources/libchaos-${lib}/LIBCHAOS_VERSION"
done

echo ""
echo "Done. libchaos ${RELEASE_VERSION} installed."
