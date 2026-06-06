# Phonar

Phonar is an Android acoustic-ranging experiment powered by the Rust
[BatKit](https://github.com/Ytsejam76/BatKit) DSP library.

The app is built around a single scan button and a radar-style UI. When you
start a scan, it plays a short train of ultrasonic pings, animates a sonar
dial and waveform, records the response, and then estimates the distance to
the strongest echo using BatKit's matched filter and range conversion APIs.

## Requirements

- Android Studio with Android SDK 36 and NDK 28.2.13676358
- JDK 17 or 21 (JDK 25 is not currently supported by this Android toolchain)
- Rust toolchain
- `cargo-ndk`
- Android Rust targets for `arm64-v8a`, `armeabi-v7a`, and `x86_64`

Install the native build prerequisites:

```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

Open this directory in Android Studio and run the `app` configuration, or build
from the command line:

```sh
./gradlew assembleDebug
```

The Gradle `buildRust` task compiles the JNI library before the Android build.
BatKit is pinned to a specific Git revision in `app/src/main/rust/Cargo.toml`.

## App Flow

1. Grant microphone permission if prompted.
2. Tap `Start sonar`.
3. Watch the radar animation and ping indicators while the scan runs.
4. Read the estimated distance once the echo is locked.

## Notes

Acoustic ranging accuracy depends heavily on speaker/microphone latency,
automatic gain control, filtering, reflections, and the device's ability to
reproduce near-ultrasonic frequencies. This project is an experimental starting
point, not a calibrated measuring instrument.
