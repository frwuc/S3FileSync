//! 开机自启（HKCU Run 注册表），与 Python 端同一注册表位置。

const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
const VALUE_NAME: &str = "S3FileSync";

/// 当前 exe 的完整路径（发行为单 exe 时即为安装位置）。
pub fn exe_path() -> String {
    std::env::current_exe()
        .map(|p| p.to_string_lossy().to_string())
        .unwrap_or_default()
}

pub fn is_enabled() -> bool {
    use winreg::enums::*;
    use winreg::RegKey;
    if let Ok(hkcu) = RegKey::predef(HKEY_CURRENT_USER).open_subkey_with_flags(RUN_KEY, KEY_READ) {
        if let Ok(v) = hkcu.get_value::<String, _>(VALUE_NAME) {
            return !v.trim().is_empty();
        }
    }
    false
}

/// 开机静默启动：`exe --silent`（不弹主窗口，只在托盘）。
pub fn enable() -> Result<(), String> {
    use winreg::enums::*;
    use winreg::RegKey;
    let cmd = format!("\"{}\" --silent", exe_path());
    let (hkcu, _) = RegKey::predef(HKEY_CURRENT_USER)
        .create_subkey(RUN_KEY)
        .map_err(|e| e.to_string())?;
    hkcu.set_value(VALUE_NAME, &cmd).map_err(|e| e.to_string())
}

pub fn disable() -> Result<(), String> {
    use winreg::enums::*;
    use winreg::RegKey;
    let hkcu = RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(RUN_KEY, KEY_WRITE)
        .map_err(|e| e.to_string())?;
    hkcu.delete_value(VALUE_NAME).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    #[test]
    fn exe_path_exists() {
        let p = super::exe_path();
        assert!(p.to_lowercase().ends_with(".exe"));
    }
}
