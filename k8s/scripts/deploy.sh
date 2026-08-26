#!/usr/bin/env bash
# Deploy/test builds on a WearOS watch over Wi-Fi using one-shot adb Jobs in
# the cluster (no Android tooling runs on this Mac).
#
# Usage: ./deploy.sh [--action install|launch|shot|logcat|uninstall|wait|pair|
#                     shell|adbcmd|discover]
#                    [--ip IP] [--port N|auto] [--apk PATH] [--launch]
#                    [--pair CODE PORT] [--wait SECONDS] [--shell-cmd CMD]
#                    [--adb-cmd CMD] [--keep-job] [-h]
#
# Port handling: --port auto (default) discovers the watch's rotating
# wireless-debugging port via mDNS/nmap sweep — needed after every watch
# reboot. Pass an explicit --port to skip discovery. Pairing keys persist in
# k8s/.cache, so pairing is only ever needed once per watch.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

ACTION="install"
IP="$WATCH_IP"
PORT=""
PAIR_CODE=""
PAIR_PORT=""
APK_REL=""
LAUNCH=0
KEEP_JOB=0
WAIT_SECONDS="$WATCH_WAIT_SECONDS"
SHELL_CMD=""
ADB_CMD=""

usage() { sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

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
    install | launch | shot | logcat | uninstall | connect | pair | wait | shell | adbcmd | discover) ;;
    *) die "invalid action '$ACTION'" ;;
esac
[ "$ACTION" != shell ] || [ -n "$SHELL_CMD" ] || die "--action shell requires --shell-cmd"
[ "$ACTION" != adbcmd ] || [ -n "$ADB_CMD" ] || die "--action adbcmd requires --adb-cmd"

ensure_cluster
ensure_namespace
mkdir -p "$CACHE_HOST" "$ARTIFACTS_HOST"

notify() {
    local title="$1" msg="$2"
    printf '\a'
    if command -v osascript >/dev/null 2>&1; then
        osascript -e "display notification \"$msg\" with title \"$title\" sound name \"Glass\"" >/dev/null 2>&1 || true
    fi
}

# Render, apply and await one deploy Job. Args: action port. Returns non-zero
# on failure; full output lands in $DEPLOY_LOG either way.
run_job() {
    local act="$1" prt="$2"
    local name rendered apk_in_container="/workspace/app/build/outputs/apk/debug/latest.apk"

    if [ "$act" = install ]; then
        if [ -n "$APK_REL" ]; then
            case "$APK_REL" in
                /*) APK_SRC="$APK_REL" ;;
                *) APK_SRC="$REPO_ROOT/$APK_REL" ;;
            esac
            [ -f "$APK_SRC" ] || die "apk not found: $APK_SRC"
            cp "$APK_SRC" "$WORKSPACE_HOST/.incoming.apk"
            apk_in_container="/workspace/.incoming.apk"
        else
            local newest
            newest="$(find "$WORKSPACE_HOST/app/build/outputs/apk" -name '*.apk' -type f -exec stat -f '%m %N' {} + 2>/dev/null \
                | sort -rn | head -1 | cut -d' ' -f2- || true)"
            [ -n "$newest" ] || die "no apk under $WORKSPACE_HOST/app/build/outputs; run build.sh first or pass --apk"
            info "using $(basename "$newest") ($(du -h "$newest" | cut -f1))"
            apk_in_container="/workspace${newest#"$WORKSPACE_HOST"}"
        fi
    fi

    name="$(job_name offbook-deploy)"
    rendered="$CACHE_HOST/$name.yaml"
    render_template "$K8S_DIR/templates/job-deploy.yaml" "$rendered" \
        "JOB_NAME=$name" \
        "NAMESPACE=$NAMESPACE" \
        "IMAGE=$IMAGE" \
        "DEADLINE=$((WAIT_SECONDS + 300))" \
        "ACTION=$act" \
        "WATCH_IP=$IP" \
        "WATCH_PORT=$prt" \
        "PAIR_CODE=$PAIR_CODE" \
        "PAIR_PORT=$PAIR_PORT" \
        "WAIT_SECONDS=$WAIT_SECONDS" \
        "APK=$apk_in_container" \
        "LAUNCH_AFTER_INSTALL=$([ "$LAUNCH" -eq 1 ] && echo yes || echo "")" \
        "APP_ID=$APP_ID" \
        "MAIN_ACTIVITY=$MAIN_ACTIVITY" \
        "SHELL_CMD_B64=$(printf '%s' "$SHELL_CMD" | base64)" \
        "ADB_CMD_B64=$(printf '%s' "$ADB_CMD" | base64)" \
        "WORKSPACE_HOSTPATH=$WORKSPACE_HOST" \
        "ARTIFACTS_HOSTPATH=$ARTIFACTS_HOST" \
        "CACHE_HOSTPATH=$CACHE_HOST"

    info "creating deploy job $name ($act -> $IP:${prt:-auto}, wait up to ${WAIT_SECONDS}s)"
    kc apply -f "$rendered"

    JOB_RESULT=unknown
    if ! stream_job_logs_and_wait "$name" "$((WAIT_SECONDS + 300))" "$DEPLOY_LOG"; then
        [ "$KEEP_JOB" -eq 1 ] || delete_job "$name"
        return 1
    fi
    [ "$KEEP_JOB" -eq 1 ] || delete_job "$name"
    rm -f "$rendered"
}

DEPLOY_LOG="$CACHE_HOST/last-deploy.log"

# Resolve the connect port unless the caller pinned one.
if [ -z "$PORT" ] || [ "$PORT" = auto ]; then
    info "discovering current watch port (mDNS, then port sweep)"
    if run_job discover ""; then
        PORT="$(grep -oE 'DISCOVERED_PORT=[0-9]+' "$DEPLOY_LOG" | head -1 | cut -d= -f2)"
    fi
    if [ -z "${PORT:-}" ]; then
        notify "OffBook+ deploy FAILED" "could not find watch $IP (Debug over Wi-Fi off?)"
        die "port discovery failed; enable Debug over Wi-Fi on the watch and retry"
    fi
    info "watch found at $IP:$PORT"
    [ "$ACTION" = discover ] && { info "done"; exit 0; }
fi

if run_job "$ACTION" "$PORT"; then
    if [ "$ACTION" = shot ]; then
        info "screenshots saved under ${ARTIFACTS_HOST#$REPO_ROOT/}"
    fi
    notify "OffBook+ deploy" "$ACTION completed on $IP:$PORT"
    info "done"
else
    status="${JOB_RESULT:-unknown}"
    case "$status" in
        failure)
            notify "OffBook+ deploy FAILED" "$ACTION did not complete; check terminal"
            die "deploy failed; see $DEPLOY_LOG"
            ;;
        deadline)
            notify "OffBook+ deploy TIMEOUT" "watch $IP:$PORT never connected (${WAIT_SECONDS}s)"
            die "timed out waiting for the deploy job"
            ;;
        *)
            notify "OffBook+ deploy FAILED" "unexpected end; see terminal"
            die "deploy ended unexpectedly"
            ;;
    esac
fi
