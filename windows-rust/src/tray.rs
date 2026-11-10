//! 托盘图标与菜单（tray-icon）。事件由 GUI 每帧从全局 receiver 取走。

use tray_icon::menu::{Menu, MenuItem, PredefinedMenuItem};
use tray_icon::{TrayIcon, TrayIconBuilder};

pub const MENU_SHOW: &str = "show";
pub const MENU_SYNC_NOW: &str = "sync_now";
pub const MENU_SYNC_UPLOAD: &str = "sync_upload";
pub const MENU_SYNC_DOWNLOAD: &str = "sync_download";
pub const MENU_TOGGLE_PAUSE: &str = "toggle_pause";
pub const MENU_OPEN_DIR: &str = "open_dir";
pub const MENU_OPEN_CLOUD: &str = "open_cloud";
pub const MENU_QUIT: &str = "quit";

/// 32x32 RGBA 托盘图标：蓝云 + 上下双向箭头（与 Python 端一致）。
pub fn icon_rgba() -> Vec<u8> {
    let w = 32usize;
    let h = 32usize;
    let mut buf = vec![0u8; w * h * 4];
    let blue = [37u8, 99, 235, 255];
    let white = [255u8, 255, 255, 255];

    let in_ellipse = |x: f64, y: f64, cx: f64, cy: f64, rx: f64, ry: f64| {
        ((x - cx) / rx).powi(2) + ((y - cy) / ry).powi(2) <= 1.0
    };
    let in_tri = |px: f64, py: f64, t: &[(f64, f64); 3]| {
        let (x1, y1) = t[0];
        let (x2, y2) = t[1];
        let (x3, y3) = t[2];
        let d1 = (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2);
        let d2 = (px - x3) * (y2 - y3) - (x2 - x3) * (py - y3);
        let d3 = (px - x1) * (y3 - y1) - (x3 - x1) * (py - y1);
        let has_neg = (d1 < 0.0) || (d2 < 0.0) || (d3 < 0.0);
        let has_pos = (d1 > 0.0) || (d2 > 0.0) || (d3 > 0.0);
        !(has_neg && has_pos)
    };

    for y in 0..h {
        for x in 0..w {
            let fx = x as f64 + 0.5;
            let fy = y as f64 + 0.5;
            let mut color: Option<[u8; 4]> = None;
            // 云：两个圆 + 连接矩形
            if in_ellipse(fx, fy, 9.0, 19.0, 7.0, 7.0)
                || in_ellipse(fx, fy, 17.0, 13.0, 7.0, 7.0)
                || (8.0..=15.0).contains(&fx) && (16.0..=20.0).contains(&fy)
            {
                color = Some(blue);
            }
            // 上箭头（白）：柱 + 三角
            if (12.0..=14.0).contains(&fx) && (14.0..=22.0).contains(&fy)
                || in_tri(fx, fy, &[(13.0, 9.0), (9.0, 15.0), (17.0, 15.0)])
            {
                color = Some(white);
            }
            // 下箭头（白）
            if (16.0..=18.0).contains(&fx) && (14.0..=21.0).contains(&fy)
                || in_tri(fx, fy, &[(17.0, 27.0), (13.0, 21.0), (21.0, 21.0)])
            {
                color = Some(white);
            }
            if let Some(c) = color {
                let idx = (y * w + x) * 4;
                buf[idx..idx + 4].copy_from_slice(&c);
            }
        }
    }
    buf
}

/// 创建托盘图标（必须在有 win32 消息循环的主线程调用一次）。
pub fn build_tray() -> Option<TrayIcon> {
    let menu = Menu::new();
    let show = MenuItem::with_id(MENU_SHOW, "打开主窗口", true, None);
    let sync_now = MenuItem::with_id(MENU_SYNC_NOW, "立即同步（按设置的方向）", true, None);
    let sync_up = MenuItem::with_id(MENU_SYNC_UPLOAD, "仅上传一轮", true, None);
    let sync_down = MenuItem::with_id(MENU_SYNC_DOWNLOAD, "仅下载一轮", true, None);
    let pause = MenuItem::with_id(MENU_TOGGLE_PAUSE, "暂停 / 恢复自动同步", true, None);
    let sep1 = PredefinedMenuItem::separator();
    let open_dir = MenuItem::with_id(MENU_OPEN_DIR, "打开本地目录", true, None);
    let open_cloud = MenuItem::with_id(MENU_OPEN_CLOUD, "打开云端目录", true, None);
    let sep2 = PredefinedMenuItem::separator();
    let quit = MenuItem::with_id(MENU_QUIT, "退出", true, None);

    for item in [&show, &sync_now, &sync_up, &sync_down, &pause] {
        let _ = menu.append(item);
    }
    let _ = menu.append(&sep1);
    let _ = menu.append(&open_dir);
    let _ = menu.append(&open_cloud);
    let _ = menu.append(&sep2);
    let _ = menu.append(&quit);

    let rgba = icon_rgba();
    let icon = tray_icon::Icon::from_rgba(rgba, 32, 32).ok()?;

    TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("S3FileSync")
        .with_icon(icon)
        .build()
        .ok()
}

#[cfg(test)]
mod tests {
    #[test]
    fn icon_buffer_size() {
        let buf = super::icon_rgba();
        assert_eq!(buf.len(), 32 * 32 * 4);
        // 中心应有蓝色像素
        assert_ne!(&buf[(16 * 32 + 8) * 4..(16 * 32 + 8) * 4 + 4], &[0, 0, 0, 0]);
    }
}
