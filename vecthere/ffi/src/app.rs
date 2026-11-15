//! What is VectorCraft's own: the session, how methods reach it, and the artboard renderer.

use serde_json::{Value, json};
use vectorcraft_mcp::{Backend, Headless};
use vectorcraft_render::encode::{RasterExportOptions, RasterFormat};

use crate::Rendered;

pub(crate) struct Inner {
    h: Headless,
}

/// A session with a fresh default (Letter) document.
pub(crate) fn new() -> Inner {
    Inner { h: Headless::with_document() }
}

/// Every control-protocol method the headless backend answers (`engine.execute`, `engine.commands`,
/// `document.inspect`, `ui.pointer`, `ui.key`, `ui.tool.select`, `app.open`, …).
pub(crate) fn call(inner: &mut Inner, method: &str, params: Value) -> Result<Value, String> {
    inner.h.call(method, params)
}

/// The headless backend applies a command's requests itself (tool switches, placing) and returns
/// dialogs in its replies to `ui.pointer`; nothing is queued for the front end yet (phase 6).
pub(crate) fn ui_requests(_inner: &mut Inner) -> Value {
    json!([])
}

/// `artboards`: one page per artboard of the active document.
pub(crate) fn surface(inner: &mut Inner) -> Value {
    let Some(st) = inner.h.session.active() else { return json!({"kind": "artboards", "pages": 0, "current": 0}) };
    json!({"kind": "artboards", "pages": st.doc.artboards.len(), "current": 0})
}

/// Artboard `page` on white, with the options of a PNG export (so its pixels are the exported
/// file's), at `scale` pixels per point.
pub(crate) fn render(inner: &mut Inner, page: u32, scale: f32, max_pixels: u64) -> Option<Rendered> {
    let st = inner.h.session.active()?;
    let doc = st.doc.clone();
    let r = doc.artboards.get(usize::try_from(page).ok()?)?.rect;
    let (w, h) = vectorcraft_render::raster_size(r, f64::from(scale)).ok()?;
    if u64::from(w) * u64::from(h) > max_pixels {
        return None;
    }
    let mut opts = RasterExportOptions { background: Some([255; 3]), ..Default::default() }.render_options(RasterFormat::Png);
    opts.trace_views = true;
    let img = inner.h.renderer.render_region_with(&doc, r, f64::from(scale), &opts);
    Some(Rendered { width: img.width, height: img.height, pages: u32::try_from(doc.artboards.len()).unwrap_or(u32::MAX), rgba: img.to_straight() })
}
