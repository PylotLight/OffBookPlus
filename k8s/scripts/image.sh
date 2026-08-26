#!/usr/bin/env bash
# Build the Android toolchain image and make sure the k3s cluster can use it.
#
# - If k3s runs with the colima docker daemon as its CRI (colima default),
#   the docker build alone is enough.
# - If k3s uses embedded containerd, the image is exported from docker and
#   imported via `k3s ctr images import` (works because colima mounts the
#   macOS home into the VM at identical paths).
#
# Usage: ./image.sh [--keep-tar]
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

KEEP_TAR=0
[ "${1:-}" = "--keep-tar" ] && KEEP_TAR=1

need docker "provided by colima; ensure the VM is running"
ensure_cluster

RUNTIME="$(cluster_runtime)"
info "building $IMAGE (first run downloads the Android SDK; expect several GB of transfer)"
docker build -t "$IMAGE" -f "$K8S_DIR/Dockerfile" "$K8S_DIR"

if [ "$RUNTIME" != "docker" ]; then
    mkdir -p "$CACHE_HOST"
    TAR="$CACHE_HOST/image.tar"
    info "k3s uses containerd; exporting and importing image"
    docker save -o "$TAR" "$IMAGE"
    colima_vm ssh -- sudo k3s ctr -n k8s.io images import "$TAR"
    if [ "$KEEP_TAR" -eq 0 ]; then rm -f "$TAR"; fi
else
    info "k3s runs on the colima docker daemon; no import needed"
fi

if image_in_cluster "$IMAGE"; then
    info "image '$IMAGE' is available to the cluster"
else
    warn "could not confirm '$IMAGE'; builds may fail until it exists (rerun: $SCRIPT_DIR/image.sh)"
fi
