#!/usr/bin/env bash
# On-demand on-device testing against a WearOS watch. Thin preset layer over
# deploy.sh: every command is a one-shot Job that runs and deletes itself —
# nothing persists on the cluster between commands.
#
# One-time:   ./k8s/scripts/test.sh setup          (saves watch IP, gitignored)
# Then:
#   ./k8s/scripts/test.sh install                # newest debug apk + launch
#   ./k8s/scripts/test.sh launch                 # wake + launch debug app
#   ./k8s/scripts/test.sh shot                   # screenshot -> k8s/.artifacts/
#   ./k8s/scripts/test.sh log [n]                # app-filtered logcat
#   ./k8s/scripts/test.sh tap 250 250            # input tap
#   ./k8s/scripts/test.sh swipe x1 y1 x2 y2 [ms]
#   ./k8s/scripts/test.sh key 4                  # keyevent
#   ./k8s/scripts/test.sh shell "cmd"            # arbitrary adb shell
#   ./k8s/scripts/test.sh forget                 # clear saved watch config
#
# The watch sleeps hard (~10s); press the crown, then run a command. The
# wireless-debugging port is remembered after the first connect; after a
# watch reboot the port rotates and commands auto-fallback to discovery.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

WATCH_LOCAL="$K8S_DIR/.watch.local"
[ -f "$WATCH_LOCAL" ] && # shellcheck disable=SC1090
    source "$WATCH_LOCAL"

CMD="${1:-help}"
shift || true

APP_ID_DEBUG="$APP_ID.debug"

usage() { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }
[ "$CMD" = help ] && usage

need_watch_ip() {
    [ -n "${WATCH_IP:-}" ] || die "watch IP unknown; run: $0 setup"
}

# Run deploy.sh with the preset defaults. Falls back to port auto-discovery
# once if a remembered port no longer works (e.g. after a watch reboot).
run_deploy() {
    need_watch_ip
    if [ -n "${WATCH_PORT:-}" ]; then
        if "$SCRIPT_DIR/deploy.sh" --ip "$WATCH_IP" --port "$WATCH_PORT" "$@"; then
            grep -q "^WATCH_PORT=" "$WATCH_LOCAL" 2>/dev/null ||
                printf "WATCH_PORT=%s\n" "$WATCH_PORT" >>"$WATCH_LOCAL"
            return 0
        fi
        warn "remembered port $WATCH_PORT failed; rediscovering"
        "$SCRIPT_DIR/deploy.sh" --ip "$WATCH_IP" "$@" || return 1
        return 0
    fi
    "$SCRIPT_DIR/deploy.sh" --ip "$WATCH_IP" "$@" || return 1
}

save_port_from_log() {
    local p
    p="$(grep -oE 'watch found at [0-9.]+:[0-9]+' "$CACHE_HOST/last-deploy.log" 2>/dev/null |
        tail -1 | grep -oE '[0-9]+$' || true)"
    if [ -n "$p" ] && [ "$p" != "${WATCH_PORT:-}" ]; then
        if grep -q "^WATCH_PORT=" "$WATCH_LOCAL" 2>/dev/null; then
            sed -i '' "s/^WATCH_PORT=.*/WATCH_PORT=$p/" "$WATCH_LOCAL"
        else
            printf "WATCH_PORT=%s\n" "$p" >>"$WATCH_LOCAL"
        fi
        info "remembered port $p for next time"
    fi
}

case "$CMD" in
    setup)
        printf "watch IP (e.g. 192.168.1.106): "
        read -r ip
        [[ "$ip" =~ ^[0-9.]+$ ]] || die "not an IP: $ip"
        printf "WATCH_IP=%s\n" "$ip" >"$WATCH_LOCAL"
        info "saved to ${WATCH_LOCAL#$REPO_ROOT/} (gitignored)"
        ;;

    forget)
        rm -f "$WATCH_LOCAL"
        info "cleared saved watch config"
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
        run_deploy --action install --apk "$APK_SRC" --launch --app-id "$APP_ID_DEBUG" &&
            save_port_from_log
        ;;

    launch)
        run_deploy --action launch --app-id "$APP_ID_DEBUG" && save_port_from_log
        ;;

    shot)
        run_deploy --action shot --app-id "$APP_ID_DEBUG" && save_port_from_log
        info "screenshots in k8s/.artifacts/"
        ;;

    log)
        run_deploy --action logcat && save_port_from_log
        ;;

    uninstall)
        run_deploy --action uninstall --app-id "$APP_ID_DEBUG" && save_port_from_log
        ;;

    tap)
        [ $# -ge 2 ] || die "usage: $0 tap X Y"
        run_deploy --action shell --app-id "$APP_ID_DEBUG" --shell-cmd "input keyevent KEYCODE_WAKEUP; input tap $1 $2" &&
            save_port_from_log
        ;;

    swipe)
        [ $# -ge 4 ] || die "usage: $0 swipe X1 Y1 X2 Y2 [ms]"
        run_deploy --action shell --app-id "$APP_ID_DEBUG" \
            --shell-cmd "input keyevent KEYCODE_WAKEUP; input swipe $1 $2 $3 $4 ${5:-300}" &&
            save_port_from_log
        ;;

    key)
        [ $# -ge 1 ] || die "usage: $0 key KEYCODE"
        run_deploy --action shell --app-id "$APP_ID_DEBUG" --shell-cmd "input keyevent $1" &&
            save_port_from_log
        ;;

    shell)
        [ $# -ge 1 ] || die 'usage: $0 shell "watch command"'
        run_deploy --action shell --app-id "$APP_ID_DEBUG" --shell-cmd "$1" && save_port_from_log
        ;;

    *)
        die "unknown command: $CMD (try: $0 help)"
        ;;
esac
