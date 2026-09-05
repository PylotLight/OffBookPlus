# Local k3s build & test space

Builds OffBook+ as one-shot Kubernetes Jobs on the local k3s cluster (the
colima VM with `docker+k3s` runtime), so no JDK/Android SDK is needed on the
Mac. Nothing runs between builds: each build is a Job that appears, runs
`./gradlew`, and disappears.

```
k8s/
├── Dockerfile              toolchain image: Temurin JDK 21 + Android SDK (API 37)
├── config.env              cluster/namespace/image/watch defaults; override per call
├── templates/              Job manifests rendered by the scripts
└── scripts/
    ├── image.sh            build image with colima docker -> import if needed
    ├── build.sh            sync sources, run Gradle in a Job, copy artifacts back
    └── deploy.sh           adb to the watch over Wi-Fi: install / launch / screenshot / logcat
```

## Prerequisites

- `colima` VM profile with runtime `docker+k3s` (scripts start it if stopped)
- `kubectl` able to reach the `colima` context (`KUBECTL=` override supported)
- `rsync` (ships with macOS)

All scripts source `k8s/config.env`; any variable can be overridden inline,
e.g. `NAMESPACE=builds ./build.sh`.

## Download budget

| What | Transfer | Notes |
|---|---|---|
| Toolchain image (`image.sh`) | ~1 GB | JDK base ~400MB, cmdline-tools ~140MB, platforms 37.0+37.1 ~140MB, build-tools ~60MB, rest small. ~2.6 GB on disk |
| amd64 compat layer | ~40 MB | re-bakes only the last image layer |
| First `build.sh` | ~400–600 MB | Gradle 9.7 dist (~135MB) + dependencies; lands in `k8s/.cache/gradle`, reused forever after |
| Later builds | 0 | fully offline-capable |

## One-time setup

```bash
./k8s/scripts/image.sh
```

Builds `localhost/offbook-android-builder:jdk21` with the colima docker
daemon. If this k3s runs on that same docker daemon (colima default), the
image is immediately usable; otherwise the script exports and imports it
into k3s containerd automatically. Re-run whenever the Dockerfile changes.

## Building

```bash
# default task: assembleDebug
./k8s/scripts/build.sh

# signed release APK (keystore creds pass through from local.properties)
./k8s/scripts/build.sh --task assembleRelease

# arbitrary gradle invocation
./k8s/scripts/build.sh --task :app:lintDebug -- --stacktrace

# iterate without re-syncing unchanged sources
./k8s/scripts/build.sh --no-sync
```

What happens:

1. Sources are rsynced into `k8s/.workspace/` (repo's `local.properties`,
   `.git`, and build outputs are excluded). A container-side
   `local.properties` is written with `sdk.dir=/opt/android-sdk` plus the
   signing keys copied from your real one — secrets stay on this Mac.
2. A Job mounts `.workspace` at `/workspace` and `k8s/.cache` at `/cache`
   (both hostPath volumes through colima's shared home mount), so Gradle and
   dependency caches persist across builds.
3. Logs stream incrementally (survives API blips) and are saved to
   `k8s/.cache/last-build.log`.
4. On success, APK/AAB outputs are rsynced back into `app/build/outputs/`
   and the Job is deleted (`--keep-job` to inspect a failure instead).

Memory is pre-tuned for the colima default VM (6 GiB, no swap): container
limit 5000Mi with a single-JVM profile (Gradle heap 3072m, Kotlin compiled
in-process, 2 workers) via `GRADLE_OPTS_VALUE`. That profile covers both
`assembleDebug` and R8-minified `assembleRelease`; the stock 2g+2g daemon
heaps OOMKill during dexing/minification.

## Testing on the watch

An in-cluster emulator is not an option here — colima on Apple Silicon has
no nested KVM. Instead, deploy straight to the watch over Wi-Fi ADB:

On the watch: **Settings > Developer options > ADB debugging**, enable
**Debug over Wi-Fi**, then note the IP/port and pairing code.

Deploy Jobs wait for the watch to appear — retries run for `--wait` seconds
(default 180, see `WATCH_WAIT_SECONDS`) — and macOS gets a notification plus
terminal bell with the outcome, so you can start a deploy before picking up
the watch:

```bash
# once, when pairing is requested:
./k8s/scripts/deploy.sh --ip 192.168.1.50 --pair 123456 4423

# install newest built apk and open the app:
./k8s/scripts/deploy.sh --ip 192.168.1.50 --launch

# round-display screenshot -> k8s/.artifacts/*.png
./k8s/scripts/deploy.sh --ip 192.168.1.50 --action shot

# recent log lines for the app / crashes
./k8s/scripts/deploy.sh --ip 192.168.1.50 --action logcat

# just probe connectivity and notify when the watch answers
./k8s/scripts/deploy.sh --ip 192.168.1.50 --action wait --wait 600

# clean uninstall
./k8s/scripts/deploy.sh --ip 192.168.1.50 --action uninstall
```

Set `WATCH_IP` in `k8s/config.env` to skip the `--ip` flag.

### Wired (USB) watch

`test.sh` presets also work with the watch plugged into the Mac: local adb
sees USB devices, cluster Jobs cannot, so a detected USB watch is used
directly (install/launch/shot/log/tap/swipe/key/shell/uninstall). `fix` over
USB additionally re-pins Wi-Fi ADB at `:5555` and saves it to
`k8s/.watch.local` — the quickest repair whenever wireless discovery gets
stuck. `WATCH_MODE=wired|wireless` forces a path (default: auto, prefer
wired). Requires `adb` on the Mac (`brew install android-platform-tools`).

## Implementation notes

- **aapt2 under emulation**: Google publishes no arm64 Linux aapt2; the
  Dockerfile installs the amd64 libc/libstdc++/zlib set so both build-tools'
  and AGP's maven aapt2 run through qemu-user. This makes resource steps
  slower than native but fully functional.
- **Image vs runtime**: colima's k3s may use either its docker daemon or
  embedded containerd as CRI; scripts detect which and import the image only
  when required.

## Troubleshooting

- **Job stuck in `Pending`** — check that the hostPath exists inside the VM:
  `colima ssh -- ls /Users/$USER/Documents/AndroidStudioProjects/OffBook+/k8s`.
- **Permission errors on `/cache` or `/workspace`** — run
  `chmod -R a+rwX k8s/.cache k8s/.workspace` on the Mac.
- **AAPT2 daemon startup failed / transform errors after a crash** — clear
  corrupted caches: `rm -rf k8s/.cache/gradle/caches/*/transforms`.
- **OOMKilled** — lower heaps via `GRADLE_OPTS_VALUE` or raise
  `JOB_MEM_LIMIT` (mind the VM's physical RAM).
- **ImageNotFound** — rerun `image.sh` (e.g. after pruning docker).
