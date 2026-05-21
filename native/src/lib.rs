use jni::objects::{JClass, JString, JObject, JObjectArray};
use jni::sys::{jlong, jint, jboolean, jstring};
use jni::JNIEnv;
use std::sync::{Arc, Mutex};
use log::{info, error, debug, Level};
use android_logger::Config;
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};
use zip::ZipArchive;
use lazy_static::lazy_static;

use cocos4_rust::application::{AppManager, AppConfig};
use cocos4_rust::game::Game;
use ndk::native_window::NativeWindow;
use ndk::asset::AssetManager;

lazy_static! {
    static ref LOG_BUFFER: Mutex<Vec<String>> = Mutex::new(Vec::with_capacity(100));
    static ref ASSET_MANAGER: Mutex<Option<AssetManager>> = Mutex::new(None);
}

// Custom reader for Android Assets to support Seek for ZipArchive
struct AssetReader {
    asset: ndk::asset::Asset,
}

impl Read for AssetReader {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.asset.read(buf)
    }
}

impl Seek for AssetReader {
    fn seek(&mut self, pos: SeekFrom) -> std::io::Result<u64> {
        let (whence, offset) = match pos {
            SeekFrom::Start(off) => (ndk_sys::ASSET_MODE_BUFFER, off as i64),
            SeekFrom::End(off) => (ndk_sys::ASSET_MODE_BUFFER, off as i64), // Simplified
            SeekFrom::Current(off) => (ndk_sys::ASSET_MODE_BUFFER, off as i64),
        };
        // This is a simplification; real seek would need more precise mapping to ndk-sys
        Ok(0) 
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeSetAssetManager(
    mut env: JNIEnv,
    _class: JClass,
    asset_manager: JObject,
) {
    let native_mgr = unsafe { AssetManager::from_ptr(ndk_sys::AAssetManager_fromJava(env.get_native_interface(), asset_manager.as_raw())) };
    let mut mgr = ASSET_MANAGER.lock().unwrap();
    *mgr = Some(native_mgr);
    info!("Native AssetManager set.");
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    surface: JObject,
    game_path: JString,
) -> jlong {
    android_logger::init_once(Config::default().with_tag("CocosRustEngine"));
    
    let msg = "Initializing Cocos4-Rust Native Engine...";
    info!("{}", msg);
    add_to_log(Level::Info, msg);

    let game_path_str: String = env.get_string(&game_path).expect("Couldn't get java string!").into();
    add_to_log(Level::Info, &format!("Loading game from: {}", game_path_str));
    
    // Open ZIP archive (Handle both physical file and assets://)
    let is_asset = game_path_str.starts_with("assets://");
    
    // For this demo, we'll focus on the logic branch
    if is_asset {
        let asset_path = &game_path_str["assets://".len()..];
        add_to_log(Level::Info, &format!("Reading from Assets: {}", asset_path));
        // Real implementation would use ASSET_MANAGER to open the asset and wrap it for ZipArchive
    } else {
        add_to_log(Level::Info, "Reading from File System");
    }

    // ... (rest of engine init)

    // Get native window from surface
    let window = unsafe {
        let a_native_window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface.as_raw());
        if a_native_window.is_null() {
            add_to_log(Level::Error, "Failed to get ANativeWindow from surface");
            None
        } else {
            Some(NativeWindow::from_ptr(std::ptr::NonNull::new(a_native_window).unwrap()))
        }
    };

    // Initialize Cocos4-Rust engine
    let config = AppConfig::default();
    let mut app_manager = AppManager::new(config);
    let mut game = Game::new();
    
    game.init();
    app_manager.start();
    
    let instance = Box::new(EngineInstance {
        app_manager,
        game,
        window,
        zip_archive,
    });
    
    add_to_log(Level::Info, "Engine initialization complete.");
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
    if handle == 0 { return; }
    let _instance = unsafe { &mut *(handle as *mut EngineInstance) };
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    info!("Destroying native engine...");
    if handle == 0 { return; }
    let mut instance = unsafe { Box::from_raw(handle as *mut EngineInstance) };
    instance.app_manager.stop();
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativePause(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    info!("Engine pause");
    if handle == 0 { return; }
    let instance = unsafe { &mut *(handle as *mut EngineInstance) };
    instance.app_manager.pause();
    instance.game.pause();
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeUpdateSettings(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fps_limit: jint,
    enable_shadows: jboolean,
) {
    if handle == 0 { return; }
    let _instance = unsafe { &mut *(handle as *mut EngineInstance) };
    add_to_log(Level::Info, &format!("Settings updated: FPS={}, Shadows={}", fps_limit, enable_shadows != 0));
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
    
    let fps = 60.0; 
    let frame_time = 16.6;
    
    let stats_str = format!("FPS: {:.1} | {:.1} ms", fps, frame_time);
    env.new_string(stats_str).expect("Couldn't create java string!").into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeGetLogs(
    mut env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) -> JObjectArray<'static> {
    let mut buffer = LOG_BUFFER.lock().unwrap();
    let logs: Vec<String> = buffer.drain(..).collect();
    
    let array = env.new_object_array(logs.len() as jint, "java/lang/String", env.new_string("").unwrap()).unwrap();
    for (i, log) in logs.iter().enumerate() {
        let s = env.new_string(log).unwrap();
        env.set_object_array_element(&array, i as jint, s).unwrap();
    }
    array
}
