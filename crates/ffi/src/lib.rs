//! VectorCraft's engine for native front ends: Kotlin Multiplatform apps on Android, ChromeOS and
//! iPadOS (and the JVM), through [UniFFI](https://mozilla.github.io/uniffi-rs/) bindings generated
//! by [Gobley](https://github.com/gobley/gobley). See `docs/mobile.md`.
//!
//! One [`VectorEngine`] holds a whole session (open documents, history, the active tool), the
//! canvas [`Viewport`](viewport::Viewport) and the pen and touch [`Router`](input::Router). The
//! front end stays thin, as the egui one is: it forwards raw input, draws the frame and the
//! overlays it gets back, and acts through [`VectorEngine::execute`], which reaches every command
//! in the registry (the same ids the desktop menus, MCP and the control channel use).
//!
//! Never crashes: every entry point catches a panic ([`vectorcraft_engine::guard`]), rolls nothing
//! half-done into the document (the session's own guards do that) and reports a
//! [`VectorError::Internal`] instead; Kotlin sees an exception, never an abort.

use std::sync::{Mutex, MutexGuard};

use kurbo::Rect;
use serde_json::{Value, json};
use vectorcraft_engine::{Session, ViewInfo, guard};
use vectorcraft_tools::{Mods, PointerEvent, PointerKind, ToolKey};

pub mod input;
pub mod overlay;
pub mod viewport;

use input::{InputPrefs, PointerSample, Routed, Router};
use overlay::OverlayShape;
use viewport::Viewport;

uniffi::setup_scaffolding!();

/// The pasteboard behind the artboards when the front end sets none (premultiplied RGBA).
const PASTEBOARD: [u8; 4] = [0xe8, 0xe8, 0xe8, 0xff];
/// The most samples one [`VectorEngine::pointer`] call takes (a frame's batch is far fewer).
const MAX_BATCH: usize = 4096;

/// Kotlin sees `VectorException.Engine` etc., with this `Display` text as the message.
#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum VectorError {
    /// A command or file operation failed; `message` is for the user.
    Engine { message: String },
    /// Bad arguments from the front end.
    Invalid { message: String },
    /// A bug: the engine caught a panic. The document is as it was before the call.
    Internal { message: String },
}

impl std::fmt::Display for VectorError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Engine { message } | Self::Invalid { message } => f.write_str(message),
            Self::Internal { message } => write!(f, "internal error: {message}"),
        }
    }
}

impl std::error::Error for VectorError {}

impl From<vectorcraft_engine::EngineError> for VectorError {
    fn from(e: vectorcraft_engine::EngineError) -> Self {
        Self::Engine { message: e.to_string() }
    }
}

fn invalid(message: impl Into<String>) -> VectorError {
    VectorError::Invalid { message: message.into() }
}

type Result<T> = std::result::Result<T, VectorError>;

/// Modifier keys from a hardware keyboard (Chromebooks, iPad Magic Keyboard, Samsung Book Cover).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct Modifiers {
    pub shift: bool,
    /// Alt; Option on Apple keyboards.
    pub alt: bool,
    /// Command on Apple keyboards, Ctrl elsewhere.
    pub cmd: bool,
    pub ctrl: bool,
}

/// Keys the tools act on.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum Key {
    Enter,
    Escape,
    Backspace,
    Delete,
    Up,
    Down,
    Left,
    Right,
    BracketLeft,
    BracketRight,
    Tab,
    Home,
    End,
    Digit { value: u8 },
}

impl Key {
    fn tool_key(self) -> ToolKey {
        match self {
            Key::Enter => ToolKey::Enter,
            Key::Escape => ToolKey::Escape,
            Key::Backspace => ToolKey::Backspace,
            Key::Delete => ToolKey::Delete,
            Key::Up => ToolKey::Up,
            Key::Down => ToolKey::Down,
            Key::Left => ToolKey::Left,
            Key::Right => ToolKey::Right,
            Key::BracketLeft => ToolKey::BracketLeft,
            Key::BracketRight => ToolKey::BracketRight,
            Key::Tab => ToolKey::Tab,
            Key::Home => ToolKey::Home,
            Key::End => ToolKey::End,
            Key::Digit { value } => ToolKey::Digit(value.min(9)),
        }
    }
}

/// A rendered canvas frame: premultiplied RGBA8, row-major, `width` × `height` pixels (what
/// Android's `Bitmap.Config.ARGB_8888` and Skia's `kRGBA_8888 / kPremul` hold).
#[derive(Clone, Debug, uniffi::Record)]
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
    /// The document revision drawn: an unchanged revision and view need no new frame.
    pub revision: u64,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ViewState {
    pub center_x: f64,
    pub center_y: f64,
    pub zoom: f64,
    pub rotation: f64,
}

/// View options the tools and the renderer follow (the View menu).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ViewOptions {
    pub outline: bool,
    pub smart_guides: bool,
    pub guides: bool,
    pub snap_to_grid: bool,
    pub snap_to_point: bool,
    pub snap_to_pixel: bool,
    pub bounding_box: bool,
    pub corner_widgets: bool,
}

impl Default for ViewOptions {
    fn default() -> Self {
        let v = ViewInfo::default();
        Self {
            outline: v.outline,
            smart_guides: v.smart_guides,
            guides: v.guides,
            snap_to_grid: v.snap_to_grid,
            snap_to_point: v.snap_to_point,
            snap_to_pixel: v.snap_to_pixel,
            bounding_box: v.show_bbox,
            corner_widgets: v.corner_widgets,
        }
    }
}

/// What the front end shows about the active document.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DocumentState {
    pub has_document: bool,
    pub title: String,
    pub dirty: bool,
    pub revision: u64,
    pub can_undo: bool,
    pub can_redo: bool,
    pub undo_label: Option<String>,
    pub redo_label: Option<String>,
    pub selection_count: u32,
    pub tool: String,
    pub document_count: u32,
    pub active_index: u32,
    pub artboard_count: u32,
}

/// Something only the front end can do, asked for by a tool or a command.
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum UiRequest {
    /// Open dialog `id` with its parameters (JSON).
    Dialog { id: String, params: String },
    /// The tool changed itself (the Type tool after placing text, a temporary tool).
    ToolChanged { tool: String },
    /// A message for the status line.
    Status { message: String },
}

/// What a batch of input did.
#[derive(Clone, Debug, Default, PartialEq, uniffi::Record)]
pub struct InputResult {
    /// The document changed: draw a new frame.
    pub document_changed: bool,
    /// The view moved (pan, zoom, rotate): draw a new frame.
    pub view_changed: bool,
    pub requests: Vec<UiRequest>,
    /// Commands run by gestures (two-finger tap: `edit.undo`).
    pub commands: Vec<String>,
    /// The first error a command or tool reported (the input still went through).
    pub error: Option<String>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct CommandInfo {
    pub id: String,
    pub label: String,
    /// Menu path, top level first (`["Object", "Path"]`); empty for commands not in a menu.
    pub menu: Vec<String>,
    /// Desktop shortcut (`Cmd+Shift+Z`); Cmd is Ctrl off Apple keyboards.
    pub shortcut: Option<String>,
    pub params: String,
    pub enabled: bool,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ToolInfo {
    pub id: String,
    pub label: String,
    pub shortcut: Option<String>,
    /// The name of its icon in VectorCraft's own icon set (`assets/icons`).
    pub icon: String,
}

/// Tools that share a toolbar slot (the Pen with Add/Delete Anchor Point…).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ToolGroup {
    pub tools: Vec<ToolInfo>,
}

/// One row of the Layers panel.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct LayerRow {
    /// The object's id: what `layer.setProps`, `layer.setCurrent` and `select.set` take.
    pub id: u64,
    pub name: String,
    /// 0 for a top-level layer.
    pub depth: u32,
    pub is_layer: bool,
    pub visible: bool,
    pub locked: bool,
    pub selected: bool,
    pub child_count: u32,
    /// The layer's colour, `0xAARRGGBB`.
    pub color: u32,
}

/// The most Layers panel rows sent at once.
const MAX_ROWS: usize = 5000;

struct Inner {
    session: Session,
    renderer: vectorcraft_render::Renderer,
    viewport: Viewport,
    router: Router,
    mods: Mods,
    view: ViewOptions,
    pasteboard: [u8; 4],
    /// The tool to go back to after a stroke with a temporary one (eraser end, barrel button).
    pushed: Option<String>,
}

impl Inner {
    fn view_info(&self, finger: bool) -> ViewInfo {
        let v = &self.view;
        // Tools measure their hit tolerances in screen points (`tol(px) = px / zoom`): a finger
        // gets them larger by telling the tools the art is smaller on screen than it is.
        let scale = if finger && self.router.prefs.finger_tolerance.is_finite() { self.router.prefs.finger_tolerance.clamp(1.0, 4.0) } else { 1.0 };
        ViewInfo {
            zoom: self.viewport.zoom / scale,
            outline: v.outline,
            smart_guides: v.smart_guides,
            guides: v.guides,
            snap_to_grid: v.snap_to_grid,
            show_bbox: v.bounding_box,
            snap_to_pixel: v.snap_to_pixel,
            snap_to_point: v.snap_to_point,
            corner_widgets: v.corner_widgets,
            screen: Some(self.viewport.screen_frame()),
        }
    }

    fn revision(&self) -> u64 {
        self.session.active().map_or(0, |d| d.revision ^ d.uid.rotate_left(32))
    }

    fn fit(&mut self, all: bool) {
        let Some(st) = self.session.active() else { return };
        let doc = &st.doc;
        let r = if all {
            doc.artboards.iter().map(|a| a.rect).chain(doc.art_bounds()).reduce(|a: Rect, b| a.union(b))
        } else {
            doc.artboards.first().map(|a| a.rect).or_else(|| doc.art_bounds())
        };
        if let Some(r) = r {
            self.viewport.fit(r, 24.0);
        }
    }

    fn requests(&mut self, reqs: Vec<vectorcraft_engine::UiRequest>, out: &mut InputResult) {
        for r in reqs {
            out.requests.push(match r {
                vectorcraft_engine::UiRequest::Dialog(id, params) => UiRequest::Dialog { id, params: params.to_string() },
                vectorcraft_engine::UiRequest::SwitchTool(id) => {
                    let view = self.view_info(false);
                    if let Err(e) = self.session.switch_tool(&id, view) {
                        out.error.get_or_insert(e.to_string());
                    }
                    UiRequest::ToolChanged { tool: self.session.tool_id().to_string() }
                }
                vectorcraft_engine::UiRequest::Status(message) => UiRequest::Status { message },
            });
        }
    }

    fn apply(&mut self, r: Routed, out: &mut InputResult) {
        let before = self.revision();
        match r {
            Routed::Tool { kind, x, y, pressure, finger } => {
                let pos = self.viewport.to_doc(x, y);
                let ev = PointerEvent { kind, pos, mods: self.mods, pressure };
                let view = self.view_info(finger);
                match self.session.pointer(&ev, view) {
                    Ok(reqs) => self.requests(reqs, out),
                    Err(e) => {
                        out.error.get_or_insert(e.to_string());
                    }
                }
                // Hover moves only change what the tool highlights.
                if kind != PointerKind::Move {
                    out.view_changed = true;
                }
            }
            Routed::CancelTool => {
                if self.session.in_interaction()
                    && let Err(e) = self.session.cancel_interaction()
                {
                    out.error.get_or_insert(e.to_string());
                }
                let view = self.view_info(false);
                match self.session.tool_key(ToolKey::Escape, Mods::default(), view) {
                    Ok(reqs) => self.requests(reqs, out),
                    Err(e) => {
                        out.error.get_or_insert(e.to_string());
                    }
                }
                out.view_changed = true;
            }
            Routed::PushTool(id) => {
                let current = self.session.tool_id().to_string();
                if current != id {
                    let view = self.view_info(false);
                    match self.session.switch_tool(&id, view) {
                        Ok(()) => self.pushed = Some(current),
                        Err(e) => {
                            out.error.get_or_insert(e.to_string());
                        }
                    }
                }
            }
            Routed::PopTool => {
                if let Some(id) = self.pushed.take() {
                    let view = self.view_info(false);
                    if let Err(e) = self.session.switch_tool(&id, view) {
                        out.error.get_or_insert(e.to_string());
                    }
                }
            }
            Routed::Pan { dx, dy } => {
                self.viewport.pan(dx, dy);
                out.view_changed = true;
            }
            Routed::Zoom { factor, x, y } => {
                self.viewport.zoom_about(factor, x, y);
                out.view_changed = true;
            }
            Routed::Rotate { degrees, x, y } => {
                self.viewport.rotate_about(degrees, x, y);
                out.view_changed = true;
            }
            Routed::Command(id) => {
                if let Err(e) = self.session.execute(id, &json!({})) {
                    out.error.get_or_insert(e.to_string());
                }
                out.commands.push(id.to_string());
            }
        }
        if self.revision() != before {
            out.document_changed = true;
        }
    }

    fn render(&mut self) -> Frame {
        let vp = self.viewport;
        let revision = self.revision();
        let Some(st) = self.session.active() else {
            let px = self.pasteboard;
            let n = (vp.width as usize).saturating_mul(vp.height as usize);
            return Frame { width: vp.width, height: vp.height, pixels: px.repeat(n), revision };
        };
        let opts = vectorcraft_render::RenderOptions {
            outline: self.view.outline,
            background: Some(self.pasteboard),
            artboards: true,
            highlight_substitutions: true,
            progressive_placed: true,
            trace_views: true,
            isolated: st.isolation,
            ..Default::default()
        };
        let doc = st.doc.clone();
        let img = self.renderer.render(&doc, vp.width, vp.height, vp.to_screen_affine(), &opts);
        Frame { width: img.width, height: img.height, pixels: img.pixels, revision }
    }
}

/// A whole VectorCraft session for a native front end. Thread-safe: calls serialize on one lock,
/// so render on a background thread and send input from the UI thread freely.
#[derive(uniffi::Object)]
pub struct VectorEngine {
    inner: Mutex<Inner>,
}

impl VectorEngine {
    fn lock(&self) -> MutexGuard<'_, Inner> {
        // Every entry point catches panics inside the lock, so it is never poisoned by one; if it
        // ever were, the session is still whole (the engine's own guards roll back).
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Run `f` on the session, turning a panic into [`VectorError::Internal`].
    fn with<T>(&self, f: impl FnOnce(&mut Inner) -> Result<T>) -> Result<T> {
        let mut g = self.lock();
        guard::catch_panic(|| f(&mut g)).unwrap_or_else(|message| Err(VectorError::Internal { message }))
    }
}

impl Default for VectorEngine {
    fn default() -> Self {
        Self::new()
    }
}

#[uniffi::export]
impl VectorEngine {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Inner {
                session: Session::new(),
                renderer: vectorcraft_render::Renderer::new(),
                viewport: Viewport::default(),
                router: Router::default(),
                mods: Mods::default(),
                view: ViewOptions::default(),
                pasteboard: PASTEBOARD,
                pushed: None,
            }),
        }
    }

    /// Run command `command` with `params` (a JSON object; empty means `{}`) and return its result
    /// as JSON. Every command in the registry: see [`Self::commands`] for ids and parameters.
    pub fn execute(&self, command: String, params: String) -> Result<String> {
        let params: Value = if params.trim().is_empty() {
            json!({})
        } else {
            serde_json::from_str(&params).map_err(|e| invalid(format!("params are not JSON: {e}")))?
        };
        self.with(|i| {
            let had = i.session.active().map(|d| d.uid);
            let out = i.session.execute(&command, &params)?;
            // A document opened, created or switched to: show it whole.
            if i.session.active().map(|d| d.uid) != had {
                i.fit(false);
            }
            Ok(out.to_string())
        })
    }

    /// Every command, with whether it can run now (menus grey out the others).
    pub fn commands(&self) -> Vec<CommandInfo> {
        self.with(|i| {
            Ok(i.session
                .commands()
                .into_iter()
                .map(|c| CommandInfo {
                    id: c.id.into(),
                    label: c.label.into(),
                    menu: c.menu.into_iter().map(Into::into).collect(),
                    shortcut: c.shortcut.map(Into::into),
                    params: c.params.into(),
                    enabled: c.enabled,
                })
                .collect())
        })
        .unwrap_or_default()
    }

    /// The toolbar: tool groups in the desktop Tools panel's order.
    pub fn tools(&self) -> Vec<ToolGroup> {
        vectorcraft_tools::TOOL_GROUPS
            .iter()
            .map(|g| ToolGroup {
                tools: g
                    .iter()
                    .map(|t| ToolInfo { id: t.id.into(), label: t.label.into(), shortcut: t.shortcut.map(Into::into), icon: t.icon.into() })
                    .collect(),
            })
            .collect()
    }

    pub fn active_tool(&self) -> String {
        self.lock().session.tool_id().to_string()
    }

    pub fn select_tool(&self, id: String) -> Result<()> {
        if vectorcraft_tools::tool_info(&id).is_none() {
            return Err(invalid(format!("no tool `{id}`")));
        }
        self.with(|i| {
            i.pushed = None;
            let view = i.view_info(false);
            Ok(i.session.select_tool(&id, view)?)
        })
    }

    /// The active tool's options (JSON), as the Control panel and tool dialogs show them.
    pub fn tool_options(&self) -> String {
        self.lock().session.tool_options().to_string()
    }

    /// Set one option of the active tool (`value` is JSON).
    pub fn set_tool_option(&self, key: String, value: String) -> Result<()> {
        let v: Value = serde_json::from_str(&value).map_err(|e| invalid(format!("value is not JSON: {e}")))?;
        self.with(|i| {
            i.session.set_tool_option(&key, &v);
            Ok(())
        })
    }

    /// A new document `width` × `height` points (File › New).
    pub fn new_document(&self, width: f64, height: f64) -> Result<()> {
        if !(width.is_finite() && height.is_finite() && width > 0.0 && height > 0.0) {
            return Err(invalid("the size must be positive"));
        }
        self.execute("file.new".into(), json!({"width": width, "height": height}).to_string()).map(drop)
    }

    /// Open a file's bytes as a new document (a file the system picker or a share sheet handed
    /// over: Android content URIs and iOS security-scoped URLs aren't paths). `name` gives the
    /// format by its extension. Returns `document.open`'s result (JSON: title, warnings…).
    pub fn open_bytes(&self, name: String, data: Vec<u8>) -> Result<String> {
        let b64 = vectorcraft_format::base64_encode(&data);
        self.execute("document.open".into(), json!({"name": name, "dataBase64": b64}).to_string())
    }

    /// Open the file at `path` (app storage).
    pub fn open_path(&self, path: String) -> Result<String> {
        self.execute("document.open".into(), json!({"path": path}).to_string())
    }

    /// The active document as a file in `format` (`vectorcraft`, `svg`, `pdf`, `png`… see the
    /// `document.serialize` command), for the front end to write where the user chose.
    pub fn save_bytes(&self, format: String) -> Result<Vec<u8>> {
        let out: Value = serde_json::from_str(&self.execute("document.serialize".into(), json!({"format": format}).to_string())?)
            .map_err(|e| VectorError::Internal { message: e.to_string() })?;
        if let Some(b64) = out.get("dataBase64").and_then(Value::as_str) {
            return vectorcraft_format::base64_decode(b64)
                .ok_or_else(|| VectorError::Internal { message: "document.serialize returned bad base64".into() });
        }
        out.get("text")
            .and_then(Value::as_str)
            .map(|t| t.as_bytes().to_vec())
            .ok_or_else(|| VectorError::Internal { message: "document.serialize returned no data".into() })
    }

    /// Mark the active document saved (after the front end wrote [`Self::save_bytes`] to where it
    /// belongs).
    pub fn mark_saved(&self) -> Result<()> {
        self.with(|i| {
            i.session.doc_mut()?.mark_saved();
            Ok(())
        })
    }

    /// Save the active document to `path` (app storage) in the format its extension names.
    pub fn save_path(&self, path: String) -> Result<String> {
        self.execute("document.save".into(), json!({"path": path}).to_string())
    }

    pub fn document_state(&self) -> DocumentState {
        let i = self.lock();
        let s = &i.session;
        let tool = s.tool_id().to_string();
        let document_count = u32::try_from(s.documents().len()).unwrap_or(u32::MAX);
        let active_index = s.active_index().and_then(|n| u32::try_from(n).ok()).unwrap_or(0);
        match s.active() {
            Some(d) => DocumentState {
                has_document: true,
                title: d.title(),
                dirty: d.is_dirty(),
                revision: i.revision(),
                can_undo: !d.history.undo.is_empty(),
                can_redo: !d.history.redo.is_empty(),
                undo_label: d.history.undo.last().map(|e| e.label.clone()),
                redo_label: d.history.redo.last().map(|e| e.label.clone()),
                selection_count: u32::try_from(d.selection.len()).unwrap_or(u32::MAX),
                tool,
                document_count,
                active_index,
                artboard_count: u32::try_from(d.doc.artboards.len()).unwrap_or(u32::MAX),
            },
            None => DocumentState {
                has_document: false,
                title: String::new(),
                dirty: false,
                revision: 0,
                can_undo: false,
                can_redo: false,
                undo_label: None,
                redo_label: None,
                selection_count: 0,
                tool,
                document_count,
                active_index,
                artboard_count: 0,
            },
        }
    }

    /// Titles of the open documents (tabs).
    pub fn document_titles(&self) -> Vec<String> {
        self.lock().session.documents().iter().map(vectorcraft_engine::DocState::title).collect()
    }

    pub fn set_active_document(&self, index: u32) -> Result<()> {
        self.with(|i| {
            if !i.session.set_active(index as usize) {
                return Err(invalid(format!("no document {index}")));
            }
            i.fit(false);
            Ok(())
        })
    }

    // ---------- view ----------

    /// The canvas is `width` × `height` pixels at `density` pixels per point.
    pub fn resize(&self, width: u32, height: u32, density: f64) {
        let mut i = self.lock();
        let first = i.viewport.width <= 1;
        i.viewport.resize(width, height, density);
        if first {
            i.fit(false);
        }
    }

    pub fn view_state(&self) -> ViewState {
        let v = self.lock().viewport;
        ViewState { center_x: v.center.x, center_y: v.center.y, zoom: v.zoom, rotation: v.rotation }
    }

    pub fn set_view_state(&self, s: ViewState) {
        let mut i = self.lock();
        let v = &mut i.viewport;
        if s.center_x.is_finite() && s.center_y.is_finite() {
            v.center = kurbo::Point::new(s.center_x, s.center_y);
        }
        if s.zoom.is_finite() {
            v.zoom = s.zoom.clamp(viewport::MIN_ZOOM, viewport::MAX_ZOOM);
        }
        v.rotation = viewport::normalize_degrees(s.rotation);
        v.pan(0.0, 0.0);
    }

    pub fn view_options(&self) -> ViewOptions {
        self.lock().view
    }

    pub fn set_view_options(&self, o: ViewOptions) {
        self.lock().view = o;
    }

    /// Pan by `dx`, `dy` pixels (scroll bars, a trackpad's two-finger scroll).
    pub fn pan(&self, dx: f64, dy: f64) {
        self.lock().viewport.pan(dx, dy);
    }

    /// Zoom by `factor` about pixel `x`, `y` (a trackpad pinch, Ctrl+scroll, the zoom buttons).
    pub fn zoom_by(&self, factor: f64, x: f64, y: f64) {
        self.lock().viewport.zoom_about(factor, x, y);
    }

    pub fn rotate_by(&self, degrees: f64, x: f64, y: f64) {
        self.lock().viewport.rotate_about(degrees, x, y);
    }

    /// The first artboard whole (View › Fit Artboard in Window).
    pub fn fit_artboard(&self) {
        self.lock().fit(false);
    }

    /// Every artboard and the art (View › Fit All in Window).
    pub fn fit_all(&self) {
        self.lock().fit(true);
    }

    /// The pasteboard colour as `0xAARRGGBB` (the theme's canvas background).
    pub fn set_pasteboard(&self, argb: u32) {
        let [a, r, g, b] = argb.to_be_bytes();
        // Premultiplied.
        let pm = |c: u8| ((u16::from(c) * u16::from(a) + 127) / 255) as u8;
        self.lock().pasteboard = [pm(r), pm(g), pm(b), a];
    }

    // ---------- input ----------

    pub fn input_prefs(&self) -> InputPrefs {
        self.lock().router.prefs.clone()
    }

    pub fn set_input_prefs(&self, prefs: InputPrefs) {
        self.lock().router.prefs = prefs;
    }

    /// A pen has been used this session (fingers navigate instead of drawing in the Auto touch
    /// mode). The front end can show it ("Pen detected: fingers pan and zoom").
    pub fn stylus_seen(&self) -> bool {
        self.lock().router.stylus_seen
    }

    pub fn set_modifiers(&self, mods: Modifiers) {
        self.lock().mods = Mods { shift: mods.shift, alt: mods.alt, cmd: mods.cmd, ctrl: mods.ctrl, space: false };
    }

    /// Raw pointer samples in order (one event's historical samples, then the event itself).
    pub fn pointer(&self, samples: Vec<PointerSample>) -> InputResult {
        let mut out = InputResult::default();
        let r = self.with(|i| {
            let density = i.viewport.density;
            for s in samples.iter().take(MAX_BATCH) {
                for routed in i.router.feed(s, density) {
                    i.apply(routed, &mut out);
                }
            }
            Ok(())
        });
        if let Err(e) = r {
            out.error = Some(e.to_string());
        }
        out
    }

    /// A key for the active tool (hardware keyboards). Returns what it did.
    pub fn key(&self, key: Key) -> InputResult {
        let mut out = InputResult::default();
        let r = self.with(|i| {
            let before = i.revision();
            let view = i.view_info(false);
            let mods = i.mods;
            let reqs = i.session.tool_key(key.tool_key(), mods, view)?;
            i.requests(reqs, &mut out);
            out.document_changed = i.revision() != before;
            out.view_changed = true;
            Ok(())
        });
        if let Err(e) = r {
            out.error = Some(e.to_string());
        }
        out
    }

    /// Typed text for the active tool (the Type tool), from the soft keyboard or a hardware one.
    pub fn text(&self, text: String) -> InputResult {
        let mut out = InputResult::default();
        let r = self.with(|i| {
            let before = i.revision();
            let view = i.view_info(false);
            let reqs = i.session.tool_text(&text, view)?;
            i.requests(reqs, &mut out);
            out.document_changed = i.revision() != before;
            out.view_changed = true;
            Ok(())
        });
        if let Err(e) = r {
            out.error = Some(e.to_string());
        }
        out
    }

    /// The active tool takes typed text (show the soft keyboard).
    pub fn wants_text(&self) -> bool {
        self.lock().session.tool_wants_text()
    }

    /// A desktop shortcut chord from a hardware keyboard (`Cmd+Z`, `Cmd+Shift+Z`, `P`, `Shift+M`):
    /// runs the command or chooses the tool it belongs to. Returns the command or tool id, if any.
    pub fn shortcut(&self, chord: String) -> Result<Option<String>> {
        if let Some(t) = vectorcraft_tools::catalog::tool_for_shortcut(&chord) {
            self.select_tool(t.id.to_string())?;
            return Ok(Some(t.id.to_string()));
        }
        let Some(spec) = vectorcraft_engine::command_specs().iter().find(|c| c.shortcut == Some(chord.as_str())) else { return Ok(None) };
        let id = spec.id.to_string();
        self.execute(id.clone(), String::new())?;
        Ok(Some(id))
    }

    // ---------- drawing ----------

    /// Draw the canvas at its current size and view.
    pub fn render(&self) -> Result<Frame> {
        self.with(|i| Ok(i.render()))
    }

    /// The overlays to draw over the frame, in view pixels: the selection, then the tool's.
    pub fn overlays(&self) -> Vec<OverlayShape> {
        self.with(|i| {
            let vp = i.viewport;
            let view = i.view_info(false);
            let tool = i.session.tool_id();
            let show_box = tool == "selection" && i.view.bounding_box && !i.session.tool_transforming();
            let tool_overlays = i.session.overlays(view);
            let mut out = overlay::Out::new(&vp);
            if let Some(st) = i.session.active() {
                let bbox = (show_box && !st.selection.is_empty() && st.selection.anchors.is_empty())
                    .then(|| i.session.transform_box(&st.selection.objects))
                    .flatten();
                out.selection(&st.doc, &st.selection, vectorcraft_tools::catalog::edits_anchors(tool), bbox);
            }
            out.tool(&tool_overlays);
            Ok(out.shapes)
        })
        .unwrap_or_default()
    }

    /// The Layers panel: each layer and the objects in it, `depth` levels deep (0: layers only),
    /// top of the stack first.
    pub fn layers(&self, depth: u32) -> Vec<LayerRow> {
        fn walk(
            doc: &vectorcraft_doc::Document,
            sel: &vectorcraft_doc::Selection,
            n: &vectorcraft_doc::Node,
            level: u32,
            max: u32,
            out: &mut Vec<LayerRow>,
        ) {
            if out.len() >= MAX_ROWS {
                return;
            }
            let children = n.children();
            out.push(LayerRow {
                id: n.id.0,
                name: n.display_name(),
                depth: level,
                is_layer: matches!(n.kind, vectorcraft_doc::NodeKind::Layer { .. }),
                visible: n.visible,
                locked: n.locked,
                selected: sel.contains(n.id),
                child_count: children.map_or(0, |c| u32::try_from(c.len()).unwrap_or(u32::MAX)),
                color: overlay::argb(doc.layer_color(n.id), 0xff),
            });
            if level < max {
                for c in children.into_iter().flatten().rev() {
                    walk(doc, sel, c, level + 1, max, out);
                }
            }
        }
        let i = self.lock();
        let Some(st) = i.session.active() else { return vec![] };
        let mut out = vec![];
        for l in st.doc.layers.iter().rev() {
            walk(&st.doc, &st.selection, l, 0, depth.min(32), &mut out);
        }
        out
    }

    /// Thumbnail of the active document's first artboard, at most `size` pixels on its longer side
    /// (PNG), for a recent-files grid.
    pub fn thumbnail_png(&self, size: u32) -> Result<Vec<u8>> {
        self.with(|i| {
            let st = i.session.active().ok_or_else(|| invalid("no document"))?;
            let r = st.doc.artboards.first().map(|a| a.rect).ok_or_else(|| invalid("no artboard"))?;
            let side = r.width().max(r.height());
            if !(side.is_finite() && side > 0.0) {
                return Err(invalid("empty artboard"));
            }
            let scale = f64::from(size.clamp(16, 2048)) / side;
            let doc = st.doc.clone();
            let img = i.renderer.render_region(&doc, r, scale, true);
            img.to_png().map_err(|message| VectorError::Engine { message })
        })
    }
}

/// Folders to look for fonts in besides the system's (an app's own fonts, ones the user imported).
#[uniffi::export]
pub fn set_font_dirs(dirs: Vec<String>) {
    vectorcraft_text::set_user_font_dirs(dirs.into_iter().map(std::path::PathBuf::from).collect());
}

/// The engine's version (the workspace version).
#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[cfg(test)]
mod tests;
