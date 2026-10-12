# Mobile: Android, ChromeOS and iPadOS

> **Last reviewed:** 2026-10-12 · **Last updated:** 2026-10-12 · **Change:** major (first version: the engine bridge `crates/ffi` and the Kotlin Multiplatform + Compose shell under `mobile/`) · **Target:** Adobe Illustrator 2026 (30.x), Illustrator on the iPad

VectorCraft's engine runs inside a Kotlin Multiplatform app with a Compose Multiplatform UI on
Android phones and tablets (Samsung Galaxy Tab with S Pen), Chromebooks (USI pens), and iPads (Apple
Pencil). The desktop and web apps are unchanged; this is a second front end over the same engine.

**Status: pre-alpha, estimated.** The Rust bridge is built, tested (44 tests) and passes the workspace
gates. The Compose shell's shared code and desktop harness compile against the generated bindings. The
Android and iOS source sets, the Android app and the iOS app have **not** been compiled or run in CI:
this repo's sandbox can't download the Android SDK, and iOS needs a Mac. Treat them as a starting
point to build in Android Studio or Xcode, not a tested app.

## Why a second front end

egui on phones and tablets lacks what drawing apps need from the platform: full pen data (tilt,
orientation, the eraser end, barrel buttons, hover), low-latency dispatch, system palm rejection,
IMEs and accessibility done the platform's way, and layouts for small screens. Compose has all of
it on Android and most of it on iOS. Its UI stays **thin**, as the egui one is: it forwards raw
input, draws the frame and overlays the engine returns, and acts through commands.

## Shape

```
mobile/
  shared/                  Kotlin Multiplatform library: the whole UI (Compose) + engine bindings
    rust/                  vectorcraft-mobile: crates/ffi linked as a cdylib (Android, JVM) and a
                           staticlib (iOS); a separate Cargo workspace (see below)
    src/commonMain/        EditorState, ArtCanvas, adaptive layouts, tools, menus, panels, keyboard
    src/androidMain/       PenInputView (MotionEvent → engine), bitmaps
    src/iosMain/           PencilInputView (UITouch → engine), document picker, MainViewController
    src/desktopMain/       a JVM window running the same UI, for development without a device
  androidApp/              the Android application (activity, manifest, launcher icon)
  iosApp/                  the iOS application (SwiftUI host, Info.plist, XcodeGen spec)
crates/ffi/                vectorcraft-ffi (layer L6): the engine as a UniFFI object
```

- **`crates/ffi`** (`vectorcraft-ffi`, L6 beside `ui-egui` and `mcp`) wraps a whole `Session` in one
  `VectorEngine` object: `execute(command, paramsJson)` reaches every command in the registry, plus
  documents (`newDocument`, `openBytes`, `saveBytes`), the view (`resize`, `pan`, `zoomBy`,
  `rotateBy`, `fitArtboard`), input (`pointer`, `key`, `text`, `shortcut`), drawing (`render`,
  `overlays`, `layers`, `thumbnailPng`) and state (`documentState`, `commands`, `tools`). Every
  entry point catches panics (`vectorcraft_engine::guard`) and returns `VectorException` to Kotlin.
- **Bindings:** [UniFFI](https://mozilla.github.io/uniffi-rs/) 0.29.4 proc-macros in Rust;
  [Gobley](https://github.com/gobley/gobley) 0.3.7 builds the Rust library for each Kotlin target
  (Android ABIs, iOS device and simulator, the host JVM) and generates the Kotlin bindings
  (`com.appthere.vectorcraft.engine`). JNA on Android and the JVM, cinterop on iOS. The workspace pins
  `uniffi = "=0.29.4"` because the scaffolding and Gobley's bindgen must agree.
- **Why a separate `mobile/shared/rust` workspace:** a `cdylib` + `staticlib` crate type on a
  workspace member would make every `cargo xtask ci` run link an ~800 MB shared library and a 2 GB
  static one (debug). `vectorcraft-ffi` stays a plain library tested by CI; the wrapper links it and
  keeps its own lock file (pruned from the main one) and target directory.
- **Fonts:** the engine scans `/system/fonts` and `/product/fonts` on Android and
  `/System/Library/Fonts` on iOS (`crates/text/src/fontdb.rs`); apps add their own folders with
  `setFontDirs`.

## Input: pens, fingers, mice, keyboards

The routing is decided once, in Rust (`crates/ffi/src/input.rs`, unit-tested), so every platform
behaves the same. Platform code only forwards raw samples, including historical (Android) or
coalesced (iOS) ones.

| Input | Android / ChromeOS (`PenInputView`) | iPadOS (`PencilInputView`) | What it does |
|---|---|---|---|
| Pen tip | `TOOL_TYPE_STYLUS`: pressure, `AXIS_TILT`, orientation; unbuffered dispatch; historical samples | `UITouch.type == .pencil`: force, altitude, azimuth; coalesced touches | draws with the active tool; pressure reaches the tools (Paintbrush, Blob Brush, Width…) |
| Pen eraser end | `TOOL_TYPE_ERASER` (USI, Wacom-style) | — (Apple Pencil has none) | switches to the Eraser tool for the stroke (Pen & Touch › eraser end) |
| Barrel button | `BUTTON_STYLUS_PRIMARY` (S Pen side button, USI barrel); older One UI reports `BUTTON_SECONDARY`, mapped the same | — | switches to a chosen tool while held (default Eraser) |
| Double-tap / squeeze | — | `UIPencilInteraction`, following Settings › Apple Pencil (switch to eraser / previous tool) | as the system setting says |
| Hover | `ACTION_HOVER_*` (S Pen Air View, USI) | `UIHoverGestureRecognizer` (M2 iPad Pro and later, trackpads) | the tool's hover feedback |
| Palm | Android 13+ `FLAG_CANCELED` cancels the contact | UIKit's own palm rejection | never draws; once a pen is seen, fingers never draw (Auto) |
| One finger | — | — | draws until a pen is seen, then pans (Pen & Touch › Fingers: Auto, Draw, Pan & zoom); its press waits 90 ms or 6 pt of movement so a second finger makes a gesture instead of a mark |
| Two fingers | — | — | pinch zoom and pan; rotate the view when enabled (after a 12° turn); tap: undo |
| Three fingers | — | — | tap: redo (as the desktop app, #585) |
| Mouse, touchpad | wheel pans, Ctrl+wheel zooms; Android 14 touchpad pinch and two-finger swipe (ChromeOS, DeX) | pointer through UIKit | as on the desktop |
| Keyboard | Ctrl shortcuts (ChromeOS, Book Cover Keyboard) | ⌘ shortcuts (Magic Keyboard) | desktop shortcut chords run commands or choose tools; arrows, Escape, Enter go to the tool; modifiers reach the tools |

Finger hit tolerances are twice a pen's (`InputPrefs.fingerTolerance`), by telling the tools the
art is smaller on screen than it is.

## Layouts

`VectorCraftApp` picks a layout from Material 3's width classes, so the same app fits a phone, a
tablet in either orientation, a foldable, split screen, DeX and a Chromebook window, and switches
live as the window resizes:

- **Compact** (< 600 dp, phones): title bar with undo/redo, fit, panels and an overflow menu that
  holds every menu of the desktop menu bar; the canvas; a scrolling tool strip along the bottom in
  reach of the thumb (long-press a tool for its group). Panels open as a bottom sheet.
- **Medium** (600–840 dp, tablets in portrait, foldables): a tool rail on the left; panels as a sheet;
  a status bar.
- **Expanded** (≥ 840 dp, tablets in landscape, Chromebooks): the desktop menu bar built from the
  command registry, the tool rail, docked panels (Properties, Layers, Pen & Touch) and a status bar.

Menus list every command with a menu path; commands that need parameters are greyed out until their
dialogs exist on mobile. Tool icons are Material Symbols (Apache-2.0, from the Compose icon
dependency) until VectorCraft's own icon set is exported as Compose vectors.

## Building

Prerequisites: Rust (the workspace's toolchain) with the mobile targets
(`rustup target add aarch64-linux-android x86_64-linux-android aarch64-apple-ios aarch64-apple-ios-sim`;
Gobley adds missing ones itself), JDK 17+, Android Studio with the NDK, and for iOS a Mac with Xcode
and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
cd mobile
gradle wrapper --gradle-version 8.14.3     # once, to create ./gradlew
./gradlew :shared:run                      # the desktop harness (resize the window: phone → laptop)
./gradlew :androidApp:installDebug         # a connected device or emulator
cd iosApp && xcodegen && open iosApp.xcodeproj   # then Run in Xcode
```

The first build compiles the engine for each target (minutes per target). Release builds use thin
LTO and strip debug info.

## Known limits and next steps

- **Frame transfer:** each render copies the whole frame across the FFI (a 2560 × 1600 tablet frame
  is 16 MB). Next: render only the damaged region, or into a shared buffer (`ByteBuffer` on Android,
  a pinned array on iOS); then tiles and a GPU path (gap G15).
- **Latency:** input waits for a render in progress (one lock). Next: Android front-buffered
  rendering (`androidx.graphics` `GLFrontBufferedRenderer`) and motion prediction
  (`androidx.input` `MotionEventPredictor`) for wet ink, and the same with Metal on iPadOS.
- **Tilt and azimuth** are captured end to end but the tools take pressure only (the desktop app is
  the same, #372): next, pass them to the brushes.
- **Dialogs and panels:** tool dialogs, the Color, Swatches, Stroke, Appearance, Character and
  Paragraph panels aren't on mobile yet; the menus run only commands that need no parameters.
- **iOS:** the Swift host and XcodeGen spec are untested; Apple Pencil Pro's barrel roll and
  squeeze palette, and Pencil hover's height, are not read yet.
- **Samsung:** S Pen Air actions (the Bluetooth remote gestures) need Samsung's S Pen Remote SDK and
  aren't supported.
- **Localization:** the mobile UI's strings are English only; the desktop app's i18n tables aren't
  wired in yet.
- **CI:** nothing builds `mobile/` in CI yet; a workflow with the Android SDK and a macOS runner is
  needed.

## Rules for this code

The project's rules apply: clean-room (nothing from Adobe's iPad or desktop apps; behaviour from
public documentation only), no panics in the Rust bridge, everything a command. The mobile shell is
Kotlin with a few lines of Swift at the project owner's request, the one exception to "Rust only";
keep logic in Rust (`crates/ffi`, the engine) and the Kotlin thin.

## Revision history

| Date | Change | Summary |
|---|---|---|
| 2026-10-12 | major | First version: `crates/ffi`, the input router, the KMP + Compose shell, Android pen layer (S Pen, USI), iOS Pencil layer, adaptive layouts |
