//! C ABI over the VectorCraft engine, for VecThere's Kotlin Multiplatform front end.
//!
//! The same six functions as Loki Text's (`loki-text/loki-text/ffi` in the WordCraft fork, and the
//! playbook's §4 there). Everything the UI does is a command or a control-protocol method, so one
//! JSON call covers it:
//!
//! * [`loki_session_new`] / [`loki_session_free`]: an in-process editing session.
//! * [`loki_call`]: `method` + JSON `params` in, `{"ok":true,"result":…}` or
//!   `{"ok":false,"error":"…"}` out (free with [`loki_string_free`]).
//! * [`loki_render_page`]: one page of the surface (an artboard) as straight-alpha RGBA8 (free with
//!   [`loki_buffer_free`]).
//!
//! Front-end-only methods, prefixed `loki.`:
//!
//! * `loki.commandState`: `{"disabled": {id: reason}, "on": []}`, from the engine's own enabled checks.
//! * `loki.uiRequests`: what commands asked the front end to do since the last call; reading clears.
//! * `loki.surface`: `{"kind", "pages", "current"}`: what [`loki_render_page`] draws.
//!
//! What is the app's own is in `app.rs`; this file is the same for every Craft engine.
//!
//! Never crash: every entry point catches panics and reports them as errors; null and non-UTF-8
//! arguments are errors, not UB. The one `unsafe` area is this file's pointer handling.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use serde_json::{Map, Value, json};

mod app;

/// Opaque to C. One per open window.
pub struct LokiSession {
    inner: app::Inner,
}

/// Largest bitmap `loki_render_page` returns (width × height, pixels): 8192² ≈ 256 MiB of RGBA.
const MAX_PIXELS: u64 = 8192 * 8192;

fn reply(v: Value) -> *mut c_char {
    // JSON text never contains NUL, but fall back to a fixed error rather than panic.
    CString::new(v.to_string()).or_else(|_| CString::new(r#"{"ok":false,"error":"reply contained NUL"}"#)).map_or(ptr::null_mut(), CString::into_raw)
}

fn err(msg: impl Into<String>) -> *mut c_char {
    reply(json!({"ok": false, "error": msg.into()}))
}

/// Borrow a C string as UTF-8. `None` for null or invalid UTF-8.
///
/// # Safety
/// `p` is null or points to a NUL-terminated string valid for the call.
unsafe fn str_arg<'a>(p: *const c_char) -> Option<&'a str> {
    if p.is_null() {
        return None;
    }
    // SAFETY: non-null and NUL-terminated per the caller's contract.
    unsafe { CStr::from_ptr(p) }.to_str().ok()
}

/// `{"disabled": {id: reason}, "on": []}` from `engine.commands`, whose entries carry `enabled`
/// and `disabled_reason` in every Craft engine. Which toggles are on needs the app's own tables
/// (the playbook, §5.5); until they exist `on` is empty.
fn command_state(inner: &mut app::Inner) -> Result<Value, String> {
    let list = app::call(inner, "engine.commands", json!({}))?;
    let mut disabled = Map::new();
    for c in list.as_array().into_iter().flatten() {
        let Some(id) = c.get("id").and_then(Value::as_str) else { continue };
        if c.get("enabled").and_then(Value::as_bool) == Some(false) {
            let why = c.get("disabled_reason").or_else(|| c.get("disabledReason")).and_then(Value::as_str).unwrap_or("not available now");
            disabled.insert(id.to_string(), json!(why));
        }
    }
    Ok(json!({"disabled": disabled, "on": []}))
}

/// Create a session. Free with [`loki_session_free`]. Null on failure.
#[unsafe(no_mangle)]
pub extern "C" fn loki_session_new() -> *mut LokiSession {
    catch_unwind(|| Box::into_raw(Box::new(LokiSession { inner: app::new() }))).unwrap_or(ptr::null_mut())
}

/// Free a session. Null is ignored. The pointer must not be used afterwards.
///
/// # Safety
/// `s` is null or came from [`loki_session_new`] and has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_session_free(s: *mut LokiSession) {
    if s.is_null() {
        return;
    }
    // SAFETY: from `Box::into_raw` in `loki_session_new`, freed once (caller's contract).
    let b = unsafe { Box::from_raw(s) };
    drop(catch_unwind(AssertUnwindSafe(move || drop(b))));
}

/// Call a control-protocol method (`engine.execute`, `engine.commands`, …) or a `loki.*` method.
/// `params_json` may be null for `{}`. Returns a JSON string to free with [`loki_string_free`];
/// null only if allocation failed.
///
/// # Safety
/// `s` came from [`loki_session_new`] and is not used concurrently; `method` and `params_json`
/// are null or NUL-terminated.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_call(s: *mut LokiSession, method: *const c_char, params_json: *const c_char) -> *mut c_char {
    // SAFETY: caller's contract.
    let (Some(session), Some(method)) = (unsafe { s.as_mut() }, unsafe { str_arg(method) }) else {
        return err("null session or method");
    };
    // SAFETY: caller's contract.
    let params = match unsafe { str_arg(params_json) } {
        None if params_json.is_null() => json!({}),
        None => return err("params are not UTF-8"),
        Some(t) => match serde_json::from_str::<Value>(t) {
            Ok(v) => v,
            Err(e) => return err(format!("bad params JSON: {e}")),
        },
    };
    let inner = &mut session.inner;
    let out = catch_unwind(AssertUnwindSafe(|| match method {
        "loki.commandState" => command_state(inner),
        "loki.uiRequests" => Ok(app::ui_requests(inner)),
        "loki.surface" => Ok(app::surface(inner)),
        _ => app::call(inner, method, params),
    }));
    match out {
        Ok(Ok(result)) => reply(json!({"ok": true, "result": result})),
        Ok(Err(e)) => err(e),
        Err(_) => err("internal error (panic caught)"),
    }
}

/// Free a string returned by [`loki_call`]. Null is ignored.
///
/// # Safety
/// `p` is null or came from [`loki_call`] and has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_string_free(p: *mut c_char) {
    if !p.is_null() {
        // SAFETY: from `CString::into_raw` in `reply`, freed once.
        drop(unsafe { CString::from_raw(p) });
    }
}

/// Render page `page` (0-based; an artboard) at `scale` pixels per document unit as straight-alpha
/// RGBA8. On success returns the buffer and sets `*width`, `*height`, `*pages` (how many there
/// are); the buffer is `width * height * 4` bytes, freed with [`loki_buffer_free`]. Null on any
/// failure, or when the app has nothing to render as pixels.
///
/// # Safety
/// `s` is as for [`loki_call`]; `width`, `height`, `pages` are null or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_render_page(s: *mut LokiSession, page: u32, scale: f32, width: *mut u32, height: *mut u32, pages: *mut u32) -> *mut u8 {
    // SAFETY: caller's contract.
    let Some(session) = (unsafe { s.as_mut() }) else { return ptr::null_mut() };
    let scale = if scale.is_finite() { scale.clamp(0.01, 16.0) } else { 1.0 };
    let out = catch_unwind(AssertUnwindSafe(|| app::render(&mut session.inner, page, scale, MAX_PIXELS)));
    let Ok(Some(img)) = out else { return ptr::null_mut() };
    let expected = u64::from(img.width) * u64::from(img.height) * 4;
    if expected > MAX_PIXELS * 4 || img.rgba.len() as u64 != expected {
        return ptr::null_mut();
    }
    // SAFETY: each out-pointer is null or writable (caller's contract).
    unsafe {
        if let Some(r) = width.as_mut() {
            *r = img.width;
        }
        if let Some(r) = height.as_mut() {
            *r = img.height;
        }
        if let Some(r) = pages.as_mut() {
            *r = img.pages;
        }
    }
    Box::into_raw(img.rgba.into_boxed_slice()).cast::<u8>()
}

/// Free a buffer from [`loki_render_page`]; `len` is `width * height * 4`. Null is ignored.
///
/// # Safety
/// `p`/`len` are exactly what [`loki_render_page`] returned, and not yet freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_buffer_free(p: *mut u8, len: usize) {
    if !p.is_null() {
        // SAFETY: a boxed slice of exactly `len` bytes made in `loki_render_page`.
        drop(unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(p, len)) });
    }
}

/// What [`app::render`] hands back: straight-alpha RGBA8 and how many pages there are.
pub(crate) struct Rendered {
    pub width: u32,
    pub height: u32,
    pub pages: u32,
    pub rgba: Vec<u8>,
}

#[cfg(test)]
mod tests;
