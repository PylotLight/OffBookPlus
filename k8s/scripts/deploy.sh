#!/usr/bin/env bash
# Deploy/test the built APK on a WearOS watch over Wi-Fi using a one-shot
# adb Job in the cluster (no Android tooling runs on this Mac).
#
# Usage: ./deploy.sh [--action install|launch|shot|logcat|uninstall|wait|pair|shell]
#                    [--ip IP] [--port N] [--apk PATH] [--launch]
#                    [--pair CODE PORT] [--wait SECONDS] [--shell-cmd CMD]
#                    [--keep-job] [-h]
#
# Jobs retry adb connect for --wait seconds (default from config.env) so you
# can start a deploy before picking up the watch; macOS gets a notification
# with the outcome.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

ACTION="install"
IP="$WATCH_IP"
PORT="$WATCH_PORT"
PAIR_CODE=""
PAIR_PORT=""
APK_REL=""
LAUNCH=0
KEEP_JOB=0
WAIT_SECONDS="$WATCH_WAIT_SECONDS"

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
    case "$1" in
        --action) ACTION="$2"; shift 2 ;;
        --ip) IP="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --pair)
            [ $# -ge 3 ] || die "--pair requires a pairing CODE and PORT"
            PAIR_CODE="$2"; PAIR_PORT="$3"; shift 3
            ;;
        --apk) APK_REL="$2"; shift 2 ;;
        --launch) LAUNCH=1; shift ;;
        --keep-job) KEEP_JOB=1; shift ;;
        --wait) WAIT_SECONDS="$2"; shift 2 ;;
        --shell-cmd) SHELL_CMD="$2"; shift 2 ;;
        --adb-cmd) ADB_CMD="$2"; shift 2 ;;
        -h | --help) usage ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done

[ -n "$IP" ] || die "watch IP not set; pass --ip <watch-ip> or set WATCH_IP in k8s/config.env"
[[ "$WAIT_SECONDS" =~ ^[0-9]+$ ]] || die "--wait expects seconds, got '$WAIT_SECONDS'"

case "$ACTION" in
    install | launch | shot | logcat | uninstall | connect | pair | wait | shell | adbcmd) ;;
    *) die "invalid action '$ACTION'" ;;
esac
if [ "$ACTION" = shell ]; then
    [ -n "${SHELL_CMD:-}" ] || die "--action shell requires --shell-cmd"
fi
if [ "$ACTION" = adbcmd ]; then
    [ -n "${ADB_CMD:-}" ] || die "--action adbcmd requires --adb-cmd"
fi

ensure_cluster
ensure_namespace

# Resolve which APK to install: explicit path, else newest build output.
APK_IN_CONTAINER="/workspace/app/build/outputs/apk/debug/latest.apk"
if [ "$ACTION" = install ]; then
    if [ -n "$APK_REL" ]; then
        case "$APK_REL" in
            /*) APK_SRC="$APK_REL" ;;
            *) APK_SRC="$REPO_ROOT/$APK_REL" ;;
        esac
        [ -f "$APK_SRC" ] || die "apk not found: $APK_SRC"
        cp "$APK_SRC" "$WORKSPACE_HOST/.incoming.apk"
        APK_IN_CONTAINER="/workspace/.incoming.apk"
    else
        newest="$(find "$WORKSPACE_HOST/app/build/outputs/apk" -name '*.apk' -type f -exec stat -f '%m %N' {} + 2>/dev/null \
            | sort -rn | head -1 | cut -d' ' -f2- || true)"
        [ -n "$newest" ] || die "no apk under $WORKSPACE_HOST/app/build/outputs; run ../build.sh first or pass --apk"
        info "using $(basename "$newest") ($(du -h "$newest" | cut -f1))"
        APK_IN_CONTAINER="/workspace${newest#"$WORKSPACE_HOST"}"
    fi
fi

JOB_NAME="$(job_name offbook-deploy)"
RENDERED="$CACHE_HOST/$JOB_NAME.yaml"
DEPLOY_LOG="$CACHE_HOST/last-deploy.log"
mkdir -p "$CACHE_HOST" "$ARTIFACTS_HOST"

DEADLINE=$((WAIT_SECONDS + 300))

render_template "$K8S_DIR/templates/job-deploy.yaml" "$RENDERED" \
    "JOB_NAME=$JOB_NAME" \
    "NAMESPACE=$NAMESPACE" \
    "IMAGE=$IMAGE" \
    "DEADLINE=$DEADLINE" \
    "ACTION=$ACTION" \
    "WATCH_IP=$IP" \
    "WATCH_PORT=$PORT" \
    "PAIR_CODE=$PAIR_CODE" \
    "PAIR_PORT=$PAIR_PORT" \
    "WAIT_SECONDS=$WAIT_SECONDS" \
    "APK=$APK_IN_CONTAINER" \
    "LAUNCH_AFTER_INSTALL=$([ "$LAUNCH" -eq 1 ] && echo yes || echo "")" \
    "APP_ID=$APP_ID" \
    "MAIN_ACTIVITY=$MAIN_ACTIVITY" \
    "SHELL_CMD_B64=$(printf '%s' "${SHELL_CMD:-}" | base64)" \
    "ADB_CMD_B64=$(printf '%s' "${ADB_CMD:-}" | base64)" \
    "WORKSPACE_HOSTPATH=$WORKSPACE_HOST" \
    "ARTIFACTS_HOSTPATH=$ARTIFACTS_HOST" \
    "CACHE_HOSTPATH=$CACHE_HOST"

info "creating deploy job $JOB_NAME ($ACTION -> $IP:$PORT, wait up to ${WAIT_SECONDS}s)"
kc apply -f "$RENDERED"

notify() {
    local title="$1" msg="$2"
    printf '\a'
    if command -v osascript >/dev/null 2>&1; then
        osascript -e "display notification \"$msg\" with title \"$title\" sound name \"Glass\"" >/dev/null 2>&1 || true
    fi
}

JOB_RESULT=unknown
if stream_job_logs_and_wait "$JOB_NAME" "$DEADLINE" "$DEPLOY_LOG"; then
    if [ "$KEEP_JOB" -eq 0 ]; then delete_job "$JOB_NAME"; fi
    rm -f "$RENDERED"
    if [ "$ACTION" = shot ]; then
        info "screenshots saved under ${ARTIFACTS_HOST#$REPO_ROOT/}"
    fi
    notify "OffBook+ deploy" "$ACTION completed on $IP:$PORT"
    info "done"
else
    case "${JOB_RESULT:-unknown}" in
        failure)
            notify "OffBook+ deploy FAILED" "$ACTION did not complete; check terminal"
            die "deploy failed; see logs above (job kept: $JOB_NAME)"
            ;;
        deadline)
            notify "OffBook+ deploy TIMEOUT" "watch $IP:$PORT never connected (${WAIT_SECONDS}s)"
            die "timed out waiting for the deploy job; is the watch reachable from the cluster network?"
            ;;
        *)
            notify "OffBook+ deploy FAILED" "unexpected end; job kept: $JOB_NAME"
            die "deploy ended unexpectedly; job kept for inspection: $JOB_NAME"
            ;;
    esac
fi
