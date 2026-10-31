# vecthere: Kotlin Multiplatform front end

**Status: prepared, nothing built.** This folder holds the app-agnostic toolchain scaffold and the
porting brief. No Kotlin or FFI source exists yet. Nothing here is part of the root Cargo workspace
or of `cargo xtask ci`.

The method is in [`PLAYBOOK.md`](PLAYBOOK.md) (written from Loki Text; reference implementation:
`AppThere/loki-text`, branch `ccr-ddee26d0-bs8joe`, folder `loki-text/`). Read it top to bottom first.
The repository's `AGENTS.md` rules (never-crash, assets, clean-room) bind everything here.

## Baseline

| | |
| --- | --- |
| Upstream | `storytold/vectorcraft` |
| Rebased to | release `v0.18.0` (`68b68a30` on this branch; the previous `main` was 4043 commits behind it) |
| How | fast-forward: this fork carried no commits of its own, so nothing was rewritten |
| Product | Vector illustration (Illustrator-class) |
| Primary surface | an artboard canvas with a tool palette, pan and zoom |
| Headless driver | `crates/mcp/src/headless.rs` (`Headless`) over `crates/engine` `Session`; `crates/render` |
| Rust | edition 2024, `rust-version = "1.95"` |

## Names (placeholders, change before the first commit that ships them)

| | |
| --- | --- |
| FFI crate / library | `vecthere-ffi` / `libvecthere_ffi` |
| Header | `ffi/include/vecthere.h` |
| C symbols | `vecthere_session_new`, `_session_free`, `_call`, `_string_free`, `_render_*`, `_buffer_free` |
| Android `applicationId` | `app.appthere.vecthere` |
| Accent token | choose this app's own colour (do not reuse Loki Text's `BarBlue`) |

## What is already here

* `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` and
  `.gitignore`, copied from Loki Text. Versions are the ones that built there (Kotlin 2.2.20,
  Compose Multiplatform 1.9.0, AGP 8.11.1); see playbook pitfall 8 before bumping.
* `Cargo.toml`: the separate FFI workspace, with the root `[patch]` block mirrored.
* `PLAYBOOK.md`.

Not yet created: `gradle/wrapper` (run `gradle wrapper --gradle-version 9.1.0`, pitfall 2), `app/`,
`ffi/`, `scripts/`.

## Readiness and the first blocker

Wave 1. Canvas apps need pointer tools (pen, direct select) over FFI and region rendering; start read-only plus the select tool.

The ribbon builder in the playbook reads each command's `location`. In this repo the engine's
`CommandSpec` carries `menu: &[&str]` (a menu path), id, label, shortcut, params doc and `enabled`.
That is the same information, so `parseRibbon` ports by renaming the field, but this app's menu tree
decides the tab order and which groups get icons.

## Steps (playbook §7, with this repo's specifics)

1. Confirm the headless driver above runs with no egui, wgpu window or audio device. If it does not,
   extract one first; nothing else can start.
2. Add `ffi/` (six C functions only, hostile-argument test, `catch_unwind` everywhere) and add
   `"ffi"` to `members` above. Add the `loki.*`-style front-end-only methods this app needs
   (selection or hit-test geometry, viewport).
3. Add `app/` (commonMain engine wrapper, `parseRibbon`, `CommandState`, `SizeClass`, theme) and
   `scripts/build-{desktop,android,ios}.sh`.
4. Replace page rendering with this app's surface, keeping the single-lock snapshot pattern.
5. `cargo test` in this folder, `:app:jvmTest`, a desktop run, then an Android build; screenshot it and
   read the PNG.
6. Commit with the repo's task-id convention; update `ROADMAP.md`, `docs/gaps.md` and
   `docs/target-app-parity.md` as `AGENTS.md` requires. The Lucide icon package is a dependency, not an
   asset file, so it needs no attribution row.

## Open decisions (yours)

* Final application ids and the per-app accent colours.
* Whether the shared Kotlin (ribbon parser, size classes, theme scaffolding, command-state parsing) lives
  in one library repo consumed by all ten apps, or is copied per app as the playbook describes.
