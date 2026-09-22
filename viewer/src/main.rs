use macroquad::prelude::*;
use serde_json::Value;
use std::collections::HashSet;

const FONT_PATH: &str = "C:/Windows/Fonts/NotoSansJP-VF.ttf";
const CELL: f32 = 0.5;
const CUBE: f32 = 0.92;
const AGENT: f32 = 0.6;
const PLAY_INTERVAL: f32 = 0.22;
const BAR_H: f32 = 86.0;

struct Step {
    kind: String,
    block: Option<(i32, i32, i32)>,
    agent: (i32, i32, i32),
    facing: i32,
}

struct Data {
    ok: bool,
    error: String,
    truncated: bool,
    steps: Vec<Step>,
    placed: i64,
    destroyed: i64,
    blocked: i64,
    ops: i64,
}

fn num(v: &Value, key: &str) -> Option<i32> {
    v.get(key).and_then(|x| x.as_i64()).map(|x| x as i32)
}

fn load(path: &str) -> Data {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    let root: Value = serde_json::from_str(&text).unwrap_or(Value::Null);

    let mut steps = Vec::new();
    if let Some(list) = root.get("events").and_then(|e| e.as_array()) {
        for e in list {
            let kind = e.get("t").and_then(|t| t.as_str()).unwrap_or("").to_string();
            let block = match (num(e, "x"), num(e, "y"), num(e, "z")) {
                (Some(x), Some(y), Some(z)) => Some((x, y, z)),
                _ => None,
            };
            steps.push(Step {
                kind,
                block,
                agent: (
                    num(e, "ax").unwrap_or(0),
                    num(e, "ay").unwrap_or(0),
                    num(e, "az").unwrap_or(0),
                ),
                facing: num(e, "f").unwrap_or(0),
            });
        }
    }
    let s = root.get("summary");
    let pick = |key: &str| -> i64 {
        s.and_then(|v| v.get(key)).and_then(|v| v.as_i64()).unwrap_or(0)
    };
    Data {
        ok: root.get("ok").and_then(|v| v.as_bool()).unwrap_or(false),
        error: root.get("error").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        truncated: root.get("truncated").and_then(|v| v.as_bool()).unwrap_or(false),
        placed: pick("placed"),
        destroyed: pick("destroyed"),
        blocked: pick("blocked"),
        ops: pick("ops"),
        steps,
    }
}

fn world_at(steps: &[Step], upto: usize) -> HashSet<(i32, i32, i32)> {
    let mut set = HashSet::new();
    for s in steps.iter().take(upto) {
        if let Some(b) = s.block {
            match s.kind.as_str() {
                "place" | "world" => {
                    set.insert(b);
                }
                "destroy" => {
                    set.remove(&b);
                }
                _ => {}
            }
        }
    }
    set
}

fn agent_at(steps: &[Step], upto: usize) -> ((i32, i32, i32), i32) {
    if upto == 0 {
        return ((0, 0, 0), 0);
    }
    let s = &steps[upto - 1];
    (s.agent, s.facing)
}

fn facing_vec(f: i32) -> Vec3 {
    match f.rem_euclid(4) {
        0 => vec3(0.0, 0.0, 1.0),
        1 => vec3(1.0, 0.0, 0.0),
        2 => vec3(0.0, 0.0, -1.0),
        _ => vec3(-1.0, 0.0, 0.0),
    }
}

fn center(b: (i32, i32, i32)) -> Vec3 {
    vec3(b.0 as f32 + CELL, b.1 as f32 + CELL, b.2 as f32 + CELL)
}

fn conf() -> Conf {
    Conf {
        window_title: "MakeCode 3D preview".to_owned(),
        window_width: 960,
        window_height: 720,
        high_dpi: true,
        ..Default::default()
    }
}

struct Ui {
    font: Option<Font>,
}

impl Ui {
    fn text(&self, ja: &str, en: &str) -> String {
        if self.font.is_some() { ja.to_string() } else { en.to_string() }
    }

    fn draw(&self, s: &str, x: f32, y: f32, size: u16, color: Color) {
        draw_text_ex(s, x, y, TextParams {
            font: self.font.as_ref(),
            font_size: size,
            color,
            ..Default::default()
        });
    }

    fn width(&self, s: &str, size: u16) -> f32 {
        measure_text(s, self.font.as_ref(), size, 1.0).width
    }
}

fn button(ui: &Ui, x: f32, y: f32, w: f32, h: f32, label: &str, on: bool) -> bool {
    let (mx, my) = mouse_position();
    let hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
    let bg = if on {
        Color::new(0.30, 0.52, 0.85, 1.0)
    } else if hover {
        Color::new(0.32, 0.34, 0.38, 1.0)
    } else {
        Color::new(0.24, 0.26, 0.30, 1.0)
    };
    draw_rectangle(x, y, w, h, bg);
    let tw = ui.width(label, 20);
    ui.draw(label, x + (w - tw) / 2.0, y + h / 2.0 + 7.0, 20, WHITE);
    hover && is_mouse_button_pressed(MouseButton::Left)
}

#[macroquad::main(conf)]
async fn main() {
    let path = std::env::args().nth(1).unwrap_or_default();
    let data = load(&path);
    let ui = Ui { font: load_ttf_font(FONT_PATH).await.ok() };

    let total = data.steps.len();
    let mut step = total;
    let mut playing = false;
    let mut timer = 0.0f32;

    let mut yaw = 0.8f32;
    let mut pitch = 0.6f32;
    let mut dist = 14.0f32;
    let mut target = vec3(0.0, 0.0, 0.0);

    let all = world_at(&data.steps, total);
    if !all.is_empty() {
        let mut min = vec3(f32::MAX, f32::MAX, f32::MAX);
        let mut max = vec3(f32::MIN, f32::MIN, f32::MIN);
        for b in &all {
            let c = center(*b);
            min = min.min(c);
            max = max.max(c);
        }
        target = (min + max) * 0.5;
        dist = ((max - min).length() + 6.0).max(8.0);
    }
    let home = (yaw, pitch, dist, target);

    let mut last_mouse = mouse_position();
    let mut dragging_slider = false;

    loop {
        let (mx, my) = mouse_position();
        let dx = mx - last_mouse.0;
        let dy = my - last_mouse.1;
        last_mouse = (mx, my);

        let over_bar = my > screen_height() - BAR_H;
        if is_mouse_button_down(MouseButton::Left) && !over_bar && !dragging_slider {
            yaw -= dx * 0.008;
            pitch = (pitch + dy * 0.008).clamp(-1.5, 1.5);
        }
        if is_mouse_button_down(MouseButton::Right) {
            let right = vec3(yaw.cos(), 0.0, -yaw.sin());
            let up = vec3(0.0, 1.0, 0.0);
            target += right * (-dx * 0.02) + up * (dy * 0.02);
        }
        let wheel = mouse_wheel().1;
        if wheel != 0.0 {
            dist = (dist * if wheel > 0.0 { 0.9 } else { 1.1 }).clamp(2.0, 200.0);
        }

        if is_key_pressed(KeyCode::Right) && step < total {
            step += 1;
            playing = false;
        }
        if is_key_pressed(KeyCode::Left) && step > 0 {
            step -= 1;
            playing = false;
        }
        if is_key_pressed(KeyCode::Home) {
            step = 0;
            playing = false;
        }
        if is_key_pressed(KeyCode::End) {
            step = total;
            playing = false;
        }
        if is_key_pressed(KeyCode::Space) {
            if step >= total {
                step = 0;
            }
            playing = !playing;
        }
        if is_key_pressed(KeyCode::R) {
            yaw = home.0;
            pitch = home.1;
            dist = home.2;
            target = home.3;
        }
        if is_key_pressed(KeyCode::Escape) {
            break;
        }

        if playing {
            timer += get_frame_time();
            while timer >= PLAY_INTERVAL && step < total {
                timer -= PLAY_INTERVAL;
                step += 1;
            }
            if step >= total {
                playing = false;
            }
        }

        clear_background(Color::new(0.09, 0.10, 0.12, 1.0));

        let eye = vec3(
            target.x + dist * pitch.cos() * yaw.sin(),
            target.y + dist * pitch.sin(),
            target.z + dist * pitch.cos() * yaw.cos(),
        );
        set_camera(&Camera3D {
            position: eye,
            up: vec3(0.0, 1.0, 0.0),
            target,
            ..Default::default()
        });

        draw_grid(24, 1.0, Color::new(0.35, 0.37, 0.42, 1.0), Color::new(0.20, 0.22, 0.26, 1.0));
        draw_line_3d(vec3(0.0, 0.01, 0.0), vec3(4.0, 0.01, 0.0), RED);
        draw_line_3d(vec3(0.0, 0.0, 0.0), vec3(0.0, 4.0, 0.0), GREEN);
        draw_line_3d(vec3(0.0, 0.01, 0.0), vec3(0.0, 0.01, 4.0), SKYBLUE);

        let world = world_at(&data.steps, step);
        let newest = if step > 0 { data.steps[step - 1].block } else { None };
        for b in &world {
            let c = center(*b);
            let fresh = Some(*b) == newest;
            let col = if fresh {
                Color::new(1.0, 0.78, 0.25, 1.0)
            } else {
                Color::new(0.85, 0.68, 0.30, 1.0)
            };
            draw_cube(c, vec3(CUBE, CUBE, CUBE), None, col);
            draw_cube_wires(c, vec3(CUBE, CUBE, CUBE), Color::new(0.25, 0.20, 0.10, 1.0));
        }

        let (apos, afacing) = agent_at(&data.steps, step);
        let ac = center(apos);
        draw_cube(ac, vec3(AGENT, AGENT, AGENT), None, Color::new(0.30, 0.78, 0.95, 0.9));
        draw_cube_wires(ac, vec3(AGENT, AGENT, AGENT), WHITE);
        let dirv = facing_vec(afacing);
        draw_line_3d(ac, ac + dirv * 0.95, WHITE);
        draw_cube(ac + dirv * 0.85, vec3(0.16, 0.16, 0.16), None, WHITE);

        set_default_camera();

        let w = screen_width();
        let h = screen_height();
        let mut y = 26.0;
        if !data.ok {
            ui.draw(&ui.text("コードを実行できませんでした", "Failed to run the code"), 16.0, y, 22, Color::new(1.0, 0.45, 0.45, 1.0));
            y += 26.0;
            ui.draw(&data.error, 16.0, y, 18, Color::new(1.0, 0.7, 0.7, 1.0));
            y += 26.0;
        }
        let summary = format!(
            "{}: {}   {}: {}   {}: {}   {}: {}",
            ui.text("ブロック", "blocks"), data.placed,
            ui.text("壊した", "destroyed"), data.destroyed,
            ui.text("進めなかった", "blocked"), data.blocked,
            ui.text("操作", "ops"), data.ops
        );
        ui.draw(&summary, 16.0, y, 20, WHITE);
        y += 24.0;
        if data.truncated {
            ui.draw(&ui.text("操作が多すぎるため途中で打ち切りました", "stopped early: too many operations"),
                16.0, y, 18, Color::new(1.0, 0.8, 0.4, 1.0));
            y += 22.0;
        }
        ui.draw(&ui.text(
            "左ドラッグ:回転  右ドラッグ:移動  ホイール:ズーム  ←→:ステップ  Space:再生  R:視点リセット  Esc:閉じる",
            "drag:rotate  right-drag:pan  wheel:zoom  arrows:step  space:play  R:reset view  Esc:close"),
            16.0, y, 16, Color::new(0.7, 0.72, 0.78, 1.0));

        draw_rectangle(0.0, h - BAR_H, w, BAR_H, Color::new(0.13, 0.14, 0.17, 0.95));

        let by = h - BAR_H + 12.0;
        let bw = 52.0;
        let bh = 30.0;
        if button(&ui, 16.0, by, bw, bh, "|<", false) {
            step = 0;
            playing = false;
        }
        if button(&ui, 16.0 + bw + 6.0, by, bw, bh, "<", false) && step > 0 {
            step -= 1;
            playing = false;
        }
        let play_label = if playing { "||" } else { "▷" };
        if button(&ui, 16.0 + (bw + 6.0) * 2.0, by, bw, bh, play_label, playing) {
            if step >= total {
                step = 0;
            }
            playing = !playing;
        }
        if button(&ui, 16.0 + (bw + 6.0) * 3.0, by, bw, bh, ">", false) && step < total {
            step += 1;
            playing = false;
        }
        if button(&ui, 16.0 + (bw + 6.0) * 4.0, by, bw, bh, ">|", false) {
            step = total;
            playing = false;
        }

        let label = format!("{} {} / {}", ui.text("ステップ", "step"), step, total);
        ui.draw(&label, 16.0 + (bw + 6.0) * 5.0 + 10.0, by + 21.0, 20, WHITE);

        let sx = 16.0;
        let sw = w - 32.0;
        let sy = h - 30.0;
        draw_rectangle(sx, sy, sw, 10.0, Color::new(0.26, 0.28, 0.33, 1.0));
        let ratio = if total == 0 { 1.0 } else { step as f32 / total as f32 };
        draw_rectangle(sx, sy, sw * ratio, 10.0, Color::new(0.30, 0.62, 0.95, 1.0));
        draw_circle(sx + sw * ratio, sy + 5.0, 8.0, WHITE);

        let on_slider = my >= sy - 10.0 && my <= sy + 20.0 && mx >= sx && mx <= sx + sw;
        if is_mouse_button_pressed(MouseButton::Left) && on_slider {
            dragging_slider = true;
        }
        if !is_mouse_button_down(MouseButton::Left) {
            dragging_slider = false;
        }
        if dragging_slider && total > 0 {
            let r = ((mx - sx) / sw).clamp(0.0, 1.0);
            step = (r * total as f32).round() as usize;
            playing = false;
        }

        next_frame().await
    }
}
