//! What a native front end draws over the art, in view pixels: the active tool's feedback (pen
//! rubber band, marquee, smart guides, measurements) and the selection (outlines, anchors, handles,
//! the bounding box).
//!
//! The desktop canvas draws the same from the same engine state (`ui-egui/src/canvas.rs`); this is
//! the toolkit-free equivalent, flattened to polylines so Compose draws it with a few calls.

use kurbo::{BezPath, PathEl, Point};
use vectorcraft_doc::{Document, Node, NodeKind, Selection};
use vectorcraft_tools::Overlay;

use crate::viewport::Viewport;

/// Flattening tolerance in pixels.
const FLATTEN_TOL: f64 = 0.25;
/// The most anchors outlined (a traced photo selects hundreds of thousands of paths): beyond it the
/// selection's bounds stand for them, as on the desktop.
const MAX_ANCHORS: usize = 20_000;
/// The most points one overlay frame carries across the FFI.
const MAX_POINTS: usize = 400_000;

/// An ARGB colour (`0xAARRGGBB`), as Compose's `Color(Int)` and Android's `Color` take.
pub fn argb(rgb: [u8; 3], alpha: u8) -> u32 {
    u32::from(alpha) << 24 | u32::from(rgb[0]) << 16 | u32::from(rgb[1]) << 8 | u32::from(rgb[2])
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum OverlayShape {
    /// Connected points `[x0, y0, x1, y1, …]`; `width` in screen points.
    Polyline { points: Vec<f32>, closed: bool, color: u32, width: f32, dashed: bool },
    /// A filled polygon (text selection highlight, marquee fill).
    Fill { points: Vec<f32>, color: u32 },
    /// An anchor square `size` screen points across: filled when selected.
    Anchor { x: f32, y: f32, color: u32, filled: bool, size: f32 },
    /// A Curvature tool point, a circle `size` screen points across: filled for the last touched point.
    CurvePoint { x: f32, y: f32, color: u32, filled: bool, size: f32 },
    /// A direction handle's end.
    Handle { x: f32, y: f32, color: u32 },
    /// A bounding-box handle (white square with a coloured edge).
    BoxHandle { x: f32, y: f32, color: u32, size: f32 },
    /// A smart-guide style label.
    Label { x: f32, y: f32, text: String, color: u32 },
    /// The measurement pill near the pointer.
    Measure { x: f32, y: f32, text: String },
    /// A colour chip (a gradient stop), ringed when selected.
    Swatch { x: f32, y: f32, color: u32, selected: bool },
}

/// Collects shapes, keeping the frame under [`MAX_POINTS`].
pub struct Out<'a> {
    vp: &'a Viewport,
    pub shapes: Vec<OverlayShape>,
    points: usize,
}

impl<'a> Out<'a> {
    pub fn new(vp: &'a Viewport) -> Self {
        Self { vp, shapes: vec![], points: 0 }
    }

    fn xy(&self, p: Point) -> (f32, f32) {
        let s = self.vp.to_screen(p);
        (s.x as f32, s.y as f32)
    }

    fn flat(&self, ps: &[Point]) -> Vec<f32> {
        ps.iter().flat_map(|p| <[f32; 2]>::from(self.xy(*p))).collect()
    }

    fn push(&mut self, s: OverlayShape, points: usize) {
        if self.points + points <= MAX_POINTS {
            self.points += points;
            self.shapes.push(s);
        }
    }

    /// `bp` (document space) as polylines, one per subpath.
    pub fn path(&mut self, bp: &BezPath, color: u32, width: f32, dashed: bool) {
        let screen = self.vp.to_screen_affine() * bp.clone();
        let mut run: Vec<f32> = vec![];
        let mut subpaths: Vec<(Vec<f32>, bool)> = vec![];
        kurbo::flatten(screen.elements().iter().copied(), FLATTEN_TOL, |el| match el {
            PathEl::MoveTo(p) => {
                if run.len() >= 4 {
                    subpaths.push((std::mem::take(&mut run), false));
                }
                run.clear();
                run.extend([p.x as f32, p.y as f32]);
            }
            PathEl::LineTo(p) => run.extend([p.x as f32, p.y as f32]),
            PathEl::ClosePath => {
                if run.len() >= 4 {
                    subpaths.push((std::mem::take(&mut run), true));
                }
            }
            // `flatten` emits lines only.
            PathEl::QuadTo(..) | PathEl::CurveTo(..) => {}
        });
        if run.len() >= 4 {
            subpaths.push((run, false));
        }
        for (points, closed) in subpaths {
            let n = points.len() / 2;
            self.push(OverlayShape::Polyline { points, closed, color, width, dashed }, n);
        }
    }

    fn line(&mut self, a: Point, b: Point, color: u32, width: f32, dashed: bool) {
        self.push(OverlayShape::Polyline { points: self.flat(&[a, b]), closed: false, color, width, dashed }, 2);
    }

    fn anchor(&mut self, p: Point, color: u32, filled: bool, size: f32) {
        let (x, y) = self.xy(p);
        self.push(OverlayShape::Anchor { x, y, color, filled, size }, 1);
    }

    /// The active tool's overlays.
    pub fn tool(&mut self, overlays: &[Overlay]) {
        const GUIDE: [u8; 3] = [0x4a, 0x90, 0xe2];
        for o in overlays {
            match o {
                Overlay::Marquee(r) => {
                    let q = [Point::new(r.x0, r.y0), Point::new(r.x1, r.y0), Point::new(r.x1, r.y1), Point::new(r.x0, r.y1)];
                    let points = self.flat(&q);
                    self.push(OverlayShape::Fill { points: points.clone(), color: argb(GUIDE, 0x22) }, 4);
                    self.push(OverlayShape::Polyline { points, closed: true, color: argb([0x33; 3], 0xff), width: 1.0, dashed: true }, 4);
                }
                Overlay::Path { path, color, width, dashed } => self.path(path, argb(*color, 0xff), *width, *dashed),
                Overlay::Line { a, b, color, dashed } => self.line(*a, *b, argb(*color, 0xff), 1.0, *dashed),
                Overlay::Anchor { p, color, filled, size } => self.anchor(*p, argb(*color, 0xff), *filled, *size),
                Overlay::CurvePoint { p, color, filled, size } => {
                    let (x, y) = self.xy(*p);
                    self.push(OverlayShape::CurvePoint { x, y, color: argb(*color, 0xff), filled: *filled, size: *size }, 1);
                }
                Overlay::Handle { p, color } => {
                    let (x, y) = self.xy(*p);
                    self.push(OverlayShape::Handle { x, y, color: argb(*color, 0xff) }, 1);
                }
                Overlay::Label { p, text, color } => {
                    let (x, y) = self.xy(*p);
                    self.push(OverlayShape::Label { x, y, text: text.clone(), color: argb(*color, 0xff) }, 1);
                }
                Overlay::Measure { p, text } => {
                    let (x, y) = self.xy(*p);
                    self.push(OverlayShape::Measure { x, y, text: text.clone() }, 1);
                }
                Overlay::Highlight { quad, color } => {
                    let [r, g, b, a] = *color;
                    self.push(OverlayShape::Fill { points: self.flat(quad), color: argb([r, g, b], a) }, 4);
                }
                Overlay::Swatch { p, color, selected } => {
                    let (x, y) = self.xy(*p);
                    let [r, g, b, a] = *color;
                    self.push(OverlayShape::Swatch { x, y, color: argb([r, g, b], a), selected: *selected }, 1);
                }
                Overlay::GridLine { a, b, color } => {
                    let [r, g, bl, al] = *color;
                    self.line(*a, *b, argb([r, g, bl], al), 1.0, false);
                }
            }
        }
    }

    /// The selection: each object's outline and anchors (handles of selected anchors when `direct`),
    /// and the bounding box with its eight handles when `bbox` is given.
    pub fn selection(&mut self, doc: &Document, sel: &Selection, direct: bool, bbox: Option<vectorcraft_doc::OrientedBox>) {
        let mut anchors = 0usize;
        for id in &sel.objects {
            if let Some(n) = doc.node(*id) {
                walk_drawn(n, &mut |c| {
                    if let NodeKind::Path { path, .. } = &c.kind {
                        anchors = anchors.saturating_add(path.anchors().count());
                    }
                });
            }
        }
        let big = anchors > MAX_ANCHORS;
        if big && let (Some(b), Some(first)) = (doc.bounds_of(&sel.objects, false), sel.objects.first()) {
            let q = [Point::new(b.x0, b.y0), Point::new(b.x1, b.y0), Point::new(b.x1, b.y1), Point::new(b.x0, b.y1)];
            self.push(
                OverlayShape::Polyline { points: self.flat(&q), closed: true, color: argb(doc.layer_color(*first), 0xff), width: 1.0, dashed: false },
                4,
            );
        }
        for id in sel.objects.iter().filter(|_| !big) {
            let Some(n) = doc.node(*id) else { continue };
            let color = argb(doc.layer_color(*id), 0xff);
            let partial = sel.partial(*id);
            let mut outline = BezPath::new();
            let mut marks: Vec<(Point, bool)> = vec![];
            let mut handles: Vec<(Point, Point)> = vec![];
            walk_drawn(n, &mut |c| match &c.kind {
                NodeKind::Path { path, .. } => {
                    outline.extend(path.to_bezpath());
                    for (si, ai, a) in path.anchors() {
                        let selected = match partial {
                            Some(set) => set.contains(&(si, ai)),
                            None => !direct || c.id == *id,
                        };
                        if selected && (direct || partial.is_some()) {
                            handles.extend([a.h_in, a.h_out].into_iter().filter(|h| h.distance(a.p) > 1e-6).map(|h| (a.p, h)));
                        }
                        marks.push((a.p, selected));
                    }
                }
                NodeKind::Text(_) | NodeKind::Image(_) | NodeKind::SymbolInstance { .. } | NodeKind::PlacedDocument(_) => {
                    if let Some(b) = c.geometric_bounds() {
                        outline.extend(vectorcraft_geom::shapes::rectangle(b).to_bezpath());
                    }
                }
                _ => {}
            });
            self.path(&outline, color, 1.0, false);
            let size = if direct || partial.is_some() { 7.0 } else { 6.0 };
            for (p, selected) in marks {
                self.anchor(p, color, selected, size);
            }
            for (a, h) in handles {
                self.line(a, h, color, 1.0, false);
                let (x, y) = self.xy(h);
                self.push(OverlayShape::Handle { x, y, color }, 1);
            }
        }
        if let (Some(b), Some(first)) = (bbox, sel.objects.first()) {
            let color = argb(doc.layer_color(*first), 0xff);
            self.push(OverlayShape::Polyline { points: self.flat(&b.corners()), closed: true, color, width: 1.0, dashed: false }, 4);
            for h in vectorcraft_tools::bbox::Handle::ALL {
                let (x, y) = self.xy(b.to_doc() * h.pos(b.rect));
                self.push(OverlayShape::BoxHandle { x, y, color, size: 8.0 }, 1);
            }
        }
    }
}

/// `n` and the objects drawn inside it (an envelope shows itself, not its content).
fn walk_drawn<'a>(n: &'a Node, f: &mut impl FnMut(&'a Node)) {
    f(n);
    if matches!(n.kind, NodeKind::Envelope { .. }) {
        return;
    }
    for c in n.children().into_iter().flatten().skip(usize::from(n.extras().shaper.is_some())) {
        walk_drawn(c, f);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use kurbo::Rect;

    fn vp() -> Viewport {
        let mut v = Viewport::default();
        v.resize(200, 100, 1.0);
        v.center = Point::new(100.0, 50.0);
        v
    }

    #[test]
    fn argb_packs_alpha_first() {
        assert_eq!(argb([0x12, 0x34, 0x56], 0x78), 0x7812_3456);
    }

    #[test]
    fn paths_come_out_in_view_pixels() {
        let v = vp();
        let mut o = Out::new(&v);
        o.path(&vectorcraft_geom::shapes::rectangle(Rect::new(0.0, 0.0, 10.0, 10.0)).to_bezpath(), 0xff00_0000, 1.0, false);
        let Some(OverlayShape::Polyline { points, closed, .. }) = o.shapes.first() else { panic!("no polyline: {:?}", o.shapes) };
        assert!(*closed);
        assert_eq!(&points[..2], &[0.0, 0.0]);
        assert!(points.chunks(2).any(|p| p == [10.0, 10.0]));
    }

    #[test]
    fn marquee_is_filled_and_outlined() {
        let v = vp();
        let mut o = Out::new(&v);
        o.tool(&[Overlay::Marquee(Rect::new(10.0, 10.0, 20.0, 30.0))]);
        assert!(matches!(o.shapes.as_slice(), [OverlayShape::Fill { .. }, OverlayShape::Polyline { closed: true, dashed: true, .. }]));
    }

    #[test]
    fn frame_size_is_capped() {
        let v = vp();
        let mut o = Out::new(&v);
        for _ in 0..(MAX_POINTS / 2 + 10) {
            o.line(Point::ZERO, Point::new(1.0, 1.0), 0, 1.0, false);
        }
        assert_eq!(o.shapes.len(), MAX_POINTS / 2);
    }
}
