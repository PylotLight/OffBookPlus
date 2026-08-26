# Toolchain image for building OffBook+ as one-shot Jobs on the local k3s
# (colima) cluster. Mirrors CI (.github/workflows/ci.yml): Temurin JDK 21.
#
# Kept deliberately minimal: the app targets Wear OS 5+ only (minSdk 34,
# compileSdk 37), so no emulator, system images, or NDK are included —
# colima on Apple Silicon cannot run an emulator anyway (no nested KVM);
# testing happens on real watches via adb.
#
# Licenses are pre-accepted so AGP can fetch any exact component revision it
# pins at build time (one-time ~50-70MB per missing piece, cached in the
# image afterwards only if baked in; runtime fetches land in the ephemeral
# SDK dir and repeat per build until baked here).
#
# Both stable API-37 minors are baked because AGP resolves compileSdk=37
# (app/build.gradle.kts) to one of them and which one cannot be confirmed
# offline; having both avoids any mid-build download. Licenses are
# pre-accepted as a further backstop for exact build-tools pins.
#
# Approximate fresh-download sizes: base JDK ~400MB, cmdline-tools ~140MB,
# platform-tools ~10MB, each platform ~70MB, build-tools ~60MB.

FROM eclipse-temurin:21-jdk-noble

ENV ANDROID_HOME=/opt/android-sdk \
    CMDLINE_TOOLS_VERSION=13114758
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
    "platform-tools" \
    "platforms;android-37.0" \
    "platforms;android-37.1" \
    "build-tools;37.0.0" \
    > /dev/null \
    && yes | sdkmanager --licenses > /dev/null

# aapt2 (build-tools and AGP's maven artifact alike) is published as x86_64
# ELF only; on an arm64 host it runs through qemu-user emulation, which needs
# the amd64 dynamic loader and core libraries inside the container. Kept as
# the LAST heavy layer so refreshes skip the SDK downloads above.
RUN dpkg --add-architecture amd64 \
    && sed -i 's/^Types: deb$/Types: deb\nArchitectures: arm64/' /etc/apt/sources.list.d/ubuntu.sources \
    && echo "deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble main universe" > /etc/apt/sources.list.d/amd64.list \
    && echo "deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble-updates main universe" >> /etc/apt/sources.list.d/amd64.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends libc6:amd64 libstdc++6:amd64 zlib1g:amd64 \
    && rm -rf /var/lib/apt/lists/*

# Native arm64 adb: Google's platform-tools adb is x86_64 and core-dumps
# under qemu-user during connect/TLS. Ubuntu's build is stable; shadow the
# platform-tools copy so every 'adb' call uses it. nmap enables port
# auto-discovery when the watch reboots and its wireless-debugging port
# rotates.
RUN apt-get update \
    && apt-get install -y --no-install-recommends adb nmap \
    && ln -sf /usr/bin/adb /opt/android-sdk/platform-tools/adb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

CMD ["bash"]
