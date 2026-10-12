//! The engine as a native front end drives it: open, draw with a pen and fingers, render, save.

use super::*;
use input::{BUTTON_STYLUS_PRIMARY, PointerPhase, ToolType};

fn engine() -> VectorEngine {
    let e = VectorEngine::new();
    e.resize(800, 600, 2.0);
    e.new_document(400.0, 300.0).unwrap();
    e
}

fn sample(id: u64, tool_type: ToolType, phase: PointerPhase, x: f64, y: f64, t: u64) -> PointerSample {
    PointerSample { pointer_id: id, tool_type, phase, x, y, pressure: 0.6, tilt: 0.3, azimuth: 1.0, buttons: 0, time_ms: t }
}

/// A pen stroke from `a` to `b` in view pixels.
fn stroke(e: &VectorEngine, tool_type: ToolType, buttons: u32, a: (f64, f64), b: (f64, f64)) -> InputResult {
    let mut s = vec![sample(1, tool_type, PointerPhase::Down, a.0, a.1, 0)];
    for k in 1..=8u32 {
        let t = f64::from(k) / 8.0;
        s.push(sample(1, tool_type, PointerPhase::Move, a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t, u64::from(k) * 8));
    }
    s.push(sample(1, tool_type, PointerPhase::Up, b.0, b.1, 80));
    for x in &mut s {
        x.buttons = buttons;
    }
    e.pointer(s)
}

fn object_count(e: &VectorEngine) -> usize {
    let i = e.lock();
    let doc = &i.session.active().unwrap().doc;
    let mut n = 0;
    for l in &doc.layers {
        n += l.children().map_or(0, Vec::len);
    }
    n
}

#[test]
fn new_document_is_fitted_and_titled() {
    let e = engine();
    let st = e.document_state();
    assert!(st.has_document && !st.dirty && st.artboard_count == 1);
    let v = e.view_state();
    // 300 px of 600 at density 2 is 300 pt; the 400 × 300 artboard with 24 pt margins.
    assert!(v.zoom > 0.5 && v.zoom < 1.0, "{v:?}");
    assert_eq!((v.center_x, v.center_y), (200.0, 150.0));
}

#[test]
fn pen_draws_a_rectangle_and_two_finger_tap_undoes_it() {
    let e = engine();
    e.select_tool("rectangle".into()).unwrap();
    let r = stroke(&e, ToolType::Stylus, 0, (300.0, 200.0), (500.0, 400.0));
    assert!(r.error.is_none(), "{r:?}");
    assert!(r.document_changed);
    assert_eq!(object_count(&e), 1);
    assert!(e.document_state().can_undo);
    assert!(e.stylus_seen());
    // Overlays: the new rectangle is selected.
    assert!(!e.overlays().is_empty());
    // Two fingers tap: undo.
    let tap = e.pointer(vec![
        sample(10, ToolType::Finger, PointerPhase::Down, 100.0, 100.0, 1000),
        sample(11, ToolType::Finger, PointerPhase::Down, 200.0, 100.0, 1010),
        sample(10, ToolType::Finger, PointerPhase::Up, 100.0, 100.0, 1100),
        sample(11, ToolType::Finger, PointerPhase::Up, 200.0, 100.0, 1110),
    ]);
    assert_eq!(tap.commands, vec!["edit.undo".to_string()]);
    assert!(tap.document_changed);
    assert_eq!(object_count(&e), 0);
}

#[test]
fn fingers_pan_once_a_pen_was_seen() {
    let e = engine();
    e.select_tool("rectangle".into()).unwrap();
    stroke(&e, ToolType::Stylus, 0, (300.0, 200.0), (400.0, 300.0));
    let before = e.view_state();
    let r = e.pointer(vec![
        sample(5, ToolType::Finger, PointerPhase::Down, 100.0, 100.0, 2000),
        sample(5, ToolType::Finger, PointerPhase::Move, 160.0, 100.0, 2010),
        sample(5, ToolType::Finger, PointerPhase::Up, 160.0, 100.0, 2020),
    ]);
    assert!(r.view_changed && !r.document_changed);
    let after = e.view_state();
    // 60 px at density 2 and the fitted zoom: the centre moved left in the document.
    assert!(after.center_x < before.center_x);
    assert_eq!(object_count(&e), 1);
}

#[test]
fn finger_draws_without_a_pen_and_pinch_cancels_it() {
    let e = engine();
    e.select_tool("rectangle".into()).unwrap();
    let r = stroke(&e, ToolType::Finger, 0, (300.0, 200.0), (500.0, 400.0));
    assert!(r.document_changed, "{r:?}");
    assert_eq!(object_count(&e), 1);
    // A stroke that becomes a pinch leaves nothing behind.
    let r = e.pointer(vec![
        sample(1, ToolType::Finger, PointerPhase::Down, 100.0, 100.0, 0),
        sample(1, ToolType::Finger, PointerPhase::Move, 150.0, 150.0, 30),
        sample(2, ToolType::Finger, PointerPhase::Down, 400.0, 400.0, 60),
        sample(2, ToolType::Finger, PointerPhase::Move, 500.0, 500.0, 90),
        sample(1, ToolType::Finger, PointerPhase::Up, 150.0, 150.0, 120),
        sample(2, ToolType::Finger, PointerPhase::Up, 500.0, 500.0, 130),
    ]);
    assert!(r.error.is_none(), "{r:?}");
    assert_eq!(object_count(&e), 1);
    assert!(e.view_state().zoom > 0.7);
}

#[test]
fn eraser_end_and_barrel_button_come_back_to_the_tool() {
    let e = engine();
    e.select_tool("pencil".into()).unwrap();
    let r = stroke(&e, ToolType::Eraser, 0, (300.0, 200.0), (320.0, 220.0));
    assert!(r.error.is_none(), "{r:?}");
    assert_eq!(e.active_tool(), "pencil");
    let mut p = e.input_prefs();
    p.stylus_button_tool = "hand".into();
    e.set_input_prefs(p);
    stroke(&e, ToolType::Stylus, BUTTON_STYLUS_PRIMARY, (300.0, 200.0), (320.0, 220.0));
    assert_eq!(e.active_tool(), "pencil");
}

#[test]
fn pencil_takes_pressure() {
    let e = engine();
    e.select_tool("paintbrush".into()).unwrap();
    let r = stroke(&e, ToolType::Stylus, 0, (300.0, 200.0), (500.0, 260.0));
    assert!(r.error.is_none() && r.document_changed, "{r:?}");
}

#[test]
fn renders_the_viewport_with_artboard_and_pasteboard() {
    let e = engine();
    let f = e.render().unwrap();
    assert_eq!((f.width, f.height), (800, 600));
    assert_eq!(f.pixels.len(), 800 * 600 * 4);
    let px = |x: usize, y: usize| &f.pixels[(y * 800 + x) * 4..(y * 800 + x) * 4 + 4];
    // The corner is pasteboard, the middle is the white artboard.
    assert_eq!(px(0, 0), &PASTEBOARD);
    assert_eq!(px(400, 300), &[255, 255, 255, 255]);
    e.set_pasteboard(0xff20_2020);
    assert_eq!(&e.render().unwrap().pixels[..4], &[0x20, 0x20, 0x20, 0xff]);
}

#[test]
fn renders_without_a_document() {
    let e = VectorEngine::new();
    e.resize(10, 10, 1.0);
    let f = e.render().unwrap();
    assert_eq!(f.pixels.len(), 400);
    assert!(!e.document_state().has_document);
}

#[test]
fn opens_and_saves_bytes() {
    let e = VectorEngine::new();
    e.resize(400, 400, 1.0);
    let svg = br##"<svg xmlns="http://www.w3.org/2000/svg" width="100" height="80"><rect x="10" y="10" width="50" height="40" fill="#f00"/></svg>"##;
    let out = e.open_bytes("art.svg".into(), svg.to_vec()).unwrap();
    assert!(out.contains("title"), "{out}");
    assert_eq!(e.document_state().document_count, 1);
    let native = e.save_bytes("vectorcraft".into()).unwrap();
    assert!(!native.is_empty());
    let svg_out = e.save_bytes("svg".into()).unwrap();
    assert!(String::from_utf8_lossy(&svg_out).contains("<svg"));
    // The native bytes open again.
    e.open_bytes("copy.vectorcraft".into(), native).unwrap();
    assert_eq!(e.document_titles().len(), 2);
    assert!(e.thumbnail_png(64).unwrap().starts_with(b"\x89PNG"));
}

#[test]
fn bad_input_is_an_error_not_a_crash() {
    let e = engine();
    assert!(matches!(e.execute("no.such.command".into(), String::new()), Err(VectorError::Engine { .. })));
    assert!(matches!(e.execute("file.new".into(), "{not json".into()), Err(VectorError::Invalid { .. })));
    assert!(matches!(e.select_tool("nope".into()), Err(VectorError::Invalid { .. })));
    assert!(e.open_bytes("x.svg".into(), b"\x00\x01garbage".to_vec()).is_err());
    assert!(e.new_document(f64::NAN, 10.0).is_err());
    assert!(e.set_active_document(99).is_err());
    // Garbage samples go nowhere.
    let r = e
        .pointer(vec![sample(1, ToolType::Stylus, PointerPhase::Down, f64::NAN, 0.0, 0), sample(7, ToolType::Finger, PointerPhase::Up, 0.0, 0.0, 0)]);
    assert!(!r.document_changed);
    e.set_view_state(ViewState { center_x: f64::NAN, center_y: 1e300, zoom: f64::INFINITY, rotation: f64::NAN });
    let v = e.view_state();
    assert!(v.zoom.is_finite() && v.center_y.is_finite() && v.rotation.is_finite());
}

#[test]
fn shortcuts_choose_tools_and_run_commands() {
    let e = engine();
    assert_eq!(e.shortcut("P".into()).unwrap().as_deref(), Some("pen"));
    assert_eq!(e.active_tool(), "pen");
    e.select_tool("rectangle".into()).unwrap();
    stroke(&e, ToolType::Mouse, 0, (300.0, 200.0), (400.0, 300.0));
    assert_eq!(e.shortcut("Cmd+Z".into()).unwrap().as_deref(), Some("edit.undo"));
    assert_eq!(object_count(&e), 0);
    assert_eq!(e.shortcut("Cmd+Alt+Shift+F13".into()).unwrap(), None);
}

#[test]
fn commands_and_tools_are_listed() {
    let e = engine();
    let cmds = e.commands();
    assert!(cmds.len() > 300);
    assert!(cmds.iter().any(|c| c.id == "edit.undo" && c.shortcut.as_deref() == Some("Cmd+Z")));
    let tools = e.tools();
    assert!(tools.iter().flat_map(|g| &g.tools).any(|t| t.id == "pen" && !t.icon.is_empty()));
}

#[test]
fn keys_and_text_reach_the_type_tool() {
    let e = engine();
    e.select_tool("type".into()).unwrap();
    stroke(&e, ToolType::Stylus, 0, (400.0, 300.0), (400.0, 300.0));
    assert!(e.wants_text());
    let r = e.text("Hi".into());
    assert!(r.error.is_none(), "{r:?}");
    e.key(Key::Escape);
    assert_eq!(object_count(&e), 1);
}

#[test]
fn engine_is_shareable_across_threads() {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<VectorEngine>();
}
