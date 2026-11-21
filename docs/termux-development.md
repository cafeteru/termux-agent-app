# Termux Development and On-Device Testing

This guide covers the maintainer's local Termux setup. General build and test commands remain in [README.md](../README.md).

## Local Toolchain

Termux provides:

- `gradle` 9.9.1
- `openjdk-21`
- Native ARM64 `aapt2` at `/data/data/com.termux/files/usr/bin/aapt2`
- Android SDK at `/data/data/com.termux/files/home/android-sdk`

Install the required SDK components with:

```bash
sh $HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  --sdk_root=$HOME/android-sdk \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "build-tools;34.0.0" \
  "platform-tools"
```

Keep host-specific configuration outside version control:

- `local.properties`:
  ```properties
  sdk.dir=/data/data/com.termux/files/home/android-sdk
  ```
- `~/.gradle/gradle.properties`:
  ```properties
  android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
  ```

The Maven override is required for native ARM64 builds in Termux. GitHub Actions must continue to use its standard Maven-provided `aapt2`.

## Tests and APK Build

Always disable the Gradle daemon in Termux:

```bash
gradle testDebugUnitTest assembleDebug --no-daemon
```

Robolectric and Compose end-to-end tests run natively on Termux `aarch64` without `proot` when tests use:

```kotlin
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
```

These modes avoid x86_64-only Conscrypt and Robolectric native libraries. Use a `proot-distro` x86_64 environment only if a future test specifically requires `GraphicsMode.NATIVE` or Conscrypt.

## Install on the Device

After every app feature or fix, copy the debug APK to shared Downloads:

```bash
cp -f app/build/outputs/apk/debug/app-debug.apk \
  /sdcard/Download/termux-agent-0.0.1-debug.apk
```

Install it from Android's Files app at **My files > Downloads**.

Termux's `com.termux.app.api.file.FileViewReceiverActivity` and `FileReceiverActivity` components are disabled in the maintainer's Android environment. APK files therefore open directly with `com.google.android.packageinstaller` instead of Termux's file receiver.
