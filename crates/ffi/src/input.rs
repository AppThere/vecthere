//! Pen, touch and mouse input for native front ends, decided once in Rust so Android, ChromeOS and
//! iPadOS behave the same.
//!
//! The platform layer forwards every raw sample ([`PointerSample`]: Android `MotionEvent`s with
//! their historical samples, iOS `UITouch`es with their coalesced touches) and the [`Router`]
//! decides what each one is:
//!
//! - **Pens draw.** A stylus (S Pen, USI, Apple Pencil) or a mouse goes straight to the active tool,
//!   with its pressure. Its eraser end (USI and Wacom-style pens report `Eraser`) switches to the
//!   Eraser tool for the stroke; holding the barrel button (S Pen side button, USI barrel button)
//!   switches to the tool [`InputPrefs::stylus_button_tool`] names. Hovering pens move the tool's
//!   hover feedback.
//! - **Fingers navigate once a pen is seen** ([`TouchMode::Auto`]): a palm resting on the screen
//!   never draws, one finger pans, two pinch to zoom and pan (and rotate, when
//!   [`InputPrefs::rotate_gesture`] is on). Until a pen shows up (phones without one), one finger
//!   draws: its press waits until it moves or holds still for a moment, so a second finger landing
//!   right after makes a gesture instead of a stray mark.
//! - **Taps:** two fingers undo, three redo, as on the desktop app (#585).

use vectorcraft_tools::PointerKind;

/// What touched the screen.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ToolType {
    Finger,
    /// A pen's tip (S Pen, USI, Apple Pencil).
    Stylus,
    /// A pen's eraser end (USI and Wacom-style pens; Android `TOOL_TYPE_ERASER`).
    Eraser,
    Mouse,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PointerPhase {
    Down,
    Move,
    Up,
    /// The system took the pointer back (a system gesture, palm rejection by the platform).
    Cancel,
    /// A pen or mouse moving with nothing pressed (S Pen Air View, USI and Apple Pencil hover).
    Hover,
    /// It left hover range.
    HoverExit,
}

/// The barrel (side) button of a pen: S Pen's side button, USI's barrel button, Android
/// `BUTTON_STYLUS_PRIMARY`.
pub const BUTTON_STYLUS_PRIMARY: u32 = 1;
/// A pen's second barrel button (Android `BUTTON_STYLUS_SECONDARY`).
pub const BUTTON_STYLUS_SECONDARY: u32 = 2;
/// A mouse's secondary (right) button.
pub const BUTTON_MOUSE_SECONDARY: u32 = 4;

/// One raw input sample, in view pixels.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct PointerSample {
    /// Stable for the life of one contact (Android pointer id, iOS touch identity).
    pub pointer_id: u64,
    pub tool_type: ToolType,
    pub phase: PointerPhase,
    pub x: f64,
    pub y: f64,
    /// 0..1 (Android `getPressure`, iOS `force / maximumPossibleForce`); fingers ignore it.
    pub pressure: f32,
    /// Radians from perpendicular to the screen, 0..π/2 (Android `AXIS_TILT`; iOS π/2 −
    /// `altitudeAngle`).
    pub tilt: f32,
    /// Radians clockwise from the view's up direction (Android `AXIS_ORIENTATION`; iOS
    /// `azimuthAngle(in:)`).
    pub azimuth: f32,
    /// `BUTTON_*` flags held.
    pub buttons: u32,
    /// Milliseconds on a monotonic clock (Android `eventTime`, iOS `timestamp` × 1000).
    pub time_ms: u64,
}

/// What a finger does.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Enum)]
pub enum TouchMode {
    /// Draw until a pen is seen, then navigate (the default).
    #[default]
    Auto,
    /// Always draw with one finger.
    Draw,
    /// Never draw: fingers only pan, zoom and tap.
    Navigate,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct InputPrefs {
    pub touch_mode: TouchMode,
    /// The tool a pen draws with while its barrel button is held; empty: the button does nothing.
    pub stylus_button_tool: String,
    /// The tool for the second barrel button; empty: nothing.
    pub stylus_secondary_tool: String,
    /// The tool a pen's eraser end draws with; empty: the eraser end draws as the tip.
    pub eraser_tool: String,
    /// Two fingers turning turn the view.
    pub rotate_gesture: bool,
    /// Two-finger tap undoes, three-finger tap redoes.
    pub tap_undo: bool,
    /// How much larger hit tolerances are for a finger than for a pen (a fingertip covers ~7 mm).
    pub finger_tolerance: f64,
}

impl Default for InputPrefs {
    fn default() -> Self {
        Self {
            touch_mode: TouchMode::Auto,
            stylus_button_tool: "eraser".into(),
            stylus_secondary_tool: "hand".into(),
            eraser_tool: "eraser".into(),
            rotate_gesture: false,
            tap_undo: true,
            finger_tolerance: 2.0,
        }
    }
}

/// What the router made of a sample, for the engine to carry out in order.
#[derive(Clone, Debug, PartialEq)]
pub enum Routed {
    /// A pointer event for the active tool at view pixel `x`, `y`.
    Tool {
        kind: PointerKind,
        x: f64,
        y: f64,
        pressure: f32,
        finger: bool,
    },
    /// Abandon the tool's gesture in progress (a finger stroke that turned into a pinch, a palm).
    CancelTool,
    /// Switch to this tool for one stroke.
    PushTool(String),
    /// Back to the tool before [`Routed::PushTool`].
    PopTool,
    Pan {
        dx: f64,
        dy: f64,
    },
    Zoom {
        factor: f64,
        x: f64,
        y: f64,
    },
    Rotate {
        degrees: f64,
        x: f64,
        y: f64,
    },
    Command(&'static str),
}

/// How long the fingers of a tap may stay down.
const TAP_MS: u64 = 500;
/// How far a tap's finger may move, in screen points.
const TAP_SLOP: f64 = 16.0;
/// A one-finger stroke starts once its finger moves this far (screen points)…
const DRAW_SLOP: f64 = 6.0;
/// …or holds this long (a second finger landing sooner makes a gesture).
const DRAW_HOLD_MS: u64 = 90;
/// Two fingers must turn this far before the view starts turning, so pinches don't wobble it.
const ROTATE_START_DEG: f64 = 12.0;
/// The most contacts tracked at once (input is untrusted: ids are capped).
const MAX_FINGERS: usize = 10;

#[derive(Clone, Copy, Debug)]
struct Finger {
    id: u64,
    start: (f64, f64),
    pos: (f64, f64),
}

#[derive(Clone, Copy, Debug)]
enum DrawState {
    /// A finger is down; its press waits (see [`DRAW_SLOP`]).
    Pending {
        x: f64,
        y: f64,
        since: u64,
    },
    Active,
}

#[derive(Clone, Copy, Debug)]
struct Drawer {
    id: u64,
    finger: bool,
    state: DrawState,
    /// It switched tools for the stroke.
    pushed: bool,
}

#[derive(Clone, Copy, Debug)]
struct Pinch {
    centroid: (f64, f64),
    dist: f64,
    angle: f64,
    /// Turned so far before the view starts turning.
    turned: f64,
    rotating: bool,
}

#[derive(Clone, Copy, Debug, Default)]
struct Tap {
    most: usize,
    start: u64,
    spoilt: bool,
}

/// The input state machine. Feed it samples in order; it returns what to do.
#[derive(Clone, Debug, Default)]
pub struct Router {
    pub prefs: InputPrefs,
    /// A pen has been used (fingers then navigate in [`TouchMode::Auto`]).
    pub stylus_seen: bool,
    fingers: Vec<Finger>,
    drawer: Option<Drawer>,
    pinch: Option<Pinch>,
    tap: Tap,
}

fn dist(a: (f64, f64), b: (f64, f64)) -> f64 {
    (a.0 - b.0).hypot(a.1 - b.1)
}

impl Router {
    pub fn new(prefs: InputPrefs) -> Self {
        Self { prefs, ..Self::default() }
    }

    /// Fingers draw now.
    pub fn fingers_draw(&self) -> bool {
        match self.prefs.touch_mode {
            TouchMode::Draw => true,
            TouchMode::Navigate => false,
            TouchMode::Auto => !self.stylus_seen,
        }
    }

    /// A pointer is drawing (or about to).
    pub fn is_drawing(&self) -> bool {
        self.drawer.is_some()
    }

    /// Route sample `s`; `density` is pixels per screen point (slops are in points).
    pub fn feed(&mut self, s: &PointerSample, density: f64) -> Vec<Routed> {
        let d = if density.is_finite() && density > 0.0 { density } else { 1.0 };
        if !(s.x.is_finite() && s.y.is_finite()) {
            return vec![];
        }
        match s.tool_type {
            ToolType::Finger => self.finger(s, d),
            ToolType::Stylus | ToolType::Eraser | ToolType::Mouse => self.pen(s),
        }
    }

    /// The tool a pen sample should draw with, if not the active one.
    fn pen_tool(&self, s: &PointerSample) -> Option<String> {
        let pick = |t: &str| (!t.is_empty()).then(|| t.to_string());
        match s.tool_type {
            ToolType::Eraser => pick(&self.prefs.eraser_tool),
            ToolType::Stylus if s.buttons & BUTTON_STYLUS_PRIMARY != 0 => pick(&self.prefs.stylus_button_tool),
            ToolType::Stylus if s.buttons & BUTTON_STYLUS_SECONDARY != 0 => pick(&self.prefs.stylus_secondary_tool),
            _ => None,
        }
    }

    fn pen(&mut self, s: &PointerSample) -> Vec<Routed> {
        let mut out = vec![];
        let pressure = if s.tool_type == ToolType::Mouse {
            1.0
        } else if s.pressure.is_finite() {
            s.pressure.clamp(0.0, 1.0)
        } else {
            1.0
        };
        let tool = |kind| Routed::Tool { kind, x: s.x, y: s.y, pressure, finger: false };
        match s.phase {
            PointerPhase::Down => {
                if s.tool_type == ToolType::Mouse && s.buttons & BUTTON_MOUSE_SECONDARY != 0 {
                    return out;
                }
                if s.tool_type != ToolType::Mouse {
                    self.stylus_seen = true;
                }
                match self.drawer {
                    // Another pen or the mouse is already drawing.
                    Some(d) if !d.finger => return out,
                    // A finger (a palm, most likely) got there first: drop its stroke.
                    Some(d) => {
                        if matches!(d.state, DrawState::Active) {
                            out.push(Routed::CancelTool);
                        }
                        if d.pushed {
                            out.push(Routed::PopTool);
                        }
                        self.drawer = None;
                    }
                    None => {}
                }
                // A pen landing ends any pinch: the hand holding it is drawing now.
                self.pinch = None;
                self.tap.spoilt = true;
                let pushed = self.pen_tool(s).map(|t| out.push(Routed::PushTool(t))).is_some();
                self.drawer = Some(Drawer { id: s.pointer_id, finger: false, state: DrawState::Active, pushed });
                out.push(tool(PointerKind::Down));
            }
            PointerPhase::Move => match self.drawer {
                Some(d) if d.id == s.pointer_id && !d.finger => out.push(tool(PointerKind::Drag)),
                // A mouse moving with no button (some platforms report it as a move).
                None if s.tool_type == ToolType::Mouse => out.push(tool(PointerKind::Move)),
                _ => {}
            },
            PointerPhase::Up | PointerPhase::Cancel => {
                if let Some(d) = self.drawer.filter(|d| d.id == s.pointer_id && !d.finger) {
                    out.push(if s.phase == PointerPhase::Up { tool(PointerKind::Up) } else { Routed::CancelTool });
                    if d.pushed {
                        out.push(Routed::PopTool);
                    }
                    self.drawer = None;
                }
            }
            PointerPhase::Hover => {
                if s.tool_type != ToolType::Mouse {
                    self.stylus_seen = true;
                }
                if self.drawer.is_none() {
                    out.push(tool(PointerKind::Move));
                }
            }
            PointerPhase::HoverExit => {}
        }
        out
    }

    fn finger(&mut self, s: &PointerSample, d: f64) -> Vec<Routed> {
        let mut out = vec![];
        let pos = (s.x, s.y);
        match s.phase {
            PointerPhase::Down => {
                if self.fingers.iter().any(|f| f.id == s.pointer_id) || self.fingers.len() >= MAX_FINGERS {
                    return out;
                }
                // A pen is drawing: this is the hand resting on the screen.
                if self.drawer.is_some_and(|d| !d.finger) {
                    return out;
                }
                if self.fingers.is_empty() {
                    self.tap = Tap { most: 0, start: s.time_ms, spoilt: false };
                }
                self.fingers.push(Finger { id: s.pointer_id, start: pos, pos });
                self.tap.most = self.tap.most.max(self.fingers.len());
                if self.fingers.len() == 1 {
                    if self.fingers_draw() {
                        self.drawer = Some(Drawer {
                            id: s.pointer_id,
                            finger: true,
                            state: DrawState::Pending { x: s.x, y: s.y, since: s.time_ms },
                            pushed: false,
                        });
                    }
                } else {
                    // A second finger: whatever the first began is a gesture.
                    if let Some(dr) = self.drawer.take()
                        && matches!(dr.state, DrawState::Active)
                    {
                        out.push(Routed::CancelTool);
                    }
                    self.pinch = self.measure_pinch();
                }
            }
            PointerPhase::Move => {
                let Some(i) = self.fingers.iter().position(|f| f.id == s.pointer_id) else { return out };
                let before = self.fingers.get(i).map(|f| f.pos).unwrap_or(pos);
                if let Some(f) = self.fingers.get_mut(i) {
                    f.pos = pos;
                    if dist(f.start, pos) > TAP_SLOP * d {
                        self.tap.spoilt = true;
                    }
                }
                match self.drawer {
                    Some(dr) if dr.id == s.pointer_id => {
                        if let DrawState::Pending { x, y, since } = dr.state {
                            if dist((x, y), pos) > DRAW_SLOP * d || s.time_ms.saturating_sub(since) >= DRAW_HOLD_MS {
                                out.push(Routed::Tool { kind: PointerKind::Down, x, y, pressure: 1.0, finger: true });
                                out.push(Routed::Tool { kind: PointerKind::Drag, x: s.x, y: s.y, pressure: 1.0, finger: true });
                                self.drawer = Some(Drawer { state: DrawState::Active, ..dr });
                            }
                        } else {
                            out.push(Routed::Tool { kind: PointerKind::Drag, x: s.x, y: s.y, pressure: 1.0, finger: true });
                        }
                    }
                    Some(_) => {}
                    None if self.fingers.len() == 1 => out.push(Routed::Pan { dx: pos.0 - before.0, dy: pos.1 - before.1 }),
                    None => out.extend(self.update_pinch()),
                }
            }
            PointerPhase::Up | PointerPhase::Cancel => {
                let known = self.fingers.len();
                self.fingers.retain(|f| f.id != s.pointer_id);
                if self.fingers.len() == known {
                    return out;
                }
                if let Some(dr) = self.drawer.filter(|dr| dr.id == s.pointer_id) {
                    self.drawer = None;
                    if s.phase == PointerPhase::Cancel {
                        if matches!(dr.state, DrawState::Active) {
                            out.push(Routed::CancelTool);
                        }
                    } else {
                        if let DrawState::Pending { x, y, .. } = dr.state {
                            out.push(Routed::Tool { kind: PointerKind::Down, x, y, pressure: 1.0, finger: true });
                        }
                        out.push(Routed::Tool { kind: PointerKind::Up, x: s.x, y: s.y, pressure: 1.0, finger: true });
                    }
                    // A one-finger stroke or click is never a tap.
                    self.tap.spoilt = true;
                }
                self.tap.spoilt |= s.phase == PointerPhase::Cancel || s.time_ms.saturating_sub(self.tap.start) > TAP_MS;
                // The remaining fingers carry on from where they are.
                self.pinch = self.measure_pinch();
                if self.fingers.is_empty() {
                    if self.prefs.tap_undo && !self.tap.spoilt {
                        match self.tap.most {
                            2 => out.push(Routed::Command("edit.undo")),
                            3 => out.push(Routed::Command("edit.redo")),
                            _ => {}
                        }
                    }
                    self.tap = Tap::default();
                }
            }
            PointerPhase::Hover | PointerPhase::HoverExit => {}
        }
        out
    }

    /// The pinch the first two fingers make now (none with fewer than two).
    fn measure_pinch(&self) -> Option<Pinch> {
        let (a, b) = (self.fingers.first()?.pos, self.fingers.get(1)?.pos);
        Some(Pinch {
            centroid: ((a.0 + b.0) / 2.0, (a.1 + b.1) / 2.0),
            dist: dist(a, b),
            angle: (b.1 - a.1).atan2(b.0 - a.0).to_degrees(),
            turned: 0.0,
            rotating: false,
        })
    }

    fn update_pinch(&mut self) -> Vec<Routed> {
        let mut out = vec![];
        let (Some(old), Some(now)) = (self.pinch, self.measure_pinch()) else { return out };
        let (cx, cy) = now.centroid;
        out.push(Routed::Pan { dx: cx - old.centroid.0, dy: cy - old.centroid.1 });
        if old.dist > 1.0 && now.dist > 1.0 {
            let factor = now.dist / old.dist;
            if (factor - 1.0).abs() > 1e-9 {
                out.push(Routed::Zoom { factor, x: cx, y: cy });
            }
        }
        let delta = crate::viewport::normalize_degrees(now.angle - old.angle);
        let mut next = Pinch { turned: old.turned + delta, rotating: old.rotating, ..now };
        if self.prefs.rotate_gesture {
            if !next.rotating && next.turned.abs() >= ROTATE_START_DEG {
                next.rotating = true;
            }
            if next.rotating && delta.abs() > 1e-9 {
                out.push(Routed::Rotate { degrees: delta, x: cx, y: cy });
            }
        }
        self.pinch = Some(next);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(id: u64, tool_type: ToolType, phase: PointerPhase, x: f64, y: f64, t: u64) -> PointerSample {
        PointerSample { pointer_id: id, tool_type, phase, x, y, pressure: 0.5, tilt: 0.0, azimuth: 0.0, buttons: 0, time_ms: t }
    }
    use PointerPhase::*;
    use ToolType::*;

    fn kinds(r: &[Routed]) -> Vec<PointerKind> {
        r.iter()
            .filter_map(|x| match x {
                Routed::Tool { kind, .. } => Some(*kind),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn stylus_draws_with_pressure() {
        let mut r = Router::default();
        let down = r.feed(&s(1, Stylus, Down, 10.0, 10.0, 0), 1.0);
        assert_eq!(down, vec![Routed::Tool { kind: PointerKind::Down, x: 10.0, y: 10.0, pressure: 0.5, finger: false }]);
        assert_eq!(kinds(&r.feed(&s(1, Stylus, Move, 12.0, 10.0, 5), 1.0)), vec![PointerKind::Drag]);
        assert_eq!(kinds(&r.feed(&s(1, Stylus, Up, 12.0, 10.0, 9), 1.0)), vec![PointerKind::Up]);
        assert!(r.stylus_seen && !r.is_drawing());
    }

    #[test]
    fn bad_pressure_and_coordinates_are_tamed() {
        let mut r = Router::default();
        let mut x = s(1, Stylus, Down, 0.0, 0.0, 0);
        x.pressure = f32::NAN;
        assert!(matches!(r.feed(&x, 1.0).as_slice(), [Routed::Tool { pressure, .. }] if *pressure == 1.0));
        x.phase = Move;
        x.x = f64::INFINITY;
        assert!(r.feed(&x, 1.0).is_empty());
        x.x = 1.0;
        x.pressure = 7.0;
        assert!(matches!(r.feed(&x, 1.0).as_slice(), [Routed::Tool { pressure, .. }] if *pressure == 1.0));
    }

    #[test]
    fn eraser_end_switches_tool_for_the_stroke() {
        let mut r = Router::default();
        let down = r.feed(&s(1, Eraser, Down, 0.0, 0.0, 0), 1.0);
        assert_eq!(down.first(), Some(&Routed::PushTool("eraser".into())));
        let up = r.feed(&s(1, Eraser, Up, 0.0, 0.0, 1), 1.0);
        assert_eq!(up.last(), Some(&Routed::PopTool));
    }

    #[test]
    fn barrel_button_switches_to_the_chosen_tool() {
        let mut r = Router::default();
        r.prefs.stylus_button_tool = "eyedropper".into();
        let mut x = s(1, Stylus, Down, 0.0, 0.0, 0);
        x.buttons = BUTTON_STYLUS_PRIMARY;
        assert_eq!(r.feed(&x, 1.0).first(), Some(&Routed::PushTool("eyedropper".into())));
        x.phase = Up;
        assert_eq!(r.feed(&x, 1.0).last(), Some(&Routed::PopTool));
        // An empty setting leaves the button alone.
        r.prefs.stylus_button_tool.clear();
        x.phase = Down;
        assert_eq!(kinds(&r.feed(&x, 1.0)), vec![PointerKind::Down]);
    }

    #[test]
    fn palm_never_draws_once_a_pen_is_seen() {
        let mut r = Router::default();
        r.feed(&s(9, Stylus, Hover, 5.0, 5.0, 0), 1.0);
        assert!(!r.fingers_draw());
        assert!(r.feed(&s(1, Finger, Down, 100.0, 100.0, 10), 1.0).is_empty());
        let moved = r.feed(&s(1, Finger, Move, 110.0, 100.0, 20), 1.0);
        assert_eq!(moved, vec![Routed::Pan { dx: 10.0, dy: 0.0 }]);
    }

    #[test]
    fn hand_resting_while_the_pen_draws_is_ignored() {
        let mut r = Router::default();
        r.feed(&s(1, Stylus, Down, 0.0, 0.0, 0), 1.0);
        assert!(r.feed(&s(2, Finger, Down, 300.0, 300.0, 5), 1.0).is_empty());
        assert!(r.feed(&s(2, Finger, Move, 340.0, 300.0, 6), 1.0).is_empty());
        assert_eq!(kinds(&r.feed(&s(1, Stylus, Move, 3.0, 0.0, 7), 1.0)), vec![PointerKind::Drag]);
    }

    #[test]
    fn a_pen_landing_cancels_a_palm_stroke() {
        let mut r = Router::default();
        r.feed(&s(1, Finger, Down, 0.0, 0.0, 0), 1.0);
        r.feed(&s(1, Finger, Move, 50.0, 0.0, 10), 1.0);
        let down = r.feed(&s(2, Stylus, Down, 5.0, 5.0, 20), 1.0);
        assert_eq!(down.first(), Some(&Routed::CancelTool));
        assert_eq!(kinds(&down), vec![PointerKind::Down]);
        // The finger's later events do nothing.
        assert!(r.feed(&s(1, Finger, Up, 50.0, 0.0, 30), 1.0).is_empty());
    }

    #[test]
    fn finger_press_waits_for_movement() {
        let mut r = Router::default();
        assert!(r.feed(&s(1, Finger, Down, 0.0, 0.0, 0), 1.0).is_empty());
        assert!(r.feed(&s(1, Finger, Move, 2.0, 0.0, 10), 1.0).is_empty());
        let started = r.feed(&s(1, Finger, Move, 20.0, 0.0, 20), 1.0);
        assert_eq!(kinds(&started), vec![PointerKind::Down, PointerKind::Drag]);
        assert!(matches!(started.first(), Some(Routed::Tool { x, finger: true, .. }) if *x == 0.0));
        assert_eq!(kinds(&r.feed(&s(1, Finger, Up, 20.0, 0.0, 30), 1.0)), vec![PointerKind::Up]);
    }

    #[test]
    fn finger_tap_is_a_click() {
        let mut r = Router::default();
        r.feed(&s(1, Finger, Down, 4.0, 4.0, 0), 1.0);
        assert_eq!(kinds(&r.feed(&s(1, Finger, Up, 4.0, 4.0, 40), 1.0)), vec![PointerKind::Down, PointerKind::Up]);
    }

    #[test]
    fn second_finger_turns_a_pending_stroke_into_a_pinch() {
        let mut r = Router::default();
        r.feed(&s(1, Finger, Down, 100.0, 100.0, 0), 1.0);
        assert!(r.feed(&s(2, Finger, Down, 200.0, 100.0, 30), 1.0).is_empty());
        // Spread apart: zoom in about the centre, no tool events.
        let out = r.feed(&s(2, Finger, Move, 300.0, 100.0, 60), 1.0);
        assert!(kinds(&out).is_empty());
        assert!(out.iter().any(|x| matches!(x, Routed::Zoom { factor, .. } if (*factor - 2.0).abs() < 1e-9)));
    }

    #[test]
    fn second_finger_cancels_an_active_stroke() {
        let mut r = Router::default();
        r.feed(&s(1, Finger, Down, 100.0, 100.0, 0), 1.0);
        r.feed(&s(1, Finger, Move, 140.0, 100.0, 20), 1.0);
        assert_eq!(r.feed(&s(2, Finger, Down, 200.0, 200.0, 200), 1.0), vec![Routed::CancelTool]);
    }

    #[test]
    fn two_finger_tap_undoes_three_redo() {
        let mut r = Router::default();
        r.feed(&s(1, Finger, Down, 100.0, 100.0, 0), 1.0);
        r.feed(&s(2, Finger, Down, 200.0, 100.0, 10), 1.0);
        assert!(r.feed(&s(1, Finger, Up, 100.0, 100.0, 100), 1.0).is_empty());
        assert_eq!(r.feed(&s(2, Finger, Up, 200.0, 100.0, 120), 1.0), vec![Routed::Command("edit.undo")]);
        for (i, t) in [(1, 0), (2, 5), (3, 10)] {
            r.feed(&s(i, Finger, Down, 100.0 * i as f64, 100.0, t), 1.0);
        }
        r.feed(&s(1, Finger, Up, 100.0, 100.0, 100), 1.0);
        r.feed(&s(2, Finger, Up, 200.0, 100.0, 100), 1.0);
        assert_eq!(r.feed(&s(3, Finger, Up, 300.0, 100.0, 100), 1.0), vec![Routed::Command("edit.redo")]);
    }

    #[test]
    fn slow_or_moving_taps_do_nothing() {
        let mut r = Router::default();
        r.feed(&s(1, Finger, Down, 100.0, 100.0, 0), 1.0);
        r.feed(&s(2, Finger, Down, 200.0, 100.0, 10), 1.0);
        r.feed(&s(1, Finger, Up, 100.0, 100.0, 600), 1.0);
        assert!(r.feed(&s(2, Finger, Up, 200.0, 100.0, 610), 1.0).is_empty());
        r.feed(&s(1, Finger, Down, 100.0, 100.0, 1000), 1.0);
        r.feed(&s(2, Finger, Down, 200.0, 100.0, 1010), 1.0);
        r.feed(&s(2, Finger, Move, 260.0, 100.0, 1020), 1.0);
        r.feed(&s(1, Finger, Up, 100.0, 100.0, 1100), 1.0);
        assert!(r.feed(&s(2, Finger, Up, 260.0, 100.0, 1110), 1.0).is_empty());
    }

    #[test]
    fn rotation_waits_for_a_clear_turn() {
        let mut r = Router::default();
        r.prefs.rotate_gesture = true;
        r.prefs.touch_mode = TouchMode::Navigate;
        r.feed(&s(1, Finger, Down, 0.0, 0.0, 0), 1.0);
        r.feed(&s(2, Finger, Down, 100.0, 0.0, 0), 1.0);
        // ~5.7°: not yet.
        let small = r.feed(&s(2, Finger, Move, 100.0, 10.0, 10), 1.0);
        assert!(!small.iter().any(|x| matches!(x, Routed::Rotate { .. })));
        // Past the threshold: turning.
        let big = r.feed(&s(2, Finger, Move, 100.0, 40.0, 20), 1.0);
        assert!(big.iter().any(|x| matches!(x, Routed::Rotate { degrees, .. } if *degrees > 0.0)));
        // Off: pinches never rotate.
        let mut r = Router::default();
        r.prefs.touch_mode = TouchMode::Navigate;
        r.feed(&s(1, Finger, Down, 0.0, 0.0, 0), 1.0);
        r.feed(&s(2, Finger, Down, 100.0, 0.0, 0), 1.0);
        assert!(!r.feed(&s(2, Finger, Move, 0.0, 100.0, 10), 1.0).iter().any(|x| matches!(x, Routed::Rotate { .. })));
    }

    #[test]
    fn mouse_hover_and_right_button() {
        let mut r = Router::default();
        assert_eq!(kinds(&r.feed(&s(1, Mouse, Hover, 1.0, 1.0, 0), 1.0)), vec![PointerKind::Move]);
        assert!(!r.stylus_seen, "a mouse is not a pen: fingers still draw");
        let mut right = s(1, Mouse, Down, 1.0, 1.0, 0);
        right.buttons = BUTTON_MOUSE_SECONDARY;
        assert!(r.feed(&right, 1.0).is_empty());
    }

    #[test]
    fn cancel_abandons_the_stroke() {
        let mut r = Router::default();
        r.feed(&s(1, Eraser, Down, 0.0, 0.0, 0), 1.0);
        assert_eq!(r.feed(&s(1, Eraser, Cancel, 0.0, 0.0, 1), 1.0), vec![Routed::CancelTool, Routed::PopTool]);
        assert!(!r.is_drawing());
    }

    #[test]
    fn unknown_and_excess_pointers_are_ignored() {
        let mut r = Router::default();
        r.prefs.touch_mode = TouchMode::Navigate;
        assert!(r.feed(&s(42, Finger, Up, 0.0, 0.0, 0), 1.0).is_empty());
        assert!(r.feed(&s(42, Finger, Move, 0.0, 0.0, 0), 1.0).is_empty());
        for i in 0..20 {
            r.feed(&s(i, Finger, Down, i as f64, 0.0, 0), 1.0);
        }
        assert_eq!(r.fingers.len(), MAX_FINGERS);
    }
}
