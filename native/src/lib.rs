use cocos4_rust::application::{AppConfig, AppManager};
use cocos4_rust::core::scene_graph::Scene;
use cocos4_rust::game::{
    Game, GameBootstrapContract,
};
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use lazy_static::lazy_static;
use log::{debug, error, info, warn, Level};
use std::collections::VecDeque;
use std::fs::{self, File};
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use zip::ZipArchive;
use std::collections::HashMap;

#[cfg(target_os = "android")]
use android_logger::Config;
#[cfg(target_os = "android")]
use ndk::asset::AssetManager;
#[cfg(target_os = "android")]
use ndk_sys as ndk_sys;

#[cfg(not(target_os = "android"))]
struct AssetManager;

const MAX_LOG_LINES: usize = 256;
const DEFAULT_FPS: u32 = 60;

lazy_static! {
    static ref LOG_BUFFER: Mutex<Vec<String>> = Mutex::new(Vec::with_capacity(64));
    static ref ASSET_MANAGER: Mutex<Option<AssetManager>> = Mutex::new(None);
    static ref ASSET_PACKAGE_CACHE: Mutex<HashMap<String, Vec<u8>>> = Mutex::new(HashMap::new());
    static ref LAST_INIT_ERROR: Mutex<String> = Mutex::new(String::new());
}

const ASSET_URI_SCHEME: &str = "assets://";

fn is_asset_uri(path: &str) -> bool {
    path.starts_with(ASSET_URI_SCHEME)
}

fn asset_relative_path(path: &str) -> &str {
    path.strip_prefix(ASSET_URI_SCHEME).unwrap_or(path).trim_start_matches('/')
}

#[derive(Debug, Clone)]
struct GamePackageInfo {
    source: &'static str,
    path: String,
    entry_count: usize,
    has_project_json: bool,
    has_settings_json: bool,
    has_settings_js: bool,
    has_main_js: bool,
    has_src_main_index_js: bool,
    has_application_js: bool,
    has_game_json: bool,
    has_game_js: bool,
    has_engine_adapter_js: bool,
    has_web_adapter_js: bool,
    has_cc_require_js: bool,
    has_kwai_adapter_js: bool,
    runtime_style: &'static str,
    has_main_entry: bool,
    main_entry: Option<String>,
    has_assets: bool,
    has_src: bool,
    has_canvas: bool,
    main_entry_candidates: Vec<String>,
}

impl GamePackageInfo {
    fn new(source: &'static str, path: String) -> Self {
        Self {
            source,
            path,
            entry_count: 0,
            has_project_json: false,
            has_settings_json: false,
            has_settings_js: false,
            has_main_js: false,
            has_src_main_index_js: false,
            has_application_js: false,
            has_game_json: false,
            has_game_js: false,
            has_engine_adapter_js: false,
            has_web_adapter_js: false,
            has_cc_require_js: false,
            has_kwai_adapter_js: false,
            runtime_style: "unknown",
            has_main_entry: false,
            main_entry: None,
            has_assets: false,
            has_src: false,
            has_canvas: false,
            main_entry_candidates: Vec::new(),
        }
    }

    fn is_likely_game_package(&self) -> bool {
        self.has_project_json
            || self.has_settings_json
            || self.has_settings_js
            || self.has_game_json
            || self.has_game_js
            || self.has_main_js
            || self.has_src_main_index_js
            || self.has_application_js
            || self.has_assets
            || self.has_main_entry
            || self.has_src
            || self.has_canvas
    }

    fn derive_runtime_style(&mut self) {
        let modern_signature = self.has_application_js
            || self.has_src_main_index_js
            || self.has_engine_adapter_js
            || (self.has_game_js && !(self.has_main_js || self.has_cc_require_js));

        if modern_signature {
            self.runtime_style = "modern-systemjs";
            if self.main_entry.is_none() {
                if self.has_game_js {
                    self.main_entry = Some("game.js".to_string());
                } else if self.has_application_js {
                    self.main_entry = Some("application.js".to_string());
                } else if self.has_src_main_index_js {
                    self.main_entry = Some("src/main/index.js".to_string());
                } else if self.has_game_json {
                    self.main_entry = Some("game.json".to_string());
                }
            }
            return;
        }

        if self.has_main_js || self.has_game_js || self.has_cc_require_js {
            self.runtime_style = "legacy-cocos2d-js";
            if self.main_entry.is_none() {
                self.main_entry = Some("main.js".to_string());
            }
            return;
        }

        self.runtime_style = "unknown";
    }

    fn runtime_style_tag(&self) -> &str {
        self.runtime_style
    }

    fn summary(&self) -> String {
        format!(
            "source={}, path={}, entries={}, runtime_style={}, project.json={}, settings.json={}, settings.js={}, main.js={}, src/main/index.js={}, application.js={}, game.json={}, game.js={}, ccRequire.js={}, engine-adapter={}, web-adapter={}, kwaiadapter={}, main_entry={}, assets={}, src={}, canvas={}, candidates={}",
            self.source,
            self.path,
            self.entry_count,
            self.runtime_style_tag(),
            self.has_project_json,
            self.has_settings_json,
            self.has_settings_js,
            self.has_main_js,
            self.has_src_main_index_js,
            self.has_application_js,
            self.has_game_json,
            self.has_game_js,
            self.has_cc_require_js,
            self.has_engine_adapter_js,
            self.has_web_adapter_js,
            self.has_kwai_adapter_js,
            self.main_entry.as_deref().unwrap_or("<none>"),
            self.has_assets,
            self.has_src,
            self.has_canvas,
            self.main_entry_candidates.join(" | "),
        )
    }

    fn bootstrap_globals(&self, game_path: &str) -> Vec<(String, String)> {
        vec![
            ("bootstrap_from".to_string(), "cocos4-studio-native".to_string()),
            (
                "bootstrap_runtime_style".to_string(),
                self.runtime_style_tag().to_string(),
            ),
            (
                "bootstrap_main_entry".to_string(),
                self.main_entry.as_deref().unwrap_or("").to_string(),
            ),
            (
                "bootstrap_entry_candidates".to_string(),
                self.main_entry_candidates.join(" | "),
            ),
            (
                "bootstrap_has_project_json",
                if self.has_project_json {
                    "1".to_string()
                } else {
                    "0".to_string()
                },
            ),
            (
                "bootstrap_has_settings_json",
                if self.has_settings_json {
                    "1".to_string()
                } else {
                    "0".to_string()
                },
            ),
            (
                "bootstrap_has_settings_js",
                if self.has_settings_js {
                    "1".to_string()
                } else {
                    "0".to_string()
                },
            ),
            (
                "bootstrap_has_main_js",
                if self.has_main_js {
                    "1".to_string()
                } else {
                    "0".to_string()
                },
            ),
            (
                "bootstrap_has_src_main_index_js",
                if self.has_src_main_index_js {
                    "1".to_string()
                } else {
                    "0".to_string()
                },
            ),
            (
                "bootstrap_has_application_js",
                if self.has_application_js {
                    "1".to_string()
                } else {
                    "0".to_string()
                },
            ),
            ("bootstrap_game_path".to_string(), game_path.to_string()),
        ]
    }

    fn with_candidate(&mut self, entry: &str) {
        if !self.main_entry_candidates.iter().any(|c| c == entry) {
            self.main_entry_candidates.push(entry.to_string());
        }

        match entry {
            "application.js" => {
                self.has_application_js = true;
                self.main_entry = Some("application.js".to_string());
                self.has_main_entry = true;
            }
            "src/main/index.js" => {
                self.has_src_main_index_js = true;
                if self.main_entry.is_none() {
                    self.main_entry = Some("src/main/index.js".to_string());
                }
                self.has_main_entry = true;
            }
            "assets/main/index.js" => {
                self.has_src_main_index_js = true;
                self.main_entry = self.main_entry.clone().or(Some(entry.to_string()));
                self.has_main_entry = true;
            }
            "game.js" => {
                self.has_game_js = true;
                if self.main_entry.is_none() {
                    self.main_entry = Some(entry.to_string());
                }
                self.has_main_entry = true;
            }
            "main.js" => {
                self.has_main_js = true;
                if self.main_entry.is_none() {
                    self.main_entry = Some(entry.to_string());
                }
                self.has_main_entry = true;
            }
            "src/settings.js" => self.has_settings_js = true,
            "src/settings.json" => self.has_settings_json = true,
            "game.json" => self.has_game_json = true,
            "engine-adapter.js" => self.has_engine_adapter_js = true,
            "web-adapter.js" => self.has_web_adapter_js = true,
            "ccRequire.js" => self.has_cc_require_js = true,
            "kwaiadapter.js" => self.has_kwai_adapter_js = true,
            "index.html" | "index.htm" => self.has_canvas = true,
            _ => {}
        }
    }
}

fn collect_main_entry_candidates() -> &'static [&'static str] {
    &[
        "game.js",
        "application.js",
        "main.js",
        "src/main/index.js",
        "assets/main/index.js",
        "src/settings.js",
        "src/settings.json",
        "index.js",
    ]
}

impl Default for GamePackageInfo {
    fn default() -> Self {
        GamePackageInfo::new("directory", String::new())
    }
}

fn finalize_package_info(mut info: GamePackageInfo) -> GamePackageInfo {
    info.main_entry_candidates.sort_by(|a, b| {
        let rank = |entry: &str| {
            collect_main_entry_candidates()
                .iter()
                .position(|candidate| candidate == &entry)
                .unwrap_or(collect_main_entry_candidates().len())
        };
        rank(a).cmp(&rank(b))
    });

    info.main_entry = info
        .main_entry_candidates
        .iter()
        .find_map(|candidate| {
            for priority in collect_main_entry_candidates() {
                if candidate == priority {
                    return Some(candidate.clone());
                }
            }
            None
        })
        .or_else(|| info.main_entry.clone());

    info.derive_runtime_style();
    if info.main_entry.is_some() {
        info.has_main_entry = true;
    }

    info
}

fn entry_matcher(entry_name: &str) -> bool {
    matches_manifest_like(
        &normalize_entry_name(entry_name),
        &[
            "project.json",
            "cocos-project.json",
            "src/settings.json",
            "src/settings.js",
            "settings.json",
            "settings.js",
            "assets/main/index.js",
            "assets/index.js",
            "assets/main.js",
            "src/main.js",
            "src/index.js",
            "game.json",
            "index.js",
            "ccRequire.js",
            "physics-min.js",
            "application.js",
            "game.js",
            "main.js",
            "engine-adapter.js",
            "web-adapter.js",
            "first-screen.js",
            "kwaiadapter.js",
            "adapter-min.js",
        ],
    )
}

#[derive(Debug, Clone)]
struct PackageParseIssue {
    code: &'static str,
    message: String,
}

impl PackageParseIssue {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

#[allow(dead_code)]
fn analyze_package_issue_pack(path: &str, issues: &[PackageParseIssue]) -> String {
    if issues.is_empty() {
        return format!("path={},issues=none", path);
    }

    let mut payload = format!("path={},count={}", path, issues.len());
    for issue in issues {
        payload.push_str(&format!(
            ";{}={}",
            issue.code,
            issue.message.replace(',', ";").replace('|', ";")
        ));
    }
    payload
}

fn has_manifest_indicator(info: &GamePackageInfo) -> bool {
    matches!(
        info.runtime_style,
        "legacy-cocos2d-js" | "modern-systemjs" | "unknown"
    )
}

fn build_bootstrap_contract(game_path: &str, info: &GamePackageInfo) -> GameBootstrapContract {
    let settings_path = if info.has_settings_js {
        Some("src/settings.js".to_string())
    } else if info.has_settings_json {
        Some("src/settings.json".to_string())
    } else if info.has_project_json {
        Some("project.json".to_string())
    } else {
        None
    };
    let mut main_entry_source = info
        .main_entry
        .as_ref()
        .and_then(|main_entry| read_main_entry_source(game_path, main_entry))
        .and_then(|source| {
            if source.trim().is_empty() {
                None
            } else {
                Some(source)
            }
        });
    if info.main_entry.as_deref() == Some("game.js") && info.runtime_style == "modern-systemjs" {
        if let Some(application_source) =
            read_main_entry_source(game_path, "application.js").or_else(|| {
                read_main_entry_source(game_path, "src/application.js")
            })
        {
            if let Some(main_source) = main_entry_source.take() {
                main_entry_source = Some(format!(
                    "{}\n\n// Injected application module for bootstrap compatibility.\n{}",
                    application_source, main_source
                ));
            } else {
                main_entry_source = Some(application_source);
            }
        }
    }
    let settings_source = settings_path
        .as_ref()
        .and_then(|settings_path| read_main_entry_source(game_path, settings_path))
        .and_then(|source| {
            if source.trim().is_empty() {
                None
            } else {
                Some(source)
            }
        });
    if info.main_entry.as_ref().is_some() && main_entry_source.is_none() {
        add_to_log(
            Level::Warn,
            &format!(
                "Bootstrap entry '{}' exists in analysis index but content was not loadable from '{}'.",
                info.main_entry.as_deref().unwrap_or("unknown"),
                game_path
            ),
        );
    }

    GameBootstrapContract {
        runtime_style: info.runtime_style.to_string(),
        main_entry: info.main_entry.clone(),
        main_entry_source,
        game_path: game_path.to_string(),
        settings_path,
        settings_source,
        entry_candidates: info.main_entry_candidates.clone(),
    }
}

fn read_main_entry_source(game_path: &str, main_entry: &str) -> Option<String> {
    if main_entry.trim().is_empty() {
        return None;
    }

    if is_asset_uri(game_path) {
        let asset_path = asset_relative_path(game_path);
        if asset_path.is_empty() {
            return None;
        }
        return read_text_from_asset_zip(asset_path, main_entry);
    }

    let path = Path::new(game_path);
    let normalized_entry = normalize_entry_name(main_entry);
    if path.is_dir() {
        let entry_path = path.join(&normalized_entry);
        return read_text_file(&entry_path);
    }
    if path.is_file() {
        return read_text_from_zip(path, &normalized_entry);
    }
    None
}

fn read_text_file(path: &Path) -> Option<String> {
    if !path.is_file() {
        return None;
    }

    let mut bytes = Vec::new();
    let mut file = File::open(path).ok()?;
    file.read_to_end(&mut bytes).ok()?;
    if bytes.is_empty() {
        return None;
    }
    Some(String::from_utf8_lossy(&bytes).into_owned())
}

fn read_text_from_zip(path: &Path, entry: &str) -> Option<String> {
    let data = fs::read(path).ok()?;
    read_text_from_zip_bytes(&data, entry)
}

fn read_text_from_zip_bytes(data: &[u8], entry: &str) -> Option<String> {
    let mut archive = ZipArchive::new(Cursor::new(data)).ok()?;
    let normalized_entry = normalize_entry_name(entry);

    let lowered_entry = normalized_entry.to_lowercase();
    let mut candidates = Vec::new();
    candidates.push(normalized_entry);
    let trimmed_entry = normalized_entry.trim_start_matches('/').to_string();
    if !trimmed_entry.is_empty() {
        candidates.push(trimmed_entry);
    }
    if lowered_entry != candidates[0] {
        candidates.push(lowered_entry);
    }

    for name in candidates {
        if name.is_empty() {
            continue;
        }
        if let Ok(mut reader) = archive.by_name(name) {
            let mut bytes = Vec::new();
            reader.read_to_end(&mut bytes).ok()?;
            if bytes.is_empty() {
                return None;
            }
            return Some(String::from_utf8_lossy(&bytes).into_owned());
        }
    }

    None
}

fn read_text_from_asset_zip(asset_path: &str, entry: &str) -> Option<String> {
    let data = read_asset_file_bytes_cached(asset_path)?;
    read_text_from_zip_bytes(&data, entry)
}

#[cfg(not(target_os = "android"))]
fn read_asset_file_bytes(_asset_path: &str) -> Option<Vec<u8>> {
    None
}

#[cfg(target_os = "android")]
fn read_asset_file_bytes(asset_path: &str) -> Option<Vec<u8>> {
    let manager = ASSET_MANAGER.lock().unwrap();
    let manager = manager.as_ref()?;

    let cleaned = asset_path.trim_start_matches('/');
    let mut asset = match manager.open(cleaned) {
        Ok(asset) => asset,
        Err(err) => {
            add_to_log(
                Level::Warn,
                &format!("Failed to open asset '{cleaned}' from AssetManager: {err}"),
            );
            return None;
        }
    };

    let mut bytes = Vec::new();
    if asset.read_to_end(&mut bytes).is_err() {
        add_to_log(
            Level::Warn,
            &format!("Failed to read asset '{cleaned}' from AssetManager."),
        );
        return None;
    }

    if bytes.is_empty() {
        add_to_log(Level::Warn, &format!("Asset '{cleaned}' resolved to empty bytes."));
        return None;
    }

    Some(bytes)
}

fn read_asset_file_bytes_cached(asset_path: &str) -> Option<Vec<u8>> {
    let key = asset_relative_path(asset_path).to_string();
    if key.is_empty() {
        return None;
    }

    let cached = {
        let cache = ASSET_PACKAGE_CACHE.lock().unwrap();
        cache.get(&key).cloned()
    };
    if let Some(bytes) = cached {
        return Some(bytes);
    }

    let bytes = read_asset_file_bytes(&key)?;
    {
        let mut cache = ASSET_PACKAGE_CACHE.lock().unwrap();
        cache.insert(key.clone(), bytes.clone());
    }

    Some(bytes)
}

#[allow(dead_code)]
fn entry_candidates_from_path(path: &str) -> Vec<String> {
    let mut candidates = Vec::new();
    for c in collect_main_entry_candidates() {
        if path.contains(c) {
            candidates.push((*c).to_string());
        }
    }
    candidates
}

#[allow(dead_code)]
fn debug_trimmed_path(path: &str) -> String {
    path.split("!").next().unwrap_or("").to_string()
}

fn describe_package_entry_state(info: &GamePackageInfo) -> String {
    format!(
        "runtime_style={}, main_entry={}, candidates=[{}]",
        info.runtime_style_tag(),
        info.main_entry.as_deref().unwrap_or("<none>"),
        info.main_entry_candidates.join(", ")
    )
}

#[allow(dead_code)]
fn record_main_entry_candidate(
    info: &mut GamePackageInfo,
    raw_name: &str,
    normalized: &str,
) {
    if let Some(candidate) = resolve_main_entry_candidate(normalized) {
        let _ = &info;
        if !info.main_entry_candidates.iter().any(|c| c == candidate) {
            info.main_entry_candidates.push(candidate.to_string());
        }
    }

    if let Some(c) = resolve_main_entry_candidate(raw_name) {
        if !info.main_entry_candidates.iter().any(|e| e == c) {
            info.main_entry_candidates.push(c.to_string());
        }
    }

    for candidate in collect_main_entry_candidates() {
        if normalized == *candidate || normalized.ends_with(candidate) {
            let candidate_ref = *candidate;
            info.with_candidate(candidate_ref);
        }
    }
}

impl GamePackageInfo {
    fn finalize(mut self) -> Self {
        finalize_package_info(self)
    }

    fn set_project_json(&mut self) {
        self.has_project_json = true;
    }

    fn set_main_entry_candidate(&mut self, normalized_entry_name: &str) {
        let _ = self;
        if !self.main_entry_candidates.iter().any(|c| c == normalized_entry_name) {
            self.main_entry_candidates.push(normalized_entry_name.to_string());
        }
    }

    fn runtime_style_summary(&self) -> String {
        format!("{}|main_entry:{}", self.runtime_style, self.main_entry.as_deref().unwrap_or("none"))
    }

    fn entry_matches_candidate(&self, entry: &str) -> bool {
        let normalized_entry = normalize_entry_name(entry);
        if normalized_entry.is_empty() {
            return false;
        }

        self.main_entry_candidates.iter().any(|candidate| {
            let normalized_candidate = normalize_entry_name(candidate);
            normalized_candidate == normalized_entry
                || normalized_candidate.ends_with(&normalized_entry)
                || normalized_entry.ends_with(&normalized_candidate)
        })
    }

    fn main_entry_candidates_text(&self) -> String {
        if self.main_entry_candidates.is_empty() {
            return "<none>".to_string();
        }
        self.main_entry_candidates.join(", ")
    }

    fn update_runtime_style(&mut self) {
        self.derive_runtime_style();
    }
}

fn update_package_flags(info: &mut GamePackageInfo, normalized_entry_name: &str) {
    record_main_entry_candidate(info, normalized_entry_name, normalized_entry_name);
}

#[allow(dead_code)]
fn trim_entry_for_summary(entry: &str) -> &str {
    match entry {
        "application.js" => "application.js",
        _ => entry,
    }
}

fn analyze_archive_entry(
    path: &str,
    marker_entry_name: &str,
    has_package_marker: &mut bool,
) -> bool {
    if path == marker_entry_name {
        *has_package_marker = true;
        return true;
    }
    false
}

#[allow(dead_code)]
fn is_main_js(path: &str) -> bool {
    path.ends_with("main.js") || path.ends_with("main.ts")
}

#[allow(dead_code)]
fn entry_to_owned(entry: &str) -> String {
    entry.to_string()
}

#[allow(dead_code)]
fn is_zip_path(path: &str) -> bool {
    let low = path.to_lowercase();
    low.ends_with(".zip")
}

#[allow(dead_code)]
fn is_file_path(path: &str) -> bool {
    !path.ends_with('/') && !path.ends_with('\\')
}

fn entry_has_canvas_target(entry: &str) -> bool {
    matches_manifest_like(entry, &["index.html", "index.htm", "game.html"])
}

fn normalize_main_entry_candidate(entry: &str) -> Option<String> {
    let normalized = normalize_entry_name(entry);
    let candidates = collect_main_entry_candidates();
    for candidate in candidates {
        if normalized == *candidate || normalized.ends_with(candidate) {
            return Some(candidate.to_string());
        }
    }

    None
}

fn record_match(info: &mut GamePackageInfo, normalized_entry_name: &str, entry: &str) {
    if let Some(candidate) = normalize_main_entry_candidate(normalized_entry_name) {
        if !info.main_entry_candidates.iter().any(|e| e == &candidate) {
            info.main_entry_candidates.push(candidate.clone());
        }
    }
    if let Some(candidate) = normalize_main_entry_candidate(entry) {
        if !info.main_entry_candidates.iter().any(|e| e == &candidate) {
            info.main_entry_candidates.push(candidate);
        }
    }

    let is_match = |target: &str| {
        normalized_entry_name == target || entry == target || normalized_entry_name.ends_with(target) || entry.ends_with(target)
    };

    if is_match("application.js") {
        info.has_application_js = true;
        if info.main_entry.is_none() {
            info.main_entry = Some("application.js".to_string());
        }
        info.has_main_entry = true;
    }

    if is_match("assets/main/index.js") {
        info.has_src_main_index_js = true;
        if info.main_entry.is_none() {
            info.main_entry = Some("assets/main/index.js".to_string());
        }
        info.has_main_entry = true;
    }

    if is_match("src/main/index.js") {
        info.has_src_main_index_js = true;
        if info.main_entry.is_none() {
            info.main_entry = Some("src/main/index.js".to_string());
        }
        info.has_main_entry = true;
    }

    if is_match("game.js") {
        info.has_game_js = true;
        if info.main_entry.is_none() {
            info.main_entry = Some("game.js".to_string());
        }
        info.has_main_entry = true;
    }

    if is_match("main.js") {
        info.has_main_js = true;
        if info.main_entry.is_none() {
            info.main_entry = Some("main.js".to_string());
        }
        info.has_main_entry = true;
    }

    if is_match("src/settings.js") {
        info.has_settings_js = true;
    }

    if is_match("src/settings.json") {
        info.has_settings_json = true;
    }

    if is_match("game.json") {
        info.has_game_json = true;
    }

    if is_match("engine-adapter.js") {
        info.has_engine_adapter_js = true;
    }

    if is_match("web-adapter.js") {
        info.has_web_adapter_js = true;
    }

    if is_match("ccRequire.js") {
        info.has_cc_require_js = true;
    }

    if is_match("kwaiadapter.js") {
        info.has_kwai_adapter_js = true;
    }

    if entry_has_canvas_target(normalized_entry_name) {
        info.has_canvas = true;
    }
}

struct EngineRuntime {
    app_manager: AppManager,
    game: Game,
    package_info: GamePackageInfo,
    game_path: String,
    bootstrap_started: bool,
    bootstrap_status_code: String,
    bootstrap_status_message: String,
    fps_limit: u32,
    width: u32,
    height: u32,
    native_window_handle: usize,
    last_frame_ms: f64,
    last_tick: Instant,
    bootstrap_scene_ready: bool,
}

impl EngineRuntime {
    fn new(game_path: String, package_info: GamePackageInfo, native_window_handle: usize) -> Self {
        let app_config = AppConfig {
            target_fps: DEFAULT_FPS,
            ..AppConfig::default()
        };
        let mut app_manager = AppManager::new(app_config);
        let mut game = Game::new();

        app_manager.start();
        game.init();

        let mut runtime = Self {
            app_manager,
            game,
            package_info,
            game_path,
            bootstrap_started: false,
            bootstrap_status_code: "NOT_STARTED".to_string(),
            bootstrap_status_message: "bootstrap-start-not-invoked".to_string(),
            fps_limit: DEFAULT_FPS,
            width: 0,
            height: 0,
            native_window_handle,
            last_frame_ms: 0.0,
            last_tick: Instant::now(),
            bootstrap_scene_ready: false,
        };
        runtime.bootstrap_with_contract();
        runtime.create_bootstrap_scene();
        runtime
    }

    fn bootstrap_with_contract(&mut self) {
        let contract = build_bootstrap_contract(&self.game_path, &self.package_info);
        let native_style = contract.resolve_style_for_native();
        if matches!(
            native_style,
            "legacy-cocos2d-js" | "modern-systemjs"
        ) && self.package_info.entry_count > 0
        {
            let main_entry = contract.main_entry.as_deref().unwrap_or("");
            if main_entry.trim().is_empty() {
                self.bootstrap_started = false;
                self.bootstrap_status_code = "MISSING_MAIN_ENTRY".to_string();
                self.bootstrap_status_message = format!(
                    "No main entry selected for runtime '{}', available_candidates={}",
                    native_style,
                    self.package_info.main_entry_candidates_text()
                );
                add_to_log(
                    Level::Warn,
                    &format!("Bootstrap preflight failed for style '{}': {}", native_style, self.bootstrap_status_message),
                );
                return;
            }
            if !self.package_info.entry_matches_candidate(main_entry) {
                self.bootstrap_started = false;
                self.bootstrap_status_code = "MISSING_MAIN_ENTRY".to_string();
                self.bootstrap_status_message = format!(
                    "No matching main entry was found for runtime '{}': selected='{}', available_candidates={}",
                    native_style,
                    main_entry,
                    self.package_info.main_entry_candidates_text()
                );
                add_to_log(
                    Level::Warn,
                    &format!("Bootstrap preflight failed for style '{}': {}", native_style, self.bootstrap_status_message),
                );
                return;
            }
        }

        match self.game.start_with_bootstrap(&contract) {
            Ok(()) => {
                self.bootstrap_started = true;
                let bootstrap_status = if cfg!(all(feature = "js-runtime-mock", not(feature = "js-runtime-real"))) {
                    "STARTED_SIMULATED"
                } else {
                    "STARTED"
                };
                self.bootstrap_status_code = bootstrap_status.to_string();
                self.bootstrap_status_message = if cfg!(all(feature = "js-runtime-mock", not(feature = "js-runtime-real"))) {
                    "Runtime bootstrap entry was accepted by simulator. Native JS runtime not yet executing game logic.".to_string()
                } else {
                    "Runtime bootstrap entry was accepted by engine.".to_string()
                };
                add_to_log(
                    Level::Info,
                    &format!(
                        "Bootstrap accepted for style '{}' entry '{}'.",
                        native_style,
                        contract.main_entry.unwrap_or_else(|| "<none>".to_string())
                    ),
                );
            }
            Err(err) => {
                let code = err.code().to_string();
                let message = err.message();
                self.bootstrap_started = false;
                self.bootstrap_status_code = code.clone();
                self.bootstrap_status_message = message.clone();
                add_to_log(
                    Level::Warn,
                    &format!(
                        "Bootstrap rejected for style '{}': {} / {}",
                        native_style, code, message
                    ),
                );
            }
        }
    }

    fn create_bootstrap_scene(&mut self) {
        let mut bootstrap = Scene::new("bootstrap");
        for (key, value) in self.package_info.bootstrap_globals(&self.game_path) {
            bootstrap.set_global(&key, &value);
        }
        let native_window = self.native_window_handle;
        if let Ok(mut director) = self.game.get_director().lock() {
            director.run_scene(bootstrap);
            self.bootstrap_scene_ready = true;
            let scene_name = director
                .get_running_scene()
                .map(|s| s.name.clone())
                .unwrap_or_else(|| "<none>".to_string());
            add_to_log(
                Level::Info,
                &format!(
                    "Bootstrap scene installed: game={} (package: {}), bootstrap_status={}::{}",
                    scene_name,
                    self.package_info.summary(),
                    self.bootstrap_status_code,
                    self.bootstrap_status_message
                ),
            );
            if native_window == 0 {
                add_to_log(
                    Level::Warn,
                    "Bootstrap scene created with invalid native window handle; rendering fallback mode is in use.",
                );
            }
        }
    }

    fn set_fps_limit(&mut self, fps_limit: u32) {
        let fps = fps_limit.max(1);
        self.fps_limit = fps;
        self.app_manager.game_loop.config.target_fps = fps;
        self.game.set_frame_rate(fps);
    }

    fn resize(&mut self, width: u32, height: u32) {
        self.width = width;
        self.height = height;
    }

    fn pause(&mut self) {
        self.app_manager.pause();
        self.game.pause();
    }

    fn resume(&mut self) {
        self.app_manager.resume();
        self.game.resume();
    }

    fn stop(&mut self) {
        self.app_manager.stop();
        if self.bootstrap_scene_ready {
            if let Ok(mut director) = self.game.get_director().lock() {
                director.end();
            }
        }
    }

    fn is_running(&self) -> bool {
        self.app_manager.game_loop.is_running()
    }

    fn tick_frame(&mut self) {
        if !self.is_running() || self.game.is_paused() {
            self.last_tick = Instant::now();
            return;
        }

        let now = Instant::now();
        let mut dt = (now - self.last_tick).as_secs_f32();
        self.last_tick = now;

        if !dt.is_finite() || dt <= 0.0 {
            dt = 1.0 / self.fps_limit.max(1) as f32;
        }
        if dt > 0.25 {
            dt = 1.0 / self.fps_limit.max(1) as f32;
        }

        self.app_manager.tick(dt);
        self.game.step(dt);
        self.last_frame_ms = dt as f64 * 1000.0;
    }

    fn frame_delay(&self) -> Duration {
        Duration::from_secs_f64(1.0 / self.fps_limit.max(1) as f64)
    }

    fn stats(&self) -> (f32, f64, u32, u32, bool, usize, bool, String) {
        (
            self.app_manager.game_loop.get_fps(),
            self.last_frame_ms,
            self.width,
            self.height,
            self.bootstrap_scene_ready,
            self.package_info.entry_count,
            self.bootstrap_started,
            self.bootstrap_status_code.clone(),
        )
    }
}

struct EngineInstance {
    runtime: Arc<Mutex<EngineRuntime>>,
    running: Arc<AtomicBool>,
    render_thread: Option<JoinHandle<()>>,
}

impl EngineInstance {
    fn new(game_path: String, package_info: GamePackageInfo, native_window: usize) -> Self {
        let runtime = Arc::new(Mutex::new(EngineRuntime::new(game_path, package_info, native_window)));
        let running = Arc::new(AtomicBool::new(true));
        let thread_runtime = Arc::clone(&runtime);
        let thread_running = Arc::clone(&running);

        let thread = thread::spawn(move || {
            while thread_running.load(Ordering::Acquire) {
                let delay = if let Ok(mut state) = thread_runtime.lock() {
                    state.tick_frame();
                    state.frame_delay()
                } else {
                    Duration::from_millis(16)
                };

                if thread_running.load(Ordering::Acquire) {
                    thread::sleep(delay);
                }
            }
        });

        Self {
            runtime,
            running,
            render_thread: Some(thread),
        }
    }

    fn stop(&mut self) {
        self.running.store(false, Ordering::Release);
        if let Some(handle) = self.render_thread.take() {
            if handle.join().is_err() {
                warn!("Render thread exited with panic.");
            }
        }

        if let Ok(mut runtime) = self.runtime.lock() {
            runtime.stop();
        }
    }
}

fn with_instance_mut<T>(handle: jlong, f: impl FnOnce(&mut EngineInstance) -> T) -> Option<T> {
    if handle == 0 {
        return None;
    }
    let ptr = handle as *mut EngineInstance;
    if ptr.is_null() {
        return None;
    }
    let instance = unsafe { &mut *ptr };
    Some(f(instance))
}

fn normalize_entry_name(name: &str) -> String {
    let mut output = name.replace('\\', "/");
    if output.starts_with("./") {
        output = output.trim_start_matches("./").to_string();
    }
    while output.starts_with('/') {
        output = output.trim_start_matches('/').to_string();
    }
    output.to_lowercase()
}

fn resolve_main_entry_candidate(normalized_entry: &str) -> Option<&'static str> {
    const CANDIDATES: &[&str] = &[
        "assets/main/index.js",
        "assets/index.js",
        "assets/main.js",
        "assets/main.jsbundle",
        "assets/index.jsbundle",
        "index.js",
        "main.js",
        "main.jsbundle",
        "index.jsbundle",
        "src/main.js",
        "src/index.js",
    ];

    CANDIDATES
        .iter()
        .copied()
        .find(|candidate| normalized_entry == *candidate || normalized_entry.ends_with(candidate))
}

fn matches_manifest_like(name: &str, candidates: &[&str]) -> bool {
    let normalized = normalize_entry_name(name);
    if normalized.is_empty() {
        return false;
    }

    candidates.iter().any(|candidate| {
        let candidate = *candidate;
        normalized == candidate || normalized.ends_with(candidate)
    })
}

fn set_last_init_error(msg: &str) {
    let mut guard = LAST_INIT_ERROR.lock().unwrap();
    *guard = msg.to_string();
}

fn take_last_init_error() -> String {
    LAST_INIT_ERROR.lock().unwrap().clone()
}

fn analyze_game_package_path(path_str: &str) -> Result<GamePackageInfo, String> {
    if is_asset_uri(path_str) {
        return analyze_game_asset_package(path_str);
    }

    let path = Path::new(path_str);

    if !path.exists() {
        return Err(format!("Game path does not exist: {path_str}"));
    }
    if path.is_file() {
        return analyze_game_archive(path);
    }
    if path.is_dir() {
        return analyze_game_directory(path);
    }

    Err(format!(
        "Unsupported game path type (not a file or directory): {path_str}"
    ))
}

fn analyze_game_archive(path: &Path) -> Result<GamePackageInfo, String> {
    let bytes = fs::read(path).map_err(|err| format!("Failed to open game package: {err}"))?;
    analyze_game_archive_from_bytes(path.to_string_lossy().as_ref(), &bytes)
}

fn analyze_game_asset_package(path_str: &str) -> Result<GamePackageInfo, String> {
    let relative = asset_relative_path(path_str);
    if relative.is_empty() {
        return Err("Invalid assets URI for game package".to_string());
    }

    let bytes = read_asset_file_bytes(relative)
        .ok_or_else(|| format!("Failed to open game package from assets: {path_str}"))?;
    let result = analyze_game_archive_from_bytes(path_str, &bytes)?;

    {
        let mut cache = ASSET_PACKAGE_CACHE.lock().unwrap();
        cache.insert(relative.to_string(), bytes);
    }

    Ok(result)
}

fn analyze_game_archive_from_bytes(path_str: &str, bytes: &[u8]) -> Result<GamePackageInfo, String> {
    let mut archive = ZipArchive::new(Cursor::new(bytes))
        .map_err(|err| format!("Invalid zip package {path_str}: {err}"))?;
    let mut info = GamePackageInfo::new(
        if is_asset_uri(path_str) { "asset-zip" } else { "zip" },
        path_str.to_string(),
    );

    let count = archive.len();
    for index in 0..count {
        let file = archive
            .by_index(index)
            .map_err(|err| format!("Failed reading zip entry #{index}: {err}"))?;
        let name = normalize_entry_name(file.name());
        if name.is_empty() {
            continue;
        }

        if name.ends_with('/') {
            if name == "assets/" || name.starts_with("assets/") {
                info.has_assets = true;
            }
            if name == "src/" || name.starts_with("src/") {
                info.has_src = true;
            }
            if name == "subpackages/" || name.starts_with("subpackages/") {
                info.main_entry_candidates.push("main.js".to_string());
            }
            continue;
        }

        if matches_manifest_like(&name, &["project.json", "cocos-project.json"]) {
            info.has_project_json = true;
        }
        if name == "assets" || name.starts_with("assets/") {
            info.has_assets = true;
        }
        if name == "src" || name.starts_with("src/") {
            info.has_src = true;
        }
        if entry_matcher(&name) {
            record_match(&mut info, &name, &name);
        }
        info.entry_count += 1;
    }

    info = finalize_package_info(info);

    if !has_manifest_indicator(&info) || !info.is_likely_game_package() {
        return Err(format!(
            "Invalid game package {}: missing cocos runtime markers (project.json/settings.json/assets/index.js)",
            path_str
        ));
    }

    if info.main_entry.is_none() {
        add_to_log(
            Level::Warn,
            &format!(
                "Package {} missing expected cocos main entry candidate; native bootstrap validation will classify as MISSING_MAIN_ENTRY",
                path_str
            ),
        );
    }

    Ok(info)
}

fn analyze_game_directory(path: &Path) -> Result<GamePackageInfo, String> {
    let mut info = GamePackageInfo::new(
        "directory",
        path.to_string_lossy().to_string(),
    );
    let mut pending = VecDeque::new();
    pending.push_back(path.to_path_buf());

    while let Some(dir) = pending.pop_front() {
        let entries = fs::read_dir(&dir)
            .map_err(|err| format!("Failed to read directory {}: {err}", dir.to_string_lossy()))?;

        for entry in entries.flatten() {
            let entry_path = entry.path();
            let relative = entry_path
                .strip_prefix(path)
                .ok()
                .map(std::path::Path::to_path_buf)
                .unwrap_or_else(PathBuf::new);
            let normalized = normalize_entry_name(&relative.to_string_lossy());

            if entry_path.is_dir() {
                if normalized == "assets" || normalized.starts_with("assets/") {
                    info.has_assets = true;
                }
                if normalized == "src" || normalized.starts_with("src/") {
                    info.has_src = true;
                }
                pending.push_back(entry_path);
                continue;
            }

            if normalized.is_empty() {
                continue;
            }

            if matches_manifest_like(&normalized, &["project.json", "cocos-project.json"]) {
                info.has_project_json = true;
            }
            if normalized == "assets" || normalized.starts_with("assets/") {
                info.has_assets = true;
            }
            if normalized == "src" || normalized.starts_with("src/") {
                info.has_src = true;
            }
            if normalized == "subpackages" || normalized.starts_with("subpackages/") {
                info.main_entry_candidates.push("main.js".to_string());
            }
            if entry_matcher(&normalized) {
                record_match(&mut info, &normalized, &normalized);
            }
            info.entry_count += 1;
        }
    }

    info = finalize_package_info(info);

    if !has_manifest_indicator(&info) || !info.is_likely_game_package() {
        return Err(format!(
            "Directory {} does not look like a Cocos game package (missing project/settings/assets/main js entries)",
            path.to_string_lossy()
        ));
    }
    if info.main_entry.is_none() {
        add_to_log(
            Level::Warn,
            &format!(
                "Directory {} missing expected cocos main entry candidate; native bootstrap validation will classify as MISSING_MAIN_ENTRY",
                path.to_string_lossy()
            ),
        );
    }

    Ok(info)
}

fn log_and_fail_init(message: &str) -> jlong {
    set_last_init_error(message);
    add_to_log(Level::Error, message);
    0
}

fn add_to_log(level: Level, message: &str) {
    let line = match level {
        Level::Error => format!("[ERROR] {message}"),
        Level::Warn => format!("[WARN] {message}"),
        Level::Info => format!("[INFO] {message}"),
        _ => format!("[DEBUG] {message}"),
    };

    {
        let mut buffer = LOG_BUFFER.lock().unwrap();
        buffer.push(line.clone());
        if buffer.len() > MAX_LOG_LINES {
            let overflow = buffer.len() - MAX_LOG_LINES;
            buffer.drain(0..overflow);
        }
    }

    match level {
        Level::Error => error!("{message}"),
        Level::Warn => warn!("{message}"),
        Level::Info => info!("{message}"),
        _ => debug!("{message}"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeSetAssetManager(
    mut env: JNIEnv,
    _class: JClass,
    asset_manager: JObject,
) {
    #[cfg(not(target_os = "android"))]
    {
        add_to_log(
            Level::Warn,
            "nativeSetAssetManager is not available in non-android host builds.",
        );
        return;
    }

    #[cfg(target_os = "android")]
    {
    if asset_manager.is_null() {
        add_to_log(
            Level::Warn,
            "nativeSetAssetManager ignored: null AssetManager object passed from Java.",
        );
        return;
    }

    let ptr = unsafe { ndk_sys::AAssetManager_fromJava(env.get_native_interface(), asset_manager.as_raw()) };
    if ptr.is_null() {
        add_to_log(
            Level::Error,
            "Failed to convert Java AssetManager to ndk::asset::AssetManager.",
        );
        return;
    }

    let manager = unsafe { AssetManager::from_ptr(ptr) };
    *ASSET_MANAGER.lock().unwrap() = Some(manager);
    add_to_log(Level::Info, "Native AssetManager set.");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    surface: JObject,
    game_path: JString,
) -> jlong {
    #[cfg(target_os = "android")]
    android_logger::init_once(Config::default().with_tag("CocosRustEngine"));
    {
        let mut error_cell = LAST_INIT_ERROR.lock().unwrap();
        error_cell.clear();
    }

    let game_path_str: String = match env.get_string(&game_path) {
        Ok(path) => path.into(),
        Err(err) => {
            return log_and_fail_init(&format!("Failed to convert gamePath from Java: {err}"));
        }
    };

    let game_path_trimmed = game_path_str.trim();
    if game_path_trimmed.is_empty() {
        return log_and_fail_init("Init rejected: game_path is empty.");
    }
    if is_asset_uri(game_path_trimmed) && ASSET_MANAGER.lock().unwrap().is_none() {
        return log_and_fail_init("Init rejected: no AssetManager injected by app layer for assets protocol.");
    }

    let package_info = match analyze_game_package_path(game_path_trimmed) {
        Ok(info) => {
            add_to_log(Level::Info, &format!("Analyzed game package: {}", info.summary()));
            info
        }
        Err(err) => return log_and_fail_init(&err),
    };

    #[cfg(target_os = "android")]
    let native_window = unsafe {
        ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface.as_raw())
    };
    #[cfg(not(target_os = "android"))]
    let native_window: *mut std::ffi::c_void = std::ptr::null_mut();
    if native_window.is_null() {
        add_to_log(
            Level::Warn,
            "ANativeWindow_fromSurface returned null; using fallback tick loop without native surface handle.",
        );
    } else {
        add_to_log(Level::Info, "ANativeWindow_fromSurface returned a valid surface handle.");
    }

    let instance = Box::new(EngineInstance::new(
        game_path_trimmed.to_string(),
        package_info,
        native_window as usize,
    ));
    add_to_log(Level::Info, "Engine runtime created and bootstrap scene started.");
    set_last_init_error("OK");
    Box::into_raw(instance) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) {
    debug!("Native resize: {}x{}", width, height);
    let width = width.max(0) as u32;
    let height = height.max(0) as u32;
    let _ = with_instance_mut(handle, |instance| {
        if let Ok(mut runtime) = instance.runtime.lock() {
            runtime.resize(width, height);
            add_to_log(
                Level::Info,
                &format!("NativeResize updated to {width}x{height}"),
            );
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut instance = unsafe { Box::from_raw(handle as *mut EngineInstance) };
    add_to_log(Level::Info, "Destroying native engine.");
    instance.stop();
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativePause(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = with_instance_mut(handle, |instance| {
        if let Ok(mut runtime) = instance.runtime.lock() {
            runtime.pause();
            add_to_log(Level::Info, "Native pause.");
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeResume(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = with_instance_mut(handle, |instance| {
        if let Ok(mut runtime) = instance.runtime.lock() {
            runtime.resume();
            add_to_log(Level::Info, "Native resume.");
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeUpdateSettings(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fps_limit: jint,
    enable_shadows: jboolean,
) {
    let fps = if fps_limit > 0 { fps_limit as u32 } else { 1 };
    let _ = with_instance_mut(handle, |instance| {
        if let Ok(mut runtime) = instance.runtime.lock() {
            runtime.set_fps_limit(fps);
        }
    });
    add_to_log(
        Level::Info,
        &format!("Settings updated: FPS={fps}, Shadows={}", enable_shadows != 0),
    );
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeGetInitError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = take_last_init_error();
    env.new_string(text)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeGetPerformanceStats(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("N/A").unwrap().into_raw();
    }

    let mut fps = 0.0f64;
    let mut frame_ms = 0.0f64;
    let mut width = 0u32;
    let mut height = 0u32;
    let mut bootstrap_scene_ready = false;
    let mut entries = 0usize;
    let mut bootstrap_started = false;
    let mut bootstrap_status = String::new();
    let mut main_entry = String::new();
    let _ = with_instance_mut(handle, |instance| {
        if let Ok(runtime) = instance.runtime.lock() {
            let (f, ms, w, h, boot, e, b_started, b_status) = runtime.stats();
            fps = f as f64;
            frame_ms = ms;
            width = w;
            height = h;
            bootstrap_scene_ready = boot;
            entries = e;
            bootstrap_started = b_started;
            bootstrap_status = b_status;
            main_entry = runtime
                .package_info
                .main_entry
                .clone()
                .unwrap_or_else(|| "bootstrap-only".to_string());
        }
    });

    let stats = format!(
        "FPS: {:.1} | {:.1} ms | {}x{} | boot_scene={} | bootstrap_started={} ({}) | entries={} | main_entry={}",
        fps,
        frame_ms,
        width,
        height,
        bootstrap_scene_ready,
        bootstrap_started,
        bootstrap_status,
        entries,
        main_entry
    );
    env.new_string(stats).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeGetPackageSummary(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("No engine handle").unwrap().into_raw();
    }

    let mut summary = String::from("No runtime data");
    let _ = with_instance_mut(handle, |instance| {
        if let Ok(runtime) = instance.runtime.lock() {
            summary = runtime.package_info.summary();
        }
    });

    env.new_string(summary).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeGetBootstrapStatus(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return env.new_string("NO_HANDLE").unwrap().into_raw();
    }

    let mut payload = String::from("NOT_STARTED");
    let _ = with_instance_mut(handle, |instance| {
        if let Ok(runtime) = instance.runtime.lock() {
            payload = format!(
                "{}|{}",
                runtime.bootstrap_status_code, runtime.bootstrap_status_message
            );
        }
    });

    env.new_string(payload).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeGetLogs(
    mut env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) -> JObjectArray<'static> {
    let mut buffer = LOG_BUFFER.lock().unwrap();
    let logs: Vec<String> = buffer.drain(..).collect();

    let array = env
        .new_object_array(logs.len() as jint, "java/lang/String", env.new_string("").unwrap())
        .unwrap();
    for (index, log) in logs.iter().enumerate() {
        let value = env.new_string(log).unwrap();
        env.set_object_array_element(&array, index as jint, value).unwrap();
    }
    array
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_analyze_modern_demo_detects_chain() {
        fn assert_modern_demo_chain(demo_path: &std::path::Path) {
            if !demo_path.exists() {
                return;
            }

            let game_path = demo_path.to_string_lossy().to_string();
            let info = analyze_game_package_path(&game_path).expect("demo package should parse");
            assert_eq!(info.runtime_style, "modern-systemjs");
            assert_eq!(info.main_entry.as_deref(), Some("game.js"));
            assert!(info.main_entry_candidates.contains(&"game.js".to_string()));

            let contract = build_bootstrap_contract(&game_path, &info);
            assert_eq!(contract.runtime_style, "modern-systemjs");
            assert_eq!(contract.main_entry.as_deref(), Some("game.js"));
            let source = contract
                .main_entry_source
                .as_ref()
                .expect("modern chain should inject application module source");
            assert!(source.contains("System.register"));
            assert!(source.contains("System.import('./application.js')"));

            let mut game = Game::new();
            game.init();
            let bootstrap_result = game.start_with_bootstrap(&contract);
            assert!(
                bootstrap_result.is_ok(),
                "mock runtime should accept modern chain in {:?}",
                demo_path.file_name()
            );
        }

        let crate_dir = env!("CARGO_MANIFEST_DIR");
        let demos = [
            Path::new(crate_dir).join("../game-demo").join("1000006_1.9.4.zip"),
            Path::new(crate_dir).join("../game-demo").join("1000007_1.9.4.zip"),
        ];
        demos.iter().for_each(|demo_path| {
            assert_modern_demo_chain(demo_path);
        });
    }
}
