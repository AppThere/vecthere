Sections

[1 The idea](#idea) [2 Repository layout](#layout) [3 The C ABI](#abi) [4 Kotlin side](#kotlin) [5 Testing](#testing) [6 Build and run](#build) [7 Porting checklist](#port) [8 Known gaps](#gaps)

Written from Loki Text, October 2026

# Playbook: a Kotlin Multiplatform, responsive front end for a Craft engine

Verified on JVM desktop tests and on Android (a Chromebook's Android runtime). **iOS is scaffolded but has never been built.**

Audience: an agent (Claude Code) applying the same approach to another Craft application (PhotoCraft, VectorCraft, FilmCraft, LightCraft, PDFCraft, EffectCraft, DesignCraft). Read this top to bottom, then read the Loki Text source named in each section. It is small (about 1,700 lines of Kotlin and 360 of Rust) and is the reference implementation.

## 1. The idea

Every Craft app already has a Rust engine where **everything is a command** (an id, a label, a ribbon location, a shortcut, a params doc, an `enabled` check, a `run`), and a control protocol that dispatches commands by id. A second front end therefore needs very little:

1. run a command or protocol method by id, with JSON params;
2. read state back as JSON;
3. draw what the engine renders.

So the Kotlin app is **thin**. It never re-implements editing logic. New engine features appear in the UI with no binding changes, because the ribbon is *built from the engine's command list*.

Compose UI (app/, Kotlin) ── Engine.kt (commands as JSON) ── C ABI ── ffi/ (Rust) ── engine

One C ABI serves every target: JNA on JVM and Android, Kotlin/Native cinterop on iOS.

## 2. Repository layout (copy this)

```
<app>/                      # a folder inside the Craft repo, e.g. loki-text/
  Cargo.toml                # its OWN Cargo workspace, members = ["ffi"]
  ffi/                      # the C-ABI crate (needs `unsafe`, so it can't live in the engine workspace)
    include/<name>.h        # hand-written header, used by iOS cinterop; keep in step with lib.rs
    src/lib.rs, state.rs, tests.rs
  app/                      # Gradle KMP module
    build.gradle.kts
    src/commonMain         # ALL UI + Engine.kt (this is where the work is)
    src/commonTest         # pure-Kotlin tests (parsers), run with :app:jvmTest
    src/jnaMain            # NativeEngine for JVM + Android (JNA)
    src/jvmMain            # desktop entry point, file picker, clipboard
    src/androidMain        # MainActivity, manifest, jniLibs/<abi>/lib<name>_ffi.so (built, gitignored)
    src/iosMain            # MainViewController, NativeEngine via cinterop
    src/nativeInterop/cinterop/<name>.def
  gradle/libs.versions.toml
  scripts/build-desktop.sh, build-android.sh, build-ios.sh
```

Why a separate Cargo workspace: the engine workspace sets `unsafe_code = "forbid"` (never-crash rules). The FFI crate is the one place that needs raw pointers; keep it tiny and keep the `#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, ...)]` header. Release profile: `panic = "unwind"` so the boundary can catch panics.

## 3. The C ABI (keep it this small)

Six functions. Do not add more; add JSON *methods* instead.

```
LokiSession *loki_session_new(void);
void         loki_session_free(LokiSession *s);
char        *loki_call(LokiSession *s, const char *method, const char *params_json);
void         loki_string_free(char *p);
uint8_t     *loki_render_page(LokiSession *s, uint32_t page, float scale,
                              uint32_t *width, uint32_t *height, uint32_t *pages);
void         loki_buffer_free(uint8_t *p, size_t len);
```

- `loki_call` returns `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}`. Methods are the engine's control-protocol names (`engine.execute`, `engine.commands`, `document.inspect`) plus a few front-end-only ones prefixed `loki.` (`loki.selection`, `loki.viewport`, `loki.commandState`).
- Rendering returns straight-alpha RGBA8. Cap the bitmap (Loki Text: 8192² pixels).
- **Never crash**: every entry point wraps its body in `catch_unwind`; null and non-UTF-8 arguments are errors, not UB. Test it (`hostile_arguments_are_errors_not_crashes`).
- For an app whose "page" is a canvas, timeline or artboard, keep the same shape: `render(region, scale)` returning RGBA. Add `loki.*` methods for geometry the UI needs (selection boxes, hit tests).

## 4. Kotlin side

### 4.1 Engine.kt (commonMain)

- `expect class NativeEngine : AutoCloseable { callRaw(method, paramsJson): String; renderPage(...) }` with `actual`s in `jnaMain` (JNA `Library` interface; `Native.load("<name>_ffi", …)`) and `iosMain`.
- `class Engine(native)`: `call(method, params)` parses the reply and throws `EngineException` on `ok:false`; `execute(command, params)`, `commands()`, `inspect()`, `commandState()`, `selection()`.
- **Defensive parsing everywhere.** Replies are untrusted: use `as? JsonObject`, `?.jsonPrimitive?.…OrNull`, default values. A malformed reply must degrade (empty state), never throw into the UI.

### 4.2 Threading (important, easy to get wrong)

The native session is **not thread-safe** and calls block. Use one `Mutex` for *every* engine call (edits, geometry, rendering) and run them on `Dispatchers.Default`:

```
val engineLock = remember { Mutex() }
suspend fun perform(block: Engine.() -> Unit) {
    status = try { engineLock.withLock { withContext(Dispatchers.Default) { engine.block() } }; revision++; "" }
             catch (e: EngineException) { e.message ?: "error" }
}
```

A `revision` counter is bumped after each successful command; a `LaunchedEffect(revision, zoom, …)` takes ONE **snapshot** (selection geometry + rendered pages + counts + command state) under the lock and publishes it to UI state in a single assignment. Publishing caret and page together avoids a frame where the caret is for the new document and the page for the old one.

### 4.3 The ribbon is generated, not written

`engine.commands` returns every command with `location` = `Tab › Group[ › …]`. `parseRibbon`:

- keeps only main tabs in a fixed `TAB_ORDER` (contextual tabs wait until selection kind is known);
- drops `ui.*` commands (they act on a window this front end doesn't have) and commands that need a value (`runsWithoutParams` inspects the params doc: a required key reads `"name":`, optional `"name"?:`);
- groups items by tab and group, preserving engine order.

Buttons with a known icon are drawn as icon buttons; a group with none becomes a menu of command names, so **no engine feature is unreachable** even before it has an icon. This is why porting is cheap: a new engine command shows up automatically.

Icons: the [Lucide](https://lucide.dev) Compose package (`com.composables:icons-lucide-cmp`, ISC). It is a *dependency*, not files in the repo, so no `ATTRIBUTION.md` rows are needed for it. **Never copy another vendor's iconography** (Craft rule). A map `commandId → ImageVector` lives in `RibbonIcons.kt`.

### 4.4 Command state (disabled + toggles)

`loki.commandState` (Rust, `ffi/src/state.rs`) returns `{"disabled": {id: reason}, "on": [id,…]}` from the engine's own `enabled` checks and state queries (`format.state`, `view.state`). The UI greys a button with `enabled = disabledReason == null`, shows the reason in its tooltip, and draws a toggle that is on with a tinted container. It is fetched in the same snapshot as the render, so it can't lag the page.

**Pitfall that cost an hour:** the Kotlin fallback for a failed call was `runCatching{…}.getOrDefault(EMPTY)`. When the installed native library was stale, the call failed silently and the ribbon simply looked unstyled. Fallbacks must log. Loki Text uses `warnOnce(what, e, fallback)`: prints once per distinct message, because a snapshot runs on every keystroke.

### 4.5 Responsive layout

One `BoxWithConstraints` at the root computes a size class from the window width (not the device type, so it also handles split-screen, rotation and Chromebook window resizing):

| Class | Width | Ribbon | Chrome |
| --- | --- | --- | --- |
| Compact | \< 600 dp | `SimpleRibbon`: tab row + ONE scrolling row of icons (quick-access buttons lead the row); an "all commands" list holds the rest | page count as `1/3`; no zoom controls |
| Medium | 600–900 dp | same `SimpleRibbon` | full page count, zoom controls, Print/Web buttons |
| Expanded | ≥ 900 dp | full grouped ribbon: tab bar, scrolling row of groups, group names, separators, Styles gallery; quick-access buttons on their own row above | adds the character count to the status bar |

Rules that made it work:

- Every group's content box has the same fixed height (`GROUP_CONTENT_HEIGHT`) so group names align.
- `FlowRow` fills all rows before widening a group; a menu of one command is just that button.
- Wide content scrolls (`ScrollRow`) instead of wrapping or being clipped; nothing is unreachable on a phone.
- A window narrower than a page (`NARROW_WINDOW` = 640 dp, a phone) switches the document to a *reflowing* view (Web Layout) and restores the page view when widened (`autoWeb` flag remembers that it was automatic, not the user's choice). The engine wraps to a viewport pushed with `loki.viewport`, **debounced 150 ms** so dragging a window edge doesn't re-layout every frame.
- Zoom is client-side (render at a larger or smaller scale) with the page count capped (`MAX_PAGES / zoom²`) because bitmap memory grows with the square of zoom.
- Don't recreate the activity on resize or rotation: the open document lives in the engine session, which recreation would drop. Manifest:

  ```
  android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|keyboard|keyboardHidden|navigation|uiMode|layoutDirection|fontScale"
  android:windowSoftInputMode="adjustResize"
  ```

### 4.6 Theme

`Theme.kt` defines a Material 3 `lightColorScheme` (`LokiColors`) and two bar tokens:

- `BarBlue` (`#004F93`) for the ribbon's tab bar and the status bar, with white text;
- `OnBarColors` = `LokiColors.copy(primary = White, onSurface = White, error = soft red)`.

Content on the bars is wrapped in `MaterialTheme(colorScheme = OnBarColors) { CompositionLocalProvider(LocalContentColor provides Color.White) { … } }` so `Text`, `TextButton` and `Icon` pick up white without per-widget colours. The tab bar uses a custom `RibbonTabRow` (white labels, bold selected tab, 3 dp white indicator). **Pick one accent token per app and reference it; don't scatter hex values.** Use each app's own palette, not Loki's.

Group separators: `VerticalDivider(Modifier.height(GROUP_CONTENT_HEIGHT + GROUP_NAME_HEIGHT), color = outline @ 55 %)` between neighbouring groups.

### 4.7 Input

- Keys on the page map to commands in `Keys.kt` (`keyCommand(e)`): shortcuts follow the engine's own (`Mod+B`); Ctrl or Meta both act as Mod. Ignore bare modifier keys.
- Pointer: click and drag convert pixel offsets to page points (`/ pxPerPt`) and call `caret.set` / `select.range` (or the app's equivalent); the engine returns selection geometry which the UI draws as an overlay.
- Clipboard and file pickers are `expect fun`s (`clipboardText`, `setClipboardText`, `pickFile`, `publishFile`). The engine reads and writes **paths**, but Android hands out content URIs, so Open copies the document into app storage and returns that path; Save As returns an app-storage path and `publishFile` copies it to the chosen URI.

## 5. Testing

- Rust: `cd <app> && cargo test` (FFI round trip, hostile arguments, command state).
- Kotlin: `:app:jvmTest` runs `commonTest` (parsers are pure functions: `parseRibbon`, `parseCommandState`, `parseCounts`). Keep parsing out of composables so it is testable without a UI. Add a regression test with every fix (the status-bar character count bug got `characterCountIncludesSpaces`).
- **Look at it.** Screenshot the running app and read the PNG; compare crops side by side (`convert in.png -crop WxH+X+Y +repage -scale 200% out.png`). Two real bugs (stale library, wrong count field) were found only this way.

## 6. Build and run

```
# Desktop
scripts/build-desktop.sh        # cargo build --release -p <name>-ffi
gradle :app:run                 # build.gradle.kts passes -Dloki.lib=<rootDir>/target/release to the JVM

# Android (needs SDK + NDK; ANDROID_HOME defaults to ~/Android/Sdk)
scripts/build-android.sh        # builds the .so into app/src/androidMain/jniLibs/<abi>/, then :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n <applicationId>/.MainActivity
```

On an aarch64 Linux host (an ARM Chromebook) the NDK's own compiler is x86_64-only, so `build-android.sh` uses the host's `clang` + `lld` against the NDK sysroot, via a per-target linker wrapper. On x86_64 it uses `cargo-ndk`.

### Pitfalls (each one bit us)

1. **Stale native library.** Gradle does not rebuild Rust. If you only run `gradle :app:assembleDebug`, the APK ships whatever `.so` is in `jniLibs`. After any change under `ffi/` or the engine, run `build-android.sh` (or `cargo build` for the right target and copy it). Check `ls -l --time-style=full-iso` on the `.so` against your source mtimes. `strings` on the `.so` is NOT a reliable check: short string compares compile to immediates.
2. **Gradle version vs AGP.** AGP 8.11.x fails on Gradle ≥ 9.6 ("relies on InternalProblems … removed in 9.6.0"). Use Gradle 9.1.0 (the version in `gradle-wrapper.properties` of the sibling Android projects). Generate a wrapper with `gradle wrapper --gradle-version 9.1.0` so nobody depends on a machine-local install.
3. **`SDK location not found`** when calling `gradle` directly: export `ANDROID_HOME` (the build script does, a bare `gradle` call doesn't) or write `local.properties` (gitignored).
4. **Don't pass `--offline`** unless the dependency cache is known complete.
5. **A script's `timeout`** kills the first cold Rust build (about 8 minutes on a small ARM machine). Run long builds in the background with no timeout and log to a file.
6. **ChromeOS Android runtime.** `adb devices` is empty until `adb connect 100.115.92.2:5555`. `monkey` and `resolve-activity` can't find the launcher activity even when the manifest is right; use `adb shell am start -n <pkg>/.MainActivity`. Screenshots come back **black** when another activity (the ARC launcher placeholder) holds the foreground: start the app again before `adb exec-out screencap -p`. `adb shell input tap/text/keycombination` works for driving it (`%s` is a space in `input text`).
7. **Low-memory machines** (6 GB, no swap): put overrides in `~/.gradle/gradle.properties` (`org.gradle.jvmargs=-Xmx2g`, `kotlin.compiler.execution.strategy=in-process`, `org.gradle.workers.max=2`). On aarch64, Google ships `aapt2` for x86_64 only: `android.aapt2FromMavenOverride` pointing at an emulated binary.
8. **Compose Multiplatform version drift.** Versions in `libs.versions.toml` are the ones that built (Kotlin 2.2.20, Compose Multiplatform 1.9.0, AGP 8.11.1, JNA 5.17.0, coroutines 1.10.2, serialization 1.9.0, Lucide 2.2.1). Material 3 tab-indicator APIs move between releases; if `tabIndicatorOffset` or `SecondaryIndicator` changes, adapt `RibbonTabRow` only.

## 7. Porting checklist for another Craft app

1. Read that app's `CLAUDE.md` / `AGENTS.md` and `plan/STATUS.md`; its rules (never-crash, no third-party assets, `cargo xtask ci`) apply to the new folder too. Confirm it has an engine with a command registry and a control protocol; if not, build that first.
2. Copy the layout in §2. Rename the crate (`<app>-ffi`), library (`lib<app>_ffi`), Android `applicationId`, and the JNA `Native.load("<app>_ffi")` string.
3. Write `ffi/` against the app's engine / `Headless`-style backend. Implement the six functions; port `state.rs` using that app's own enabled/toggle queries. Add the three tests.
4. Replace "page rendering" with the app's primary surface (canvas, artboard, timeline, PDF page). Keep the snapshot-under-one-lock pattern. Decide what zoom and pan mean for it.
5. Reuse `parseRibbon`, `CommandState`, `SizeClass`, `RibbonTabRow`, `SimpleRibbon` nearly verbatim; change `TAB_ORDER` to the app's tabs and `RibbonIcons.kt` to map its command ids.
6. Choose the app's own accent colour; set `BarBlue`-equivalent tokens in `Theme.kt`.
7. Replace Word-specific pieces: word/character counts (status bar), Styles gallery, reflow "Web Layout", key map in `Keys.kt`.
8. `cargo test`, `:app:jvmTest`, build for Android, install, **screenshot and read it**.
9. Commit with the app's task-id convention. Update that app's roadmap/gaps docs and `ATTRIBUTION.md` if any asset *file* was added (the Lucide dependency needs none).

## 8. Known gaps in Loki Text

Don't assume these are solved.

- iOS target is scaffolded, never built (needs macOS; `UIDocumentPickerViewController` and `UIPasteboard` are TODOs in `iosMain`). No Xcode host project exists.
- No IME/composition handling; hardware-keyboard and `input text` only.
- Contextual ribbon tabs (Table Layout, Picture Format…) are omitted until selection kind is known.
- Only the main tabs' parameterless commands are buttons; commands that need a value (font size, colour) need real controls (dropdowns, pickers) that don't exist yet.
- No dark theme.
- `loki.commandState` is computed for every command on every snapshot; fine at Word's scale, measure before copying it to an app with thousands of commands.