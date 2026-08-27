# AGENTS.md

Rules for AI agents working in this repo.

## Build & test environment

- **No local JDK/Android SDK on this Mac.** All Gradle builds run as one-shot
  Jobs in the local colima/k3s cluster (context `colima`, namespace
  `offbook-build`). Never attempt `./gradlew` directly on the Mac.
- Build: `k8s/scripts/build.sh --task assembleDebug` (see `k8s/README.md` for
  flags). Jobs self-delete; artifacts land in `k8s/.artifacts/`, logs in
  `k8s/.cache/last-build.log`.
- Device testing: use the `k8s/scripts/test.sh` presets (`fix`, `install`,
  `launch`, `shot`, `log`, `tap`, `swipe`, `key`, `shell`, `uninstall`), not
  ad-hoc one-liners. Set `TEST_SH_QUIET=1` to skip the 3s preflight warning.

## Watch readiness protocol (IMPORTANT)

The test target is a physical Wear OS watch. **Its IP is never fixed** — it
changes with every network (home, hotel, hotspot). The last known address
lives in `k8s/.watch.local` (`WATCH_IP`/`WATCH_PORT`) and is only a hint;
treat it as stale whenever the network changed. Never hardcode IPs in docs,
scripts, or commands — always read `k8s/.watch.local` or rediscover.

Hard rules:

1. **Never assume the watch is ready or reachable.** Never start a deploy,
   port sweep, or any `test.sh`/`deploy.sh` action without first asking the
   user to ready the watch and receiving explicit confirmation.
2. To ready the watch the user must: be on a network reachable from the Mac,
   have the watch screen awake (Wi-Fi ADB dozes when the screen is off), and
   ADB debugging enabled. Ask, then wait.
3. **Wear OS ignores ICMP ping.** A failed ping proves nothing — use ARP
   entries and TCP connects as the reachability signal.
4. The remembered address rotates: the **port** changes on every adb restart
   and the **IP** changes on every network. If a connect fails with the watch
   confirmed awake, run `k8s/scripts/test.sh fix` (and only then) to
   rediscover the port. If the IP is stale (network changed since
   `k8s/.watch.local` was written), rediscover the watch's IP first — ARP
   scan of the local subnet or ask the user — and update `k8s/.watch.local`
   before running `fix`.
5. Wake the watch before any `run_deploy`-type operation; a black/1.9K
   screenshot means the screen was asleep — wake and retake.
6. If the watch is not reachable, do not loop or sweep repeatedly. Stop,
   report the state, and ask the user how to proceed (wait, change network,
   or defer testing).

## Release flow

- `master` + tag `v*.*.*` → GitHub Actions (`.github/workflows/ci.yml`) builds
  the signed release APK and publishes the GitHub Release. Local
  `assembleRelease` is not required (and OOMKills under the k8s memory limit).
- Debug builds install alongside release (`applicationIdSuffix .debug`).

## Conventions

- Kotlin, Wear OS Compose (material + material3 mix), Media3/ExoPlayer, Room.
- Playback order is owned by the app and persisted in the `playback_queue`
  table (ExoPlayer's internal shuffle order is never relied upon).
- Do not add code comments unless asked.

## Verified lessons (do not repeat)

- **End every device session with playback stopped.** Pause via
  `key KEYCODE_MEDIA_PAUSE`, then verify with a position-frozen check: two
  shots ~8s apart, timeline band must be pixel-identical. Never infer playback
  state from button glyphs.
- **Never trust vision-model defect claims** ("clipped", "overlapping").
  Verify with an objective pixel scan (python3 + PIL) before changing code;
  dark-on-dark regions produce routine false positives.
- The watch screen dozes within ~1 min of inactivity and silently swallows
  taps. After any idle gap: `key WAKEUP` + a small swipe before interacting.
- Screen is 466x466 px (~233dp). Don't trust mental dp/px math for round-screen
  fit — verify with a screenshot + pixel scan.
- `git status` before every commit: never stage `.idea/`, artifacts, or temp
  files.
- Verify third-party APIs against the exact pinned version tag on GitHub, not
  main-branch docs (e.g. Horologist components differ at 0.7.15).
