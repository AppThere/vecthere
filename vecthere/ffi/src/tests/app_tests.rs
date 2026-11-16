//! VectorCraft's own: a drawn shape shows on the rendered artboard.

use super::*;

fn ink(s: *mut LokiSession) -> usize {
    let (mut w, mut h, mut n) = (0, 0, 0);
    // SAFETY: valid session and out-pointers.
    let px = unsafe { loki_render_page(s, 0, 0.25, &mut w, &mut h, &mut n) };
    assert!(!px.is_null());
    let len = w as usize * h as usize * 4;
    // SAFETY: `len` bytes, just returned.
    let dark = unsafe { std::slice::from_raw_parts(px, len) }.chunks(4).filter(|p| p[0] < 128 && p[3] > 0).count();
    unsafe { loki_buffer_free(px, len) };
    dark
}

#[test]
fn a_drawn_rectangle_shows_on_the_artboard() {
    let s = loki_session_new();
    let before = ink(s);
    let r = call(s, "engine.execute", Some(r#"{"command":"shape.rectangle","params":{"x":50,"y":50,"width":200,"height":150}}"#));
    assert_eq!(r["ok"], true, "{r}");
    assert!(ink(s) > before, "the rectangle did not show");
    unsafe { loki_session_free(s) };
}

#[test]
fn the_viewport_zoom_reaches_the_tools_and_junk_is_ignored() {
    let s = loki_session_new();
    assert_eq!(call(s, "loki.viewport", Some(r#"{"zoom":2.5}"#))["result"]["zoom"], 2.5);
    assert_eq!(call(s, "loki.viewport", Some(r#"{"zoom":-1}"#))["result"]["zoom"], 2.5);
    assert_eq!(call(s, "loki.viewport", Some(r#"{"zoom":"x"}"#))["result"]["zoom"], 2.5);
    unsafe { loki_session_free(s) };
}

#[test]
fn a_pen_drag_with_the_rectangle_tool_draws_a_rectangle() {
    let s = loki_session_new();
    call(s, "engine.execute", Some(r#"{"command":"tool.select","params":{"tool":"rectangle"}}"#));
    let before = call(s, "document.inspect", None)["result"]["objects"].as_u64().unwrap();
    let r = call(
        s,
        "ui.pointer",
        Some(
            r#"{"events":[
        {"kind":"down","x":100,"y":100,"pressure":0.4,"tiltX":10,"tiltY":-20},
        {"kind":"drag","x":200,"y":180,"pressure":0.9},
        {"kind":"up","x":300,"y":260,"pressure":0.3}]}"#,
        ),
    );
    assert_eq!(r["ok"], true, "{r}");
    let after = call(s, "document.inspect", None)["result"]["objects"].as_u64().unwrap();
    assert_eq!(after, before + 1);
    unsafe { loki_session_free(s) };
}

#[test]
fn the_overlay_shows_the_selected_paths_and_what_the_tool_draws() {
    let s = loki_session_new();
    let r = call(s, "engine.execute", Some(r#"{"command":"shape.rectangle","params":{"x":10,"y":20,"width":100,"height":50}}"#));
    assert_eq!(r["ok"], true, "{r}");
    let o = call(s, "loki.overlay", None)["result"].clone();
    let sel = o["selection"].as_array().unwrap();
    assert_eq!(sel.len(), 1, "{o}");
    assert_eq!(sel[0]["anchors"].as_array().unwrap().len(), 4, "{o}");
    assert!(sel[0]["d"].as_str().unwrap().starts_with('M'), "{o}");
    // A marquee being dragged with the Selection tool is the tool's own overlay.
    call(s, "engine.execute", Some(r#"{"command":"tool.select","params":{"tool":"selection"}}"#));
    call(s, "ui.pointer", Some(r#"{"events":[{"kind":"down","x":300,"y":300},{"kind":"drag","x":400,"y":380}]}"#));
    let o = call(s, "loki.overlay", None)["result"].clone();
    assert!(o["tool"].as_array().unwrap().iter().any(|t| t["kind"] == "marquee"), "{o}");
    unsafe { loki_session_free(s) };
}

fn exec(s: *mut LokiSession, command: &str, params: serde_json::Value) -> serde_json::Value {
    let body = serde_json::json!({"command": command, "params": params}).to_string();
    let r = call(s, "engine.execute", Some(&body));
    assert_eq!(r["ok"], true, "{command}: {r}");
    r["result"].clone()
}

fn paint(s: *mut LokiSession) -> serde_json::Value {
    call(s, "document.inspect", None)["result"]["paint"].clone()
}

/// The colour picker and the stroke chip: with nothing selected they set what is drawn next.
#[test]
fn the_options_bar_sets_the_paint_and_weight_of_new_art() {
    let s = loki_session_new();
    exec(s, "paint.setFill", serde_json::json!({"color": "#12ab34"}));
    exec(s, "paint.setStroke", serde_json::json!({"color": "#cc2200"}));
    exec(s, "stroke.set", serde_json::json!({"weight": 7.5}));
    let p = paint(s);
    assert_eq!((p["fill"].as_str(), p["stroke"].as_str(), p["strokeWidth"].as_f64()), (Some("#12ab34"), Some("#cc2200"), Some(7.5)), "{p}");
    // A stroke drawn with the Paintbrush takes them.
    exec(s, "tool.select", serde_json::json!({"tool": "paintbrush"}));
    let r = call(
        s,
        "ui.pointer",
        Some(
            r#"{"events":[{"kind":"down","x":100,"y":100},{"kind":"drag","x":150,"y":130},{"kind":"drag","x":220,"y":120},
        {"kind":"drag","x":300,"y":180},{"kind":"up","x":300,"y":180}]}"#,
        ),
    );
    assert_eq!(r["ok"], true, "{r}");
    let d = call(s, "document.inspect", None)["result"].clone();
    let id = d["selection"][0].as_u64().expect("the new stroke is selected");
    let node = d["layers"][0]["children"].as_array().unwrap().iter().find(|n| n["id"].as_u64() == Some(id)).expect("the stroke is in the layer").clone();
    assert_eq!((node["stroke"].as_str(), node["strokeWidth"].as_f64()), (Some("#cc2200"), Some(7.5)), "the brush stroke: {node}");
    // None is a paint too.
    exec(s, "paint.setFill", serde_json::json!({"none": true}));
    assert_eq!(paint(s)["fill"], "None");
    exec(s, "paint.swap", serde_json::json!({}));
    assert_eq!(paint(s)["stroke"], "None");
    exec(s, "paint.default", serde_json::json!({}));
    let p = paint(s);
    assert_eq!((p["fill"].as_str(), p["stroke"].as_str(), p["strokeWidth"].as_f64()), (Some("#ffffff"), Some("#000000"), Some(1.0)), "{p}");
    unsafe { loki_session_free(s) };
}

/// The selection's own controls: opacity, and a tap on an object's row in the Layers panel.
#[test]
fn the_selection_takes_colour_opacity_and_a_tap_in_the_layers_panel() {
    let s = loki_session_new();
    let a = exec(s, "shape.rectangle", serde_json::json!({"x": 10, "y": 10, "width": 50, "height": 50}));
    exec(s, "shape.rectangle", serde_json::json!({"x": 100, "y": 10, "width": 50, "height": 50}));
    let d = call(s, "document.inspect", None)["result"].clone();
    let first = d["layers"][0]["children"][0]["id"].as_u64().unwrap();
    exec(s, "select.set", serde_json::json!({"ids": [first]}));
    exec(s, "transparency.set", serde_json::json!({"opacity": 40}));
    assert_eq!(exec(s, "transparency.info", serde_json::json!({}))["opacity"], 40.0, "{a}");
    exec(s, "paint.setFill", serde_json::json!({"color": "#0000ff"}));
    assert_eq!(paint(s)["fill"], "#0000ff");
    let before = call(s, "document.inspect", None)["result"]["objects"].as_u64().unwrap();
    exec(s, "edit.clear", serde_json::json!({}));
    assert_eq!(call(s, "document.inspect", None)["result"]["objects"].as_u64().unwrap(), before - 1);
    unsafe { loki_session_free(s) };
}
