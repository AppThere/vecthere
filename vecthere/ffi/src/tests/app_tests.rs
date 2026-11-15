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
