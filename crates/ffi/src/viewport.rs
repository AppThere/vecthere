//! The canvas view of a native front end: where the document sits on screen, and the conversions
//! between view pixels (what touch and pen events report) and document points.
//!
//! The same model as the desktop canvas: `zoom` is screen points (Android dp, iOS points) per
//! document point, `rotation` turns the view (View › Rotate View), and `density` is pixels per
//! screen point, so the art renders at the display's full resolution while tolerances stay in
//! points.

use kurbo::{Affine, Point, Rect, Vec2};
use vectorcraft_tools::ScreenFrame;

/// The zoom range, as the desktop app's (3.13% to 64000%).
pub const MIN_ZOOM: f64 = 0.0313;
pub const MAX_ZOOM: f64 = 640.0;

/// The canvas size cap in pixels per side: a frame is allocated from it on every render.
pub const MAX_SIDE: u32 = 8192;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Viewport {
    /// The document point at the centre of the view.
    pub center: Point,
    /// Screen points per document point.
    pub zoom: f64,
    /// View rotation in degrees, in the desktop canvas's sense (View › Rotate View).
    pub rotation: f64,
    /// The view's size in pixels.
    pub width: u32,
    pub height: u32,
    /// Pixels per screen point (Android `density`, iOS `contentScaleFactor`).
    pub density: f64,
}

impl Default for Viewport {
    fn default() -> Self {
        Self { center: Point::ZERO, zoom: 1.0, rotation: 0.0, width: 1, height: 1, density: 1.0 }
    }
}

/// `v` when finite, else `fallback`.
fn finite(v: f64, fallback: f64) -> f64 {
    if v.is_finite() { v } else { fallback }
}

impl Viewport {
    /// Resize to `width` × `height` pixels at `density`, keeping the centre.
    pub fn resize(&mut self, width: u32, height: u32, density: f64) {
        self.width = width.clamp(1, MAX_SIDE);
        self.height = height.clamp(1, MAX_SIDE);
        self.density = finite(density, 1.0).clamp(0.25, 8.0);
    }

    /// Document points → view pixels.
    pub fn to_screen_affine(&self) -> Affine {
        Affine::translate((f64::from(self.width) / 2.0, f64::from(self.height) / 2.0))
            * Affine::rotate(self.rotation.to_radians())
            * Affine::scale(self.zoom * self.density)
            * Affine::translate(-self.center.to_vec2())
    }

    /// View pixels → document points.
    pub fn to_doc_affine(&self) -> Affine {
        self.to_screen_affine().inverse()
    }

    pub fn to_doc(&self, x: f64, y: f64) -> Point {
        self.to_doc_affine() * Point::new(x, y)
    }

    pub fn to_screen(&self, p: Point) -> Point {
        self.to_screen_affine() * p
    }

    /// The window as the tools see it (screen-fixed widgets such as the Plane Switching Widget sit
    /// in it), measured in screen points as on the desktop.
    pub fn screen_frame(&self) -> ScreenFrame {
        let inv = self.to_doc_affine();
        let d = self.density;
        let origin = inv * Point::ZERO;
        ScreenFrame {
            origin,
            right: (inv * Point::new(d, 0.0)) - origin,
            down: (inv * Point::new(0.0, d)) - origin,
            size: (f64::from(self.width) / d, f64::from(self.height) / d),
        }
    }

    /// Move the art by `dx`, `dy` view pixels.
    pub fn pan(&mut self, dx: f64, dy: f64) {
        let (dx, dy) = (finite(dx, 0.0), finite(dy, 0.0));
        let a = self.to_doc_affine();
        let shift: Vec2 = (a * Point::ZERO) - (a * Point::new(dx, dy));
        self.center += shift;
        self.clamp_center();
    }

    /// Multiply the zoom by `factor`, keeping the document point under view pixel `x`, `y` still.
    pub fn zoom_about(&mut self, factor: f64, x: f64, y: f64) {
        let factor = finite(factor, 1.0);
        if factor <= 0.0 {
            return;
        }
        let (x, y) = (finite(x, 0.0), finite(y, 0.0));
        let before = self.to_doc(x, y);
        self.zoom = (self.zoom * factor).clamp(MIN_ZOOM, MAX_ZOOM);
        let after = self.to_doc(x, y);
        self.center += before - after;
        self.clamp_center();
    }

    /// Turn the view by `degrees` about view pixel `x`, `y`.
    pub fn rotate_about(&mut self, degrees: f64, x: f64, y: f64) {
        let degrees = finite(degrees, 0.0);
        let (x, y) = (finite(x, 0.0), finite(y, 0.0));
        let before = self.to_doc(x, y);
        self.rotation = normalize_degrees(self.rotation + degrees);
        let after = self.to_doc(x, y);
        self.center += before - after;
        self.clamp_center();
    }

    /// Show `r` (document points) whole and centred, with `margin` screen points around it.
    pub fn fit(&mut self, r: Rect, margin: f64) {
        if !(r.width() > 0.0 && r.height() > 0.0 && r.x0.is_finite() && r.y0.is_finite() && r.x1.is_finite() && r.y1.is_finite()) {
            return;
        }
        let margin = finite(margin, 0.0).max(0.0);
        let w = (f64::from(self.width) / self.density - 2.0 * margin).max(1.0);
        let h = (f64::from(self.height) / self.density - 2.0 * margin).max(1.0);
        // The rotated rectangle's extent on screen.
        let (s, c) = self.rotation.to_radians().sin_cos();
        let (rw, rh) = (r.width() * c.abs() + r.height() * s.abs(), r.width() * s.abs() + r.height() * c.abs());
        self.zoom = (w / rw).min(h / rh).clamp(MIN_ZOOM, MAX_ZOOM);
        self.center = r.center();
    }

    /// Keep the centre within the coordinate range the engine accepts.
    fn clamp_center(&mut self) {
        let m = vectorcraft_engine::MAX_COORD;
        self.center = Point::new(finite(self.center.x, 0.0).clamp(-m, m), finite(self.center.y, 0.0).clamp(-m, m));
    }
}

/// `deg` in (-180, 180].
pub fn normalize_degrees(deg: f64) -> f64 {
    let d = finite(deg, 0.0).rem_euclid(360.0);
    if d > 180.0 { d - 360.0 } else { d }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vp() -> Viewport {
        let mut v = Viewport::default();
        v.resize(800, 600, 2.0);
        v.center = Point::new(100.0, 50.0);
        v.zoom = 1.5;
        v
    }

    fn close(a: Point, b: Point) -> bool {
        a.distance(b) < 1e-6
    }

    #[test]
    fn centre_maps_to_the_middle_of_the_view() {
        let v = vp();
        assert!(close(v.to_screen(v.center), Point::new(400.0, 300.0)));
        assert!(close(v.to_doc(400.0, 300.0), v.center));
    }

    #[test]
    fn round_trips_under_rotation() {
        let mut v = vp();
        v.rotation = 33.0;
        let p = Point::new(-20.0, 75.0);
        assert!(close(v.to_doc(v.to_screen(p).x, v.to_screen(p).y), p));
    }

    #[test]
    fn zoom_keeps_the_focus_still() {
        let mut v = vp();
        v.rotation = 20.0;
        let before = v.to_doc(123.0, 456.0);
        v.zoom_about(2.5, 123.0, 456.0);
        assert!((v.zoom - 3.75).abs() < 1e-9);
        assert!(close(v.to_doc(123.0, 456.0), before));
    }

    #[test]
    fn zoom_is_clamped_and_ignores_nonsense() {
        let mut v = vp();
        v.zoom_about(1e9, 0.0, 0.0);
        assert_eq!(v.zoom, MAX_ZOOM);
        v.zoom_about(f64::NAN, 0.0, 0.0);
        v.zoom_about(-2.0, 0.0, 0.0);
        assert_eq!(v.zoom, MAX_ZOOM);
    }

    #[test]
    fn pan_moves_the_art_with_the_finger() {
        let mut v = vp();
        let p = Point::new(10.0, 10.0);
        let s = v.to_screen(p);
        v.pan(30.0, -12.0);
        assert!(close(v.to_screen(p), s + Vec2::new(30.0, -12.0)));
    }

    #[test]
    fn rotate_keeps_the_pivot_still() {
        let mut v = vp();
        let before = v.to_doc(200.0, 100.0);
        v.rotate_about(90.0, 200.0, 100.0);
        assert!((v.rotation - 90.0).abs() < 1e-9);
        assert!(close(v.to_doc(200.0, 100.0), before));
        v.rotate_about(180.0, 0.0, 0.0);
        assert!((v.rotation - -90.0).abs() < 1e-9);
    }

    #[test]
    fn fit_shows_the_rect() {
        let mut v = vp();
        v.fit(Rect::new(0.0, 0.0, 612.0, 792.0), 0.0);
        // 600 px / 2 density = 300 pt high: 300 / 792.
        assert!((v.zoom - 300.0 / 792.0).abs() < 1e-9);
        assert!(close(v.center, Point::new(306.0, 396.0)));
        let before = v;
        v.fit(Rect::new(0.0, 0.0, 0.0, 10.0), 0.0);
        assert_eq!(v, before);
    }

    #[test]
    fn screen_frame_is_in_points() {
        let v = vp();
        let f = v.screen_frame();
        assert_eq!(f.size, (400.0, 300.0));
        assert!(close(f.at(200.0, 150.0), v.center));
    }

    #[test]
    fn resize_caps_sizes() {
        let mut v = Viewport::default();
        v.resize(0, 100_000, f64::INFINITY);
        assert_eq!((v.width, v.height, v.density), (1, MAX_SIDE, 1.0));
    }
}
