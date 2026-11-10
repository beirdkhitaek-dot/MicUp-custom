<div align="center">

# 🎙️ MicUp

**Real-time microphone audio processing for Android**

[![Release](https://img.shields.io/github/v/release/papergray/MicUp?style=flat-square&color=blue)](https://github.com/papergray/MicUp/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square&logo=android)](https://github.com/papergray/MicUp/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

[Download APK](https://github.com/papergray/MicUp/releases/latest) · [Report Bug](https://github.com/papergray/MicUp/issues)

</div>

---

## What is MicUp?

MicUp is a real-time microphone processing app for Android. It captures your mic input, runs it through a DSP effects chain, and routes the processed audio to a virtual microphone — so every app on your phone (Discord, Teams, Zoom, WhatsApp, etc.) hears the cleaned-up, processed version of your voice.

No PC required. No monthly subscription.

---

## This fork: build on GitHub and custom plugins

**Get an APK without a PC.** Push to `main` or `ci-and-custom-plugins`, or open the **Actions** tab, choose **Build APK and plugin** and press **Run workflow**. When the run finishes, download `MicUp-debug-apk` from the run's **Artifacts** list, unzip it and install the `.apk`. No secrets are needed. Builds use the fixed debug key in `app/debug.keystore`, so a new build installs over the previous one. (On a fork, GitHub asks you to enable Actions once on the Actions tab.)

**Custom plugins.** MicUp runs native CLAP, LV2 and VST3 `.so` plugins inside its real-time audio engine. A ready-made example lives in [`plugins/null-commander`](plugins/null-commander): a deep, muffled, distorted voice chain (pitch, bass, distortion, bit crush, muffle, stutter) as a single C++ file. The same workflow builds it for `arm64-v8a`, `armeabi-v7a` and `x86_64` as `NullCommander-<abi>` artifacts. To use it:

1. Download `NullCommander-arm64-v8a` (most phones) and unzip `NullCommander.clap`.
2. Open it from your file manager and choose **MicUp**, or add its folder under **Settings, Manage Plugin Paths**.
3. In the plugin browser add **Null Commander FX**, and turn the built-in pitch shifter off because the plugin has its own.

To write your own plugin, copy `plugins/null-commander`, change the descriptor id and name, `kParams` and `tick()`. Plugins must be mono-capable and fast: the host bypasses any plugin that takes more than 2 ms per audio block.

Plugin APKs (the AIDL interface in `app/src/main/aidl`) are listed in the plugin browser but are not run by the audio engine, because calling another app from the real-time audio thread would add delay and dropouts. Use a native plugin instead.

`tests/clap-host/run.sh` builds the plugin and the real `ClapHost.cpp` on a PC and runs audio through them. CI runs it on every push.

The **Null Commander (basic)** preset gets a similar deep, muffled sound from the built-in effects alone. It is an estimate, so adjust it by ear.

---

## Features

-  **Built-in DSP chain** — Noise Gate, 10-band EQ, Compressor, Reverb, Pitch Shifter
-  **Plugin support** — Load LV2, CLAP, and VST3 native plugins (`.so`, `.clap`, `.lv2`)
-  **Open plugin files** — Tap a plugin file in your file manager to import it directly
-  **Monitor toggle** — Hear your processed audio through headphones, or silence it while still routing to other apps
-  **Shizuku support** — ADB-level ALSA loopback routing without full root
-  **Three virtual mic tiers** — VoIP stream (no root), Shizuku (ADB), or Magisk (full root)
-  **Live VU meters** — Input, output, and gain reduction metering
-  **Preset system** — Save and load your effect configurations
-  **Built-in crash reporter** — Crash logs auto-shared for easy bug reporting

---

## Download

Grab the latest signed APK from the [Releases page](https://github.com/papergray/MicUp/releases/latest).

No Play Store. No sign-in. Just install and go.

> **Enable "Install from unknown sources"** in Android Settings → Apps → Special app access before installing.

---

## How to Use

1. Install the APK
2. Grant microphone permission when prompted
3. Tap the **power button** to start audio processing
4. Adjust effects with the sliders and knobs
5. In your call/meeting app, select **MicUp** or the VoIP audio stream as your microphone

### Loading Plugins

- Open your file manager, navigate to your plugin `.so` / `.clap` / `.lv2` file
- Tap it → select **MicUp** → plugin is imported and added automatically
- Or go to **Settings → Manage Plugin Paths** to add a folder to scan

### Shizuku (optional, no root needed)

Shizuku gives MicUp ADB-level access for better audio routing:

1. Install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) from Play Store
2. Start Shizuku via wireless debugging (Android 11+)
3. Open MicUp → Settings → Shizuku → tap **Grant**

---

## Building from Source

**Requirements:** Android Studio, NDK 26, CMake 3.22, JDK 17

```bash
git clone https://github.com/papergray/MicUp.git
cd MicUp
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Supported Formats

| Format | Extension | Notes |
|--------|-----------|-------|
| LV2 | `.lv2` `.so` | Requires `liblilv` |
| CLAP | `.clap` `.so` | CLAP 1.2.1 |
| VST3 | `.so` | Experimental |
| APK Plugin | installed app | Via AIDL interface |

---

## Requirements

- Android 8.0+ (API 26)
- ARM64, ARMv7, or x86_64
- Microphone permission
- For Shizuku tier: Android 11+
- For Root tier: Magisk

---

## License

MIT License — see [LICENSE](LICENSE)

---

<div align="center">
Made for Android · Built with Oboe, C++17, Jetpack Compose
</div>

### Versions
Every GitHub build numbers itself (Settings > About > Version, e.g. `1.1.14-ab12cd3`), so you can tell which build is installed. Each new build installs over the old one and keeps your plugins and settings.
