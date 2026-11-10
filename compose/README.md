# VectorCraft — Compose Multiplatform frontend

A Kotlin/Compose client for the engine's control channel (`crates/ui-egui/src/control.rs`).
It is a separate Gradle project: it is **not** part of the Cargo workspace or `cargo xtask ci`, and the
egui app remains the primary UI. The Rust-only rule applies to the Cargo workspace; this directory is
an explicit, owner-approved exemption.

## Run
```
cargo run --release -p vectorcraft -- --control 7979
cd compose && gradle :composeApp:run
```
Connect, open a file, pick a tool, search and run any engine command (params as a JSON object). The
canvas shows `ui.render` output after each command. Every action goes through `engine.execute`, so
the UI stays thin as CLAUDE.md requires.

## Layout
- `commonMain`: protocol client (`Protocol.kt`), UI (`App.kt`), platform-neutral.
- `desktopMain`: TCP transport and PNG decode. Android/iOS/web targets need their own `Transport`
  (a TCP socket is not available in browsers; web would need an engine bridge).
- `commonTest`: protocol tests.

## Status
Unverified: written without being able to build (Maven Central was rate-limited in the authoring
sandbox). Run `gradle :composeApp:desktopTest` first and fix any version or API drift.
