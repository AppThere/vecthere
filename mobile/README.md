# VectorCraft mobile (Android, ChromeOS, iPadOS)

A Kotlin Multiplatform app with a Compose Multiplatform UI over VectorCraft's Rust engine
(`crates/ffi`). Pre-alpha: see [`docs/mobile.md`](../docs/mobile.md) for the architecture, how pens
and touch are handled (S Pen, USI, Apple Pencil), the layouts, what is verified and what is next.

```sh
gradle wrapper --gradle-version 8.14.3     # once
./gradlew :shared:run                      # desktop harness of the mobile UI
./gradlew :androidApp:installDebug         # Android device or emulator (needs the NDK)
cd iosApp && xcodegen && open iosApp.xcodeproj   # iPad / iPhone (needs a Mac with Xcode)
```

| Path | What |
|---|---|
| `shared/` | the shared UI and the generated engine bindings (`com.appthere.vectorcraft.engine`) |
| `shared/rust/` | the native library Gobley builds: `crates/ffi` as a cdylib + staticlib |
| `androidApp/` | the Android app |
| `iosApp/` | the iOS app (SwiftUI host, XcodeGen spec) |
