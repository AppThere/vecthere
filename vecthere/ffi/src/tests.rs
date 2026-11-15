use std::ffi::{CStr, CString};

use super::*;

pub(crate) fn call(s: *mut LokiSession, method: &str, params: Option<&str>) -> Value {
    let m = CString::new(method).unwrap();
    let p = params.map(|p| CString::new(p).unwrap());
    // SAFETY: valid session and NUL-terminated strings.
    let out = unsafe { loki_call(s, m.as_ptr(), p.as_ref().map_or(ptr::null(), |p| p.as_ptr())) };
    assert!(!out.is_null());
    // SAFETY: just returned by `loki_call`.
    let v = serde_json::from_str(unsafe { CStr::from_ptr(out) }.to_str().unwrap()).unwrap();
    unsafe { loki_string_free(out) };
    v
}

#[test]
fn commands_are_listed_with_the_fields_the_kit_reads() {
    let s = loki_session_new();
    let r = call(s, "engine.commands", None);
    assert_eq!(r["ok"], true, "{r}");
    let list = r["result"].as_array().unwrap();
    assert!(list.len() > 100, "only {} commands", list.len());
    for c in list {
        assert!(c["id"].is_string() && c["label"].is_string() && c["params"].is_string(), "{c}");
        assert!(c.get("menu").is_some() || c.get("location").is_some(), "{c}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn hostile_arguments_are_errors_not_crashes() {
    let s = loki_session_new();
    assert!(!s.is_null());
    assert_eq!(call(s, "engine.execute", Some("not json"))["ok"], false);
    assert_eq!(call(s, "engine.execute", Some(r#"{"command":"no.such.command"}"#))["ok"], false);
    assert_eq!(call(s, "engine.execute", Some(r#"{"command":42,"params":[1,2]}"#))["ok"], false);
    assert_eq!(call(s, "no.such.method", None)["ok"], false);
    // SAFETY: null arguments are part of the contract.
    unsafe {
        let out = loki_call(ptr::null_mut(), ptr::null(), ptr::null());
        assert!(!out.is_null());
        loki_string_free(out);
        assert!(loki_render_page(s, u32::MAX, f32::NAN, ptr::null_mut(), ptr::null_mut(), ptr::null_mut()).is_null());
        assert!(loki_render_page(ptr::null_mut(), 0, 1.0, ptr::null_mut(), ptr::null_mut(), ptr::null_mut()).is_null());
        loki_session_free(ptr::null_mut());
        loki_buffer_free(ptr::null_mut(), 0);
        loki_session_free(s);
    }
}

#[test]
fn command_state_has_the_shape_the_kit_reads() {
    let s = loki_session_new();
    let r = call(s, "loki.commandState", None);
    assert_eq!(r["ok"], true, "{r}");
    assert!(r["result"]["disabled"].is_object() && r["result"]["on"].is_array(), "{r}");
    assert!(call(s, "loki.uiRequests", None)["result"].is_array());
    assert_eq!(call(s, "loki.uiRequests", None)["result"], json!([]));
    unsafe { loki_session_free(s) };
}

#[test]
fn the_surface_renders_when_there_is_one() {
    let s = loki_session_new();
    let info = call(s, "loki.surface", None)["result"].clone();
    let pages = info["pages"].as_u64().unwrap_or(0);
    let (mut w, mut h, mut n) = (0, 0, 0);
    // SAFETY: valid session and out-pointers.
    let px = unsafe { loki_render_page(s, 0, 0.5, &mut w, &mut h, &mut n) };
    if pages == 0 || info["kind"] == "cells" {
        assert!(px.is_null(), "{info}");
    } else {
        assert!(!px.is_null() && w > 0 && h > 0 && u64::from(n) == pages, "{info}");
        unsafe { loki_buffer_free(px, w as usize * h as usize * 4) };
        // Past the last page: no picture, no crash.
        assert!(unsafe { loki_render_page(s, n, 1.0, &mut w, &mut h, &mut n) }.is_null());
    }
    unsafe { loki_session_free(s) };
}

/// The engine's command list as the Kotlin tests read it (`app/src/jvmTest/resources`), and the
/// copy the desktop app opens with when the native library is missing (`jvmMain/resources`). The
/// coverage test checks every command is reachable against it, so it must follow the engine.
#[test]
fn kotlin_command_fixture_follows_the_engine() {
    let s = loki_session_new();
    let cmds = call(s, "engine.commands", None)["result"].clone();
    unsafe { loki_session_free(s) };
    let lines: Vec<String> = cmds.as_array().into_iter().flatten().map(|c| serde_json::to_string(c).unwrap()).collect();
    let now = format!("[\n{}\n]\n", lines.join(",\n"));
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/src");
    for dir in ["jvmTest", "jvmMain"] {
        let path = root.join(dir).join("resources/engine-commands.json");
        if std::env::var_os("LOKI_UPDATE_FIXTURES").is_some() {
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(&path, &now).unwrap();
        }
        let saved = std::fs::read_to_string(&path).unwrap_or_default();
        assert!(
            saved == now,
            "engine.commands changed: run `LOKI_UPDATE_FIXTURES=1 cargo test -p loki-ffi kotlin_command_fixture`, then `gradle :app:jvmTest`"
        );
    }
}

mod app_tests;
