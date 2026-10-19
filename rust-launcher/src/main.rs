//! SSMP Launcher - opened by the "Starsector (SSMP)" desktop shortcut.
//!
//! WHAT IT DOES (plain words, for anyone reading the source):
//!  - Shows a small window in the Starsector launcher's own style (it loads Starsector's launcher artwork from the
//!    player's own install - nothing of Starsector's is copied into this program).
//!  - "IROH" / "LAN" (0.9.1.6): which way SSMP connects. Both FIRST start the SSMP crash logger (a report file in
//!    mods/StarMP/crash logs, or wherever the player chose in F8), THEN tick SSMP in Starsector's mod list
//!    (mods/enabled_mods.json - the file Starsector's own launcher edits) and start Starsector. IROH = online, through
//!    a join code (direct, or through Iroh's relays when a direct link isn't possible); LAN = direct connections only,
//!    no internet relays (same as the "LAN / direct only" box in the game). The choice is passed to the game as the
//!    environment variable STARMP_NET (iroh / lan). Until the mod is running in the game
//!    it copies Starsector's own log lines into the report; once the mod loads, the mod's logger takes the file over.
//!    If the game ends before that (closed, crashed, killed), it finishes the report: deleted on a normal exit, kept
//!    with the end of starsector.log otherwise.
//!  - Other mods in the list are never changed. Players can still change anything in Starsector's own Mods screen.
//!  - Nothing is sent over the network. No files outside the Starsector folder are touched (except the desktop, if
//!    the player chose the desktop for reports).
#![cfg_attr(windows, windows_subsystem = "windows")]

mod game;

use eframe::egui::{self, Color32, FontId, Pos2, Rect, Sense, TextureHandle, Vec2};
use std::sync::{Arc, Mutex};

#[derive(Clone, Copy, PartialEq)]
enum Choice { Iroh, Lan }

struct App {
    paths: Result<game::Paths, String>,
    ssmp_version: String,
    ssmp_on: bool,
    logger_off: bool,
    bg: Option<TextureHandle>,
    frame: Option<TextureHandle>,
    choice: Arc<Mutex<Option<Choice>>>,
}

fn tex(ctx: &egui::Context, name: &str, path: Option<std::path::PathBuf>) -> Option<TextureHandle> {
    let img = image::open(path?).ok()?.to_rgba8();
    let size = [img.width() as usize, img.height() as usize];
    Some(ctx.load_texture(name, egui::ColorImage::from_rgba_unmultiplied(size, img.as_raw()), egui::TextureOptions::LINEAR))
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>, paths: Result<game::Paths, String>, choice: Arc<Mutex<Option<Choice>>>) -> Self {
        let ui = paths.as_ref().ok().map(|p| p.ui_graphics());
        let g = |n: &str| ui.as_ref().map(|d| d.join(n));
        let ctx = &cc.egui_ctx;
        let (ssmp_version, ssmp_on) = match &paths { Ok(p) => (p.ssmp_version(), p.ssmp_enabled()), Err(_) => ("?".into(), false) };
        let logger_off = paths.as_ref().map(|p| p.logger_off()).unwrap_or(false);
        App {
            bg: tex(ctx, "bg", g("launcher_bg.jpg")),
            frame: tex(ctx, "frame", g("launch_button_bg.png")),
            paths, ssmp_version, ssmp_on, logger_off, choice,
        }
    }

    /// A Starsector-style launch button (the game's own frame + blue button images). Returns true when clicked.
    fn button(&self, ui: &mut egui::Ui, center_y: f32, label: &str, caption: &str) -> bool {
        let w = ui.max_rect().width();
        let frame_rect = Rect::from_center_size(Pos2::new(w / 2.0, center_y), Vec2::new(216.0, 72.0));
        let btn_rect = Rect::from_center_size(frame_rect.center(), Vec2::new(172.0, 32.0));
        let resp = ui.allocate_rect(btn_rect, Sense::click());
        let p = ui.painter();
        let uv = Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0));
        if let Some(t) = &self.frame { p.image(t.id(), frame_rect, uv, Color32::WHITE); }
        let hot = resp.hovered();
        // Starsector's own blue launch button, drawn in its colours (its image has "Play Starsector" baked in)
        let (top, bottom) = if hot { (Color32::from_rgb(11, 70, 185), Color32::from_rgb(4, 32, 125)) } else { (Color32::from_rgb(10, 58, 160), Color32::from_rgb(3, 26, 108)) };
        let mut mesh = egui::Mesh::default();
        mesh.colored_vertex(btn_rect.left_top(), top); mesh.colored_vertex(btn_rect.right_top(), top);
        mesh.colored_vertex(btn_rect.right_bottom(), bottom); mesh.colored_vertex(btn_rect.left_bottom(), bottom);
        mesh.add_triangle(0, 1, 2); mesh.add_triangle(0, 2, 3);
        p.add(egui::Shape::mesh(mesh));
        p.rect_stroke(btn_rect, 2.0, egui::Stroke::new(1.0, Color32::from_rgb(3, 20, 86)), egui::StrokeKind::Outside);
        p.rect_stroke(btn_rect.shrink(1.5), 2.0, egui::Stroke::new(1.5, if hot { Color32::from_rgb(40, 231, 255) } else { Color32::from_rgb(10, 70, 255) }), egui::StrokeKind::Inside);
        p.text(btn_rect.center(), egui::Align2::CENTER_CENTER, label, FontId::proportional(15.0), Color32::WHITE);
        p.text(Pos2::new(w / 2.0, frame_rect.bottom() - 2.0), egui::Align2::CENTER_TOP, caption, FontId::proportional(11.5), Color32::from_rgb(170, 205, 235));
        if hot { ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand); }
        resp.clicked()
    }
}

impl eframe::App for App {
    fn clear_color(&self, _: &egui::Visuals) -> [f32; 4] { [0.0, 0.0, 0.0, 1.0] }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::CentralPanel::default().frame(egui::Frame::NONE).show(ctx, |ui| {
            let full = ui.max_rect();
            // drag the borderless window from anywhere on the background
            let drag = ui.interact(full, ui.id().with("drag"), Sense::drag());
            if drag.drag_started() { ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag); }
            let p = ui.painter();
            if let Some(t) = &self.bg { p.image(t.id(), full, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE); }
            else { p.rect_filled(full, 0.0, Color32::from_rgb(6, 14, 30)); }
            // close (x), top right like Starsector's launcher
            let x_rect = Rect::from_center_size(Pos2::new(full.right() - 16.0, 18.0), Vec2::splat(18.0));
            let x = ui.allocate_rect(x_rect, Sense::click());
            ui.painter().text(x_rect.center(), egui::Align2::CENTER_CENTER, "x", FontId::proportional(18.0),
                if x.hovered() { Color32::WHITE } else { Color32::from_rgb(180, 200, 220) });
            if x.clicked() { ctx.send_viewport_cmd(egui::ViewportCommand::Close); }

            let cx = full.width() / 2.0;
            let cyan = Color32::from_rgb(110, 210, 255);
            match &self.paths {
                Err(e) => {
                    ui.painter().text(Pos2::new(cx, 120.0), egui::Align2::CENTER_TOP, "SSMP Launcher", FontId::proportional(17.0), cyan);
                    let g = ui.painter().layout(e.clone(), FontId::proportional(13.0), Color32::WHITE, full.width() - 80.0);
                    ui.painter().galley(Pos2::new(40.0, 150.0), g, Color32::WHITE);
                }
                Ok(_) => {
                    ui.painter().text(Pos2::new(cx, 86.0), egui::Align2::CENTER_TOP, format!("SSMP {}  -  Starsector Multiplayer", self.ssmp_version), FontId::proportional(15.0), cyan);
                    ui.painter().text(Pos2::new(cx, 106.0), egui::Align2::CENTER_TOP,
                        format!("SSMP is currently {} in your mod list", if self.ssmp_on { "ON" } else { "OFF" }), FontId::proportional(11.5), Color32::from_rgb(160, 180, 200));
                    let iroh = self.button(ui, 166.0, "IROH", "online - share a join code, no port forwarding");
                    let lan = self.button(ui, 256.0, "LAN", "direct / same network only - no internet relays");
                    let foot = "Both tick SSMP - your other mods stay as they are.";
                    ui.painter().text(Pos2::new(cx, 312.0), egui::Align2::CENTER_TOP, foot, FontId::proportional(10.5), Color32::from_rgb(150, 170, 190));
                    let pick = if iroh { Some(Choice::Iroh) } else if lan { Some(Choice::Lan) } else { None };
                    if let Some(c) = pick {
                        if let Ok(mut g) = self.choice.lock() { *g = Some(c); }
                        ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                    }
                }
            }
        });
    }
}

fn main() {
    let paths = game::Paths::find();
    let choice: Arc<Mutex<Option<Choice>>> = Arc::new(Mutex::new(None));
    let icon = paths.as_ref().ok().and_then(|p| image::open(p.ui_graphics().join("s_icon64.png")).ok()).map(|i| {
        let i = i.to_rgba8();
        egui::IconData { width: i.width(), height: i.height(), rgba: i.into_raw() }
    });
    let mut vp = egui::ViewportBuilder::default()
        .with_inner_size([597.0, 373.0]).with_resizable(false).with_decorations(false).with_title("SSMP Launcher");
    if let Some(i) = icon { vp = vp.with_icon(Arc::new(i)); }
    let opts = eframe::NativeOptions { viewport: vp, centered: true, ..Default::default() };
    let (p2, c2) = (paths.clone(), choice.clone());
    if let Err(e) = eframe::run_native("SSMP Launcher", opts, Box::new(move |cc| Ok(Box::new(App::new(cc, p2, c2))))) {
        game::fatal(&format!("could not open the launcher window: {e}"));
    }
    let picked = *choice.lock().unwrap();
    let Ok(paths) = paths else { return };
    match picked {
        Some(Choice::Iroh) => game::multiplayer(paths, false),
        Some(Choice::Lan) => game::multiplayer(paths, true),
        None => {}
    }
}
