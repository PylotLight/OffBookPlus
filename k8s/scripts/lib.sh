#!/usr/bin/env bash
# Shared helpers for the k8s one-shot runners. Sourced, never executed.
# shellcheck shell=bash

set -euo pipefail

K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$K8S_DIR/.." && pwd)"

# shellcheck disable=SC1091
source "$K8S_DIR/config.env"

WORKSPACE_HOST="$K8S_DIR/.workspace"
CACHE_HOST="$K8S_DIR/.cache"
ARTIFACTS_HOST="$K8S_DIR/.artifacts"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

need() {
    local hint="${2:-}"
    command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH${hint:+ ($hint)}"
}

# kubectl pinned to the cluster context from config.env
kc() { "$KUBECTL" --context "$KUBE_CONTEXT" "$@"; }

colima_vm() { colima -p "$COLIMA_PROFILE" "$@"; }

# Start colima (and thus k3s) if it is not running yet, then verify the API.
ensure_cluster() {
    need "$KUBECTL" "set KUBECTL=/path/to/kubectl in k8s/config.env if kubectl is elsewhere"
    if ! command -v colima >/dev/null 2>&1; then
        die "'colima' not found on PATH (brew install colima)"
    fi

    if ! colima_vm status >/dev/null 2>&1; then
        info "starting colima VM '$COLIMA_PROFILE' (docker+k3s); first boot can take a few minutes"
        colima_vm start
    fi

    if ! kc get --raw=/healthz --request-timeout=10s >/dev/null 2>&1; then
        die "cannot reach k3s context '$KUBE_CONTEXT' (is the colima runtime docker+k3s? try: colima status)"
    fi
}

ensure_namespace() {
    kc get namespace "$NAMESPACE" >/dev/null 2>&1 || kc create namespace "$NAMESPACE" >/dev/null
}

# Which container runtime does k3s use? Colima's k3s profile can run with
# the VM's docker daemon as CRI ("docker") or embedded containerd.
cluster_runtime() {
    if kc get nodes -o jsonpath='{range .items[*]}{.status.nodeInfo.containerRuntimeVersion}{"\n"}{end}' 2>/dev/null | grep -q '^docker://'; then
        echo docker
    else
        echo containerd
    fi
}

# Does the toolchain image exist where the cluster can see it?
image_in_cluster() {
    local img="$1"
    case "$(cluster_runtime)" in
        docker)
            docker image inspect "$img" >/dev/null 2>&1
            ;;
        containerd)
            colima_vm ssh -- sudo crictl --runtime-endpoint unix:///run/k3s/containerd/containerd.sock images 2>/dev/null | grep -qF "${img%%:*}"
            ;;
    esac
}

# Render a template by substituting __KEY__ placeholders from "KEY=value" args.
render_template() {
    local template="$1" out="$2"
    shift 2
    local rendered
    rendered="$(cat "$template")"
    local pair key val
    for pair in "$@"; do
        key="${pair%%=*}"
        val="${pair#*=}"
        rendered="${rendered//__${key}__/${val}}"
    done
    if grep -q '__[A-Z_]*__' <<<"$rendered"; then
        warn "unresolved placeholders remain in $(basename "$out"): $(grep -o '__[A-Z_]*__' <<<"$rendered" | sort -u | tr '\n' ' ')"
    fi
    printf '%s\n' "$rendered" > "$out"
}

# DNS-1123-safe unique job name
job_name() {
    local prefix="$1"
    printf '%s-%s-%s' "$prefix" "$(date +%Y%m%d-%H%M%S)" "$RANDOM"
}

# Print only the new tail of a pod's log (byte-offset diff against log_file)
# and store the full log. Tolerates API blips: every poll refetches fully.
follow_logs() {
    local pod="$1" log_file="$2"
    local chunk stored delta
    chunk="$(kc logs -n "$NAMESPACE" "$pod" 2>/dev/null || true)"
    [ -n "$chunk" ] || return 0
    chunk="$chunk"$'\n'
    if [ -n "$log_file" ] && [ -f "$log_file" ]; then
        stored="$(wc -c <"$log_file" | tr -d '[:space:]')"
        stored="${stored:-0}"
        delta="${chunk:stored}"
        [ -n "$delta" ] && printf '%s\n' "$delta"
        printf '%s\n' "$chunk" >"$log_file"
    else
        printf '%s\n' "$chunk"
    fi
}

# Wait until the Job finishes, following its logs incrementally, then report
# the outcome. Short polls survive flaky connections to the cluster API.
# Sets JOB_RESULT=success|failure|deadline on return.
stream_job_logs_and_wait() {
    local name="$1" deadline="$2" log_file="$3"
    local waited=0 pod="" phase="" types="" cond_waited=0

    while (( waited < deadline )); do
        pod="$(kc get pods -n "$NAMESPACE" -l job-name="$name" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
        [ -n "$pod" ] && break
        sleep 5
        waited=$((waited + 5))
    done
    [ -n "$pod" ] || { JOB_RESULT=deadline; return 1; }

    info "following logs from pod/$pod"
    : >"$log_file"

    while :; do
        follow_logs "$pod" "$log_file"
        phase="$(kc get pod -n "$NAMESPACE" "$pod" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
        case "$phase" in
            Succeeded | Failed) break ;;
        esac
        sleep 3
        waited=$((waited + 3))
        (( waited < deadline )) || break
    done
    follow_logs "$pod" "$log_file"

    # Job conditions can lag the pod's terminal state (FailureTarget first);
    # give the controller a short window instead of trusting a single read.
    while (( cond_waited < 120 )); do
        types="$(kc get job -n "$NAMESPACE" "$name" -o jsonpath='{.status.conditions[*].type}' 2>/dev/null || true)"
        case "$types" in
            *Complete*) JOB_RESULT=success; return 0 ;;
            *Failed*) JOB_RESULT=failure; return 1 ;;
        esac
        sleep 5
        cond_waited=$((cond_waited + 5))
    done

    # Fall back to the pod's own verdict if conditions never materialised.
    case "$phase" in
        Succeeded) JOB_RESULT=success; return 0 ;;
        Failed) JOB_RESULT=failure; return 1 ;;
    esac
    JOB_RESULT=deadline
    return 1
}

delete_job() {
    local name="$1"
    kc delete job -n "$NAMESPACE" "$name" --ignore-not-found >/dev/null 2>&1 || true
}
