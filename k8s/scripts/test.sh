#!/usr/bin/env bash
# On-demand on-device testing against a WearOS watch. Thin preset layer over
# deploy.sh: every command is a one-shot Job that runs and deletes itself —
# nothing persists on the cluster between commands.
#
# One-time:   ./k8s/scripts/test.sh setup          (saves watch IP, gitignored)
# Then:
#   ./k8s/scripts/test.sh fix                    # after a watch reboot: scan + re-pin port
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
# Wired (USB) also works: with the watch plugged into this Mac, presets run
# adb locally instead of via cluster Jobs (USB never reaches the colima VM),
# and `fix` over USB re-pins Wi-Fi ADB at :5555. WATCH_MODE=wired|wireless
# forces a path; default auto prefers wired and falls back to Wi-Fi.
#
# The .debug build keeps its own screen on and Wi-Fi awake (DevKeepAwake),
# so once launched it stays reachable — no crown pressing mid-session. After
# a watch reboot: wake the watch, re-enable Debug over Wi-Fi, run `fix`,
# then `launch`.
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

usage() { sed -n '2,/^set -eo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0; }
[ "$CMD" = help ] && usage

need_watch_ip() {
    [ -n "${WATCH_IP:-}" ] || die "watch IP unknown; run: $0 setup"
}

save_watch_port() {
    local p="$1"
    if grep -q "^WATCH_PORT=" "$WATCH_LOCAL" 2>/dev/null; then
        sed -i '' "s/^WATCH_PORT=.*/WATCH_PORT=$p/" "$WATCH_LOCAL"
    else
        printf "WATCH_PORT=%s\n" "$p" >>"$WATCH_LOCAL"
    fi
    info "remembered port $p"
}

preflight_watch_warning() {
    # Give the user a moment to wake the watch — addresses "watch was off,
    # need warning before that anytime". Skipped when TEST_SH_QUIET=1.
    if [ "${TEST_SH_QUIET:-0}" != "1" ]; then
        warn "about to contact watch at ${WATCH_IP}:${WATCH_PORT:-<auto>} — please ensure screen is ON (tap crown) — continuing in 3s (TEST_SH_QUIET=1 to skip)"
        sleep 3
    fi
}

# Run deploy.sh with the preset defaults. Falls back to port auto-discovery
# once if a remembered port no longer works (e.g. after a watch reboot).
# Discovered ports are saved even when the action itself fails.
run_deploy() {
    need_watch_ip
    preflight_watch_warning
    local rc=0
    if [ -n "${WATCH_PORT:-}" ]; then
        "$SCRIPT_DIR/deploy.sh" --ip "$WATCH_IP" --port "$WATCH_PORT" "$@" || rc=$?
        save_port_from_log
        if [ "$rc" -eq 0 ]; then
            return 0
        fi
        if grep -q "== watch online" "$CACHE_HOST/last-deploy.log" 2>/dev/null; then
            return "$rc"
        fi
        warn "remembered port $WATCH_PORT failed; rediscovering"
    fi
    rc=0
    "$SCRIPT_DIR/deploy.sh" --ip "$WATCH_IP" "$@" || rc=$?
    save_port_from_log
    return "$rc"
}

save_port_from_log() {
    local p
    p="$(grep -oE '(DISCOVERED_PORT=|watch found at [0-9.]+:)[0-9]+' "$CACHE_HOST/last-deploy.log" 2>/dev/null |
        tail -1 | grep -oE '[0-9]+$' || true)"
    [ -n "$p" ] && [ "$p" != "${WATCH_PORT:-}" ] && save_watch_port "$p"
    return 0
}

# Wired path: adb on this Mac sees a USB watch directly. Detects an attached
# watch and sets WIRED_SERIAL; returns 1 (fall back to wireless) otherwise.
LOCAL_ADB="$(command -v adb || true)"
WATCH_MODE="${WATCH_MODE:-auto}"

use_wired() {
    [ "$WATCH_MODE" = wireless ] && return 1
    if [ -z "$LOCAL_ADB" ]; then
        [ "$WATCH_MODE" = wired ] && die "no adb on this Mac (brew install android-platform-tools)"
        return 1
    fi
    local serial state
    while read -r serial state _; do
        case "$serial" in ''|*:*) continue ;; esac
        case "$state" in
            device) WIRED_SERIAL="$serial"; return 0 ;;
            unauthorized) die "wired watch ($serial) unauthorized - accept the USB debugging prompt on the watch" ;;
        esac
    done < <("$LOCAL_ADB" devices 2>/dev/null | tail -n +2)
    [ "$WATCH_MODE" = wired ] && die "no USB watch attached (WATCH_MODE=wired)"
    return 1
}

adbw() { "$LOCAL_ADB" -s "$WIRED_SERIAL" "$@"; }

# While docked, OPPO's HeyOffload draws a full-screen charging overlay that
# eats input and hides the app. A side-button keyevent dismisses it; only
# sent when the overlay window is actually present.
dismiss_charging_overlay() {
    adbw shell 'dumpsys window | grep -q SysUI.Charging && for k in 264 265 266 267; do input keyevent $k; done' >/dev/null 2>&1 || true
}

save_watch_ip() {
    local ip="$1"
    if grep -q "^WATCH_IP=" "$WATCH_LOCAL" 2>/dev/null; then
        sed -i '' "s/^WATCH_IP=.*/WATCH_IP=$ip/" "$WATCH_LOCAL"
    else
        printf "WATCH_IP=%s\n" "$ip" >"$WATCH_LOCAL"
    fi
}

run_preset_shell() {
    if use_wired; then
        dismiss_charging_overlay
        adbw shell "$1"
    else
        run_deploy --action shell --app-id "$APP_ID_DEBUG" --shell-cmd "$1"
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

    fix)
        if use_wired; then
            info "wired watch ($WIRED_SERIAL): re-pinning Wi-Fi ADB over USB"
            wip="$(adbw shell ip -f inet addr show wlan0 2>/dev/null | awk '/inet /{split($2,a,"/"); print a[1]; exit}')"
            [ -n "$wip" ] || warn "watch has no wlan0 IP (Wi-Fi off?); wired actions still work"
            adbw tcpip 5555 || true
            sleep 3
            if [ -n "$wip" ]; then
                "$LOCAL_ADB" connect "$wip:5555" >/dev/null 2>&1 || true
                sleep 2
                if [ "$("$LOCAL_ADB" -s "$wip:5555" get-state 2>/dev/null)" = "device" ]; then
                    save_watch_ip "$wip"
                    save_watch_port 5555
                    info "wireless ADB re-pinned at $wip:5555 (valid until the next watch reboot)"
                else
                    warn "could not reach $wip:5555; wired actions still work"
                fi
            fi
        else
            need_watch_ip
            info "scanning for the watch's current port (mDNS, then sweep)"
            "$SCRIPT_DIR/deploy.sh" --ip "$WATCH_IP" --action discover ||
                die "discovery failed (Debug over Wi-Fi on? crown pressed? or plug the watch in via USB and rerun)"
            local_port="$(grep -oE 'DISCOVERED_PORT=[0-9]+' "$CACHE_HOST/last-deploy.log" | tail -1 | cut -d= -f2)"
            [ -n "$local_port" ] || die "no port found in discovery log"
            info "watch is at $WATCH_IP:$local_port; pinning tcpip 5555"
            if "$SCRIPT_DIR/deploy.sh" --ip "$WATCH_IP" --port "$local_port" --action adbcmd \
                --adb-cmd 'set +e
adb -s "$target" tcpip 5555
sleep 4
adb connect "$WATCH_IP:5555"
sleep 2
st="$(adb -s "$WATCH_IP:5555" get-state 2>/dev/null)"
echo "PINNED_STATE=$st"
[ "$st" = device ]' &&
                grep -q "PINNED_STATE=device" "$CACHE_HOST/last-deploy.log"; then
                save_watch_port 5555
                info "port fixed at 5555 until the next watch reboot"
            else
                warn "could not pin 5555; remembering discovered port $local_port instead"
                save_watch_port "$local_port"
            fi
        fi
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
        if use_wired; then
            info "installing $(basename "$APK_SRC") over USB ($WIRED_SERIAL)"
            out="$(adbw install -r "$APK_SRC" 2>&1)" || true
            echo "$out"
            if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
                warn "signature changed; removing stale app and retrying"
                adbw uninstall "$APP_ID_DEBUG" >/dev/null 2>&1 || true
                adbw install -r "$APK_SRC" || die "adb install failed"
            fi
            adbw shell am start -n "$APP_ID_DEBUG/$MAIN_ACTIVITY"
        else
            info "installing $(basename "$APK_SRC")"
            run_deploy --action install --apk "$APK_SRC" --launch --app-id "$APP_ID_DEBUG"
        fi
        ;;

    launch)
        if use_wired; then
            adbw shell input keyevent KEYCODE_WAKEUP
            dismiss_charging_overlay
            adbw shell am start -n "$APP_ID_DEBUG/$MAIN_ACTIVITY"
        else
            run_deploy --action launch --app-id "$APP_ID_DEBUG"
        fi
        ;;

    shot)
        if use_wired; then
            mkdir -p "$ARTIFACTS_HOST"
            adbw shell input keyevent KEYCODE_WAKEUP || true
            dismiss_charging_overlay
            sleep 1
            out="$ARTIFACTS_HOST/watch-$(date +%Y%m%d-%H%M%S).png"
            adbw exec-out screencap -p >"$out"
            info "screenshot: ${out#"$REPO_ROOT"/}"
        else
            run_deploy --action shot --app-id "$APP_ID_DEBUG"
            info "screenshots in k8s/.artifacts/"
        fi
        ;;

    log)
        if use_wired; then
            adbw logcat -d -t 500 | grep -iE "offbook|androidruntime|cr_" || adbw logcat -d -t 200
        else
            run_deploy --action logcat
        fi
        ;;

    uninstall)
        if use_wired; then
            adbw uninstall "$APP_ID_DEBUG"
        else
            run_deploy --action uninstall --app-id "$APP_ID_DEBUG"
        fi
        ;;

    tap)
        [ $# -ge 2 ] || die "usage: $0 tap X Y"
        run_preset_shell "input keyevent KEYCODE_WAKEUP; input tap $1 $2"
        ;;

    swipe)
        [ $# -ge 4 ] || die "usage: $0 swipe X1 Y1 X2 Y2 [ms]"
        run_preset_shell "input keyevent KEYCODE_WAKEUP; input swipe $1 $2 $3 $4 ${5:-300}"
        ;;

    key)
        [ $# -ge 1 ] || die "usage: $0 key KEYCODE"
        run_preset_shell "input keyevent $1"
        ;;

    shell)
        [ $# -ge 1 ] || die 'usage: $0 shell "watch command"'
        run_preset_shell "$1"
        ;;

    *)
        die "unknown command: $CMD (try: $0 help)"
        ;;
esac
