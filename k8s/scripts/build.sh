#!/usr/bin/env bash
# Build OffBook+ on the local k3s (colima) cluster as a one-shot Job.
# No JDK/SDK is needed on this Mac; sources are rsynced into k8s/.workspace
# (hostPath-mounted into the Job) and artifacts are copied back afterwards.
#
# Usage: ./build.sh [options] [-- extra gradle args]
#   -t, --task TASK      Gradle task(s)   (default: GRADLE_TASK from config.env)
#       --image TAG      Toolchain image (default: IMAGE from config.env)
#       --namespace NS   Namespace       (default: NAMESPACE from config.env)
#       --keep-job       Do not delete the Job after success
#       --no-sync        Reuse the previous .workspace contents as-is
#       --skip-image-check  Assume the toolchain image already exists
#       --version-code N Override versionCode in the synced workspace copy
#       --version-name S Override versionName in the synced workspace copy
#   -h, --help
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

TASK="$GRADLE_TASK"
IMG="$IMAGE"
NS="$NAMESPACE"
KEEP_JOB=0
DO_SYNC=1
CHECK_IMAGE=1
EXTRA_ARGS=()
VER_CODE=""
VER_NAME=""

while [ $# -gt 0 ]; do
    case "$1" in
        -t | --task) TASK="$2"; shift 2 ;;
        --image) IMG="$2"; shift 2 ;;
        --namespace) NS="$2"; shift 2 ;;
        --keep-job) KEEP_JOB=1; shift ;;
        --no-sync) DO_SYNC=0; shift ;;
        --skip-image-check) CHECK_IMAGE=0; shift ;;
        --version-code)
            [[ "$2" =~ ^[0-9]+$ ]] || die "--version-code expects a number"
            VER_CODE="$2"; shift 2
            ;;
        --version-name) VER_NAME="$2"; shift 2 ;;
        --) shift; EXTRA_ARGS=("$@"); break ;;
        -h | --help)
            sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done

GRADLE_ARGS="$TASK ${EXTRA_ARGS[*]:-}"
GRADLE_ARGS="${GRADLE_ARGS% }"

ensure_cluster
NAMESPACE="$NS"
ensure_namespace

if [ "$CHECK_IMAGE" -eq 1 ] && ! image_in_cluster "$IMG"; then
    warn "toolchain image '$IMG' not found in the cluster; building it now"
    IMAGE="$IMG" "$SCRIPT_DIR/image.sh"
fi

JOB_NAME="$(job_name offbook-build)"
LOG_FILE="$CACHE_HOST/last-build.log"
mkdir -p "$CACHE_HOST"

if [ "$DO_SYNC" -eq 1 ]; then
    info "syncing sources into $WORKSPACE_HOST"
    mkdir -p "$WORKSPACE_HOST"
    # local.properties carries the Mac's sdk.dir and signing secrets; it is
    # regenerated for the container below and never synced.
    rsync -a --delete \
        --exclude=.git \
        --exclude=.idea \
        --exclude=.gradle \
        --exclude=.kotlin \
        --exclude=/build/ \
        --exclude=/app/build/ \
        --exclude=/app/release/ \
        --exclude=local.properties \
        --exclude=/k8s/ \
        --exclude=.DS_Store \
        "$REPO_ROOT/" "$WORKSPACE_HOST/"
else
    info "reusing existing workspace at $WORKSPACE_HOST (--no-sync)"
    mkdir -p "$WORKSPACE_HOST"
fi

# Container-side local.properties: SDK path inside the image + signing creds
# passed through from the Mac's local.properties (kept out of git and out of
# the cluster entirely).
{
    echo "sdk.dir=/opt/android-sdk"
    if [ -f "$REPO_ROOT/local.properties" ]; then
        grep -E '^(APP_KEY_FILE|APP_KEYSTORE_PASSWORD|APP_KEYSTORE_ALIAS|APP_KEY_PASSWORD)=' \
            "$REPO_ROOT/local.properties" || true
    fi
} >"$WORKSPACE_HOST/local.properties"

# Version overrides are applied to the workspace copy only (same approach as
# CI's tag builds), so an on-device install can upgrade over release builds.
WS_GRADLE="$WORKSPACE_HOST/app/build.gradle.kts"
if [ -n "$VER_CODE" ]; then
    sed -i '' "s/versionCode = [0-9]\+/versionCode = $VER_CODE/" "$WS_GRADLE"
    info "workspace versionCode -> $VER_CODE"
fi
if [ -n "$VER_NAME" ]; then
    sed -i '' "s/versionName = \".*\"/versionName = \"$VER_NAME\"/" "$WS_GRADLE"
    info "workspace versionName -> $VER_NAME"
fi

RENDERED="$CACHE_HOST/$JOB_NAME.yaml"
render_template "$K8S_DIR/templates/job-build.yaml" "$RENDERED" \
    "JOB_NAME=$JOB_NAME" \
    "NAMESPACE=$NS" \
    "DEADLINE=$JOB_DEADLINE_SECONDS" \
    "IMAGE=$IMG" \
    "GRADLE_ARGS=$GRADLE_ARGS" \
    "GRADLE_OPTS_VALUE=$GRADLE_OPTS_VALUE" \
    "CPU_LIMIT=$JOB_CPU_LIMIT" \
    "MEM_LIMIT=$JOB_MEM_LIMIT" \
    "WORKSPACE_HOSTPATH=$WORKSPACE_HOST" \
    "CACHE_HOSTPATH=$CACHE_HOST"

info "creating job $JOB_NAME in namespace $NS (task: $GRADLE_ARGS)"
kc apply -f "$RENDERED"

JOB_RESULT=unknown
if stream_job_logs_and_wait "$JOB_NAME" "$JOB_DEADLINE_SECONDS" "$LOG_FILE"; then
    info "build succeeded; copying artifacts back to the repo"
    rsync -a "$WORKSPACE_HOST/app/build/outputs/" "$REPO_ROOT/app/build/outputs/"

    found_artifacts=0
    while IFS= read -r f; do
        info "artifact: ${f#"$REPO_ROOT/"} ($(du -h "$f" | cut -f1))"
        found_artifacts=1
    done < <(find "$REPO_ROOT/app/build/outputs/apk" "$REPO_ROOT/app/build/outputs/bundles" \
        -type f \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null)
    [ "$found_artifacts" -eq 1 ] || warn "no apk/aab outputs found under app/build/outputs"

    if [ "$KEEP_JOB" -eq 0 ]; then delete_job "$JOB_NAME"; fi
    rm -f "$RENDERED"
    info "log saved to ${LOG_FILE#$REPO_ROOT/}"
else
    status="${JOB_RESULT:-unknown}"
    case "$status" in
        failure)
            reason="$(kc get pods -n "$NS" -l job-name="$JOB_NAME" \
                -o jsonpath='{range .items[*]}{.status.containerStatuses[0].state.terminated.reason}{.status.containerStatuses[0].lastState.terminated.reason}{"\n"}{end}' 2>/dev/null || true)"
            if grep -q OOMKilled <<<"${reason:-}"; then
                warn "container was OOMKilled; raise JOB_MEM_LIMIT or lower heap via GRADLE_OPTS_VALUE in k8s/config.env"
            fi
            die "build failed (pod reason: ${reason:-unknown}); inspect $LOG_FILE or: $KUBECTL --context $KUBE_CONTEXT -n $NS logs -l job-name=$JOB_NAME"
            ;;
        deadline) die "timed out after ${JOB_DEADLINE_SECONDS}s; job kept for inspection: $JOB_NAME" ;;
        *) die "build ended unexpectedly ($status); job kept for inspection: $JOB_NAME" ;;
    esac
fi
