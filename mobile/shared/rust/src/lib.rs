//! The engine bridge as one native library for the mobile apps: everything is in `crates/ffi`
//! (UniFFI scaffolding included); this crate only links it as a `cdylib` and a `staticlib`, under
//! the same crate name so Gobley's bindgen finds its metadata (namespace `vectorcraft_ffi`).

pub use engine::*;
