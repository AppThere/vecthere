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
    if method == "loki.viewport" {
        // The canvas's zoom (screen pixels per point): tools measure their tolerances and handle
        // sizes in screen pixels through it, so a tap picks what is under a finger at any zoom.
        if let Some(z) = params.get("zoom").and_then(Value::as_f64).filter(|z| z.is_finite() && *z > 0.0) {
            inner.h.view.zoom = z.clamp(0.01, 64.0);
        }
        return Ok(json!({"zoom": inner.h.view.zoom}));
    }
    if method == "loki.overlay" {
        return Ok(overlay(inner));
    }
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

/// Most selection marks returned at once (anchors and handles across the selected art).
const MAX_MARKS: usize = 20_000;

fn pt(p: vectorcraft_geom::Point) -> Value {
    json!([p.x, p.y])
}

fn rgb(c: [u8; 3]) -> String {
    format!("#{:02x}{:02x}{:02x}", c[0], c[1], c[2])
}

/// What the canvas draws over the picture, in document points: the active tool's own feedback
/// (`tool`: marquees, rubber bands, anchors and handles of what it is drawing, smart-guide labels,
/// measurements, text highlights) and the selected art (`selection`: each selected path's outline,
/// its anchors, filled when selected, and under Direct Selection its handles), as the desktop draws
/// them. Paths are SVG path data.
fn overlay(inner: &mut Inner) -> Value {
    use vectorcraft_tools::Overlay as O;
    let view = inner.h.view;
    let tool: Vec<Value> = inner
        .h
        .session
        .overlays(view)
        .into_iter()
        .take(MAX_MARKS)
        .map(|o| match o {
            O::Marquee(r) => json!({"kind": "marquee", "rect": [r.x0, r.y0, r.x1, r.y1]}),
            O::SelectionMarquee { rect: r, .. } => json!({"kind": "marquee", "rect": [r.x0, r.y0, r.x1, r.y1]}),
            O::Path { path, color, width, dashed } => json!({"kind": "path", "d": path.to_svg(), "color": rgb(color), "width": width, "dashed": dashed}),
            O::Line { a, b, color, dashed } => json!({"kind": "line", "a": pt(a), "b": pt(b), "color": rgb(color), "dashed": dashed}),
            O::Anchor { p, color, filled, size } => json!({"kind": "anchor", "p": pt(p), "color": rgb(color), "filled": filled, "size": size}),
            O::CurvePoint { p, color, filled, size } => json!({"kind": "curvePoint", "p": pt(p), "color": rgb(color), "filled": filled, "size": size}),
            O::Handle { p, color } => json!({"kind": "handle", "p": pt(p), "color": rgb(color)}),
            O::Label { p, text, color } => json!({"kind": "label", "p": pt(p), "text": text, "color": rgb(color)}),
            O::Measure { p, text } => json!({"kind": "measure", "p": pt(p), "text": text}),
            O::Distance { a, b, text, color } => json!({"kind": "distance", "a": pt(a), "b": pt(b), "text": text, "color": rgb(color)}),
            O::Highlight { quad, color } => json!({"kind": "highlight", "quad": quad.iter().map(|q| pt(*q)).collect::<Vec<_>>(), "color": format!("#{:02x}{:02x}{:02x}{:02x}", color[0], color[1], color[2], color[3])}),
            O::Swatch { p, color, selected } => json!({"kind": "swatch", "p": pt(p), "color": format!("#{:02x}{:02x}{:02x}{:02x}", color[0], color[1], color[2], color[3]), "selected": selected}),
            O::GridLine { a, b, color } => json!({"kind": "gridLine", "a": pt(a), "b": pt(b), "color": format!("#{:02x}{:02x}{:02x}{:02x}", color[0], color[1], color[2], color[3])}),
        })
        .collect();
    let mut selection = Vec::new();
    if let Some(st) = inner.h.session.active() {
        let direct = matches!(inner.h.session.tool_id(), "directSelection" | "groupSelection");
        let mut marks = 0usize;
        for &id in &st.selection.objects {
            let Some(node) = st.doc.node(id) else { continue };
            let partial = st.selection.partial(id);
            let mut stack = vec![node];
            while let Some(n) = stack.pop() {
                if marks >= MAX_MARKS {
                    break;
                }
                if let vectorcraft_doc::NodeKind::Path { path, .. } = &n.kind {
                    let mut anchors = Vec::new();
                    let mut handles = Vec::new();
                    for (si, ai, a) in path.anchors() {
                        let selected = partial.is_none_or(|set| set.contains(&(si, ai)));
                        anchors.push(json!({"p": pt(a.p), "selected": selected && partial.is_some()}));
                        if direct && n.id == id && selected {
                            for h in [a.h_in, a.h_out] {
                                if h.distance(a.p) > 1e-6 {
                                    handles.push(json!({"a": pt(a.p), "b": pt(h)}));
                                }
                            }
                        }
                        marks += 1;
                    }
                    selection.push(json!({"d": path.to_bezpath().to_svg(), "anchors": anchors, "handles": handles}));
                }
                for c in n.children().into_iter().flatten() {
                    stack.push(c);
                }
            }
        }
    }
    json!({"tool": tool, "selection": selection})
}
