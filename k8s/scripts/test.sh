#!/usr/bin/env bash
# Interactive on-device test session for OffBook+ against a WearOS watch.
# Starts ONE long-lived adb pod, then every command execs into it (~1-2s).
#
# Quick start:
#   ./k8s/scripts/test.sh setup            # once: saves the watch IP
#   ./k8s/scripts/test.sh start            # session up, port auto-discovered
#   ./k8s/scripts/test.sh install          # newest debug apk + launch
#   ./k8s/scripts/test.sh shot             # screenshot (press crown first if asleep)
#
# All commands:
#   setup | start | stop | status | reconnect
#   install [debug|release|path.apk]
#   launch | shot [name] | ui | log [lines]
#   tap X Y | swipe X1 Y1 X2 Y2 [ms] | key N | shell "cmd"
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

# Per-watch overrides (gitignored): WATCH_IP=...
WATCH_LOCAL="$K8S_DIR/.watch.local"
[ -f "$WATCH_LOCAL" ] && # shellcheck disable=SC1090
    source "$WATCH_LOCAL"

CMD="${1:-help}"
shift || true

APP_ID_DEBUG="$APP_ID.debug"
SESSION_LABEL="app=offbook-adb-session"
SESSION_JOB="offbook-adb-session"

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
[ "$CMD" = help ] && usage

need_watch_ip() {
    [ -n "${WATCH_IP:-}" ] || die "watch IP unknown; run: $0 setup"
}

# exec into the session pod
sess() {
    local pod
    pod="$(kc get pods -n "$NAMESPACE" -l "$SESSION_LABEL" \
        --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    [ -n "$pod" ] || die "no running session; run: $0 start"
    kc exec -n "$NAMESPACE" "$pod" -- bash -c '
        set +e
        T="$(grep -oE "SESSION_TARGET=.*" /cache/adb-session-target 2>/dev/null | cut -d= -f2)"
        [ -n "$T" ] || { echo "session has no target; try: '"$0"' reconnect" >&2; exit 1; }
        adb -s "$T" "$@"
    ' inner "$@"
}

case "$CMD" in
    setup)
        printf "watch IP (e.g. 192.168.1.106): "
        read -r ip
        [[ "$ip" =~ ^[0-9.]+$ ]] || die "not an IP: $ip"
        printf "WATCH_IP=%s\n" "$ip" >"$WATCH_LOCAL"
        info "saved to ${WATCH_LOCAL#$REPO_ROOT/} (gitignored)"
        ;;

    start)
        need_watch_ip
        kc delete job -n "$NAMESPACE" "$SESSION_JOB" --ignore-not-found >/dev/null 2>&1 || true
        rendered="$CACHE_HOST/$SESSION_JOB.yaml"
        render_template "$K8S_DIR/templates/pod-adb-session.yaml" "$rendered" \
            "NAMESPACE=$NAMESPACE" \
            "IMAGE=$IMAGE" \
            "WATCH_IP=$WATCH_IP" \
            "WATCH_PORT=${WATCH_PORT:-}" \
            "WORKSPACE_HOSTPATH=$WORKSPACE_HOST" \
            "ARTIFACTS_HOSTPATH=$ARTIFACTS_HOST" \
            "CACHE_HOSTPATH=$CACHE_HOST"
        kc apply -f "$rendered" >/dev/null
        info "waiting for session to connect (port discovery can take ~2 min after a reboot)"
        for _ in $(seq 1 60); do
            if kc logs -n "$NAMESPACE" job/$SESSION_JOB 2>/dev/null | grep -q "session ready"; then
                kc logs -n "$NAMESPACE" job/$SESSION_JOB 2>/dev/null | grep -E "DISCOVERED_PORT|session ready"
                info "ready. try: $0 shot"
                exit 0
            fi
            phase="$(kc get pods -n "$NAMESPACE" -l "$SESSION_LABEL" -o jsonpath='{.items[0].status.phase}' 2>/dev/null || true)"
            [ "$phase" = "Failed" ] && break
            sleep 5
        done
        kc logs -n "$NAMESPACE" job/$SESSION_JOB 2>/dev/null | tail -5
        die "session did not become ready (see logs above)"
        ;;

    stop)
        kc delete job -n "$NAMESPACE" "$SESSION_JOB" --ignore-not-found
        info "session stopped"
        ;;

    status)
        kc get pods -n "$NAMESPACE" -l "$SESSION_LABEL" 2>/dev/null || info "no session"
        sess devices -l
        ;;

    reconnect)
        need_watch_ip
        out="$(sess /cache/session-connect.sh "$WATCH_IP" "${WATCH_PORT:-}" || true)"
        echo "$out"
        newtarget="$(grep -oE 'SESSION_TARGET=.*' <<<"$out" | cut -d= -f2)"
        [ -n "$newtarget" ] || die "reconnect failed (watch rebooted? Debug over Wi-Fi back on?)"
        sess "echo 'SESSION_TARGET=$newtarget' > /cache/adb-session-target"
        info "reconnected: $newtarget"
        ;;

    install)
        flavor="${1:-debug}"
        case "$flavor" in
            debug) dir="$REPO_ROOT/app/build/outputs/apk/debug" ;;
            release) dir="$REPO_ROOT/app/build/outputs/apk/release" ;;
            *) dir=""; APK_SRC="$flavor" ;;
        esac
        if [ -n "$dir" ]; then
            APK_SRC="$(ls -t "$dir"/*.apk 2>/dev/null | head -1)" || true
            [ -n "${APK_SRC:-}" ] || die "no apk in $dir; run build.sh first"
        fi
        [ -f "${APK_SRC:-}" ] || die "apk not found: ${APK_SRC:-}"
        info "installing $(basename "$APK_SRC")"
        cp "$APK_SRC" "$WORKSPACE_HOST/.incoming.apk"
        sess install -r /workspace/.incoming.apk
        info "launching"
        sess shell am start -n "$APP_ID_DEBUG/$MAIN_ACTIVITY" >/dev/null
        info "done"
        ;;

    launch)
        sess shell input keyevent KEYCODE_WAKEUP
        sess shell am start -n "$APP_ID_DEBUG/$MAIN_ACTIVITY" >/dev/null && info "launched $APP_ID_DEBUG"
        ;;

    shot)
        name="${1:-watch-$(date +%Y%m%d-%H%M%S)}"
        sess shell input keyevent KEYCODE_WAKEUP
        sess exec-out screencap -p >"$ARTIFACTS_HOST/$name.png"
        ls -la "$ARTIFACTS_HOST/$name.png"
        info "saved k8s/.artifacts/$name.png"
        ;;

    ui)
        sess shell uiautomator dump /sdcard/ui.xml
        sess shell cat /sdcard/ui.xml >"$ARTIFACTS_HOST/ui.xml"
        info "saved k8s/.artifacts/ui.xml"
        ;;

    log)
        sess logcat -d -t "${1:-300}" | grep -iE "offbook|androidruntime|cr_" || sess logcat -d -t 100
        ;;

    tap)
        [ $# -ge 2 ] || die "usage: $0 tap X Y"
        sess shell input tap "$1" "$2"
        ;;

    swipe)
        [ $# -ge 4 ] || die "usage: $0 swipe X1 Y1 X2 Y2 [ms]"
        sess shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"
        ;;

    key)
        [ $# -ge 1 ] || die "usage: $0 key KEYCODE"
        sess shell input keyevent "$1"
        ;;

    shell)
        [ $# -ge 1 ] || die 'usage: $0 shell "watch command"'
        sess shell "$1"
        ;;

    *)
        die "unknown command: $CMD (see --help: $0 help)"
        ;;
esac
