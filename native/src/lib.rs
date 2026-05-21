use jni::objects::{JClass, JString, JObject};
use jni::sys::{jlong, jint};
use jni::JNIEnv;
use std::sync::{Arc, Mutex};
use lazy_static::lazy_static;
use log::{info, error, debug};
use android_logger::Config;

use cocos4_rust::application::{AppManager, AppConfig};
use cocos4_rust::game::Game;

struct EngineInstance {
    app_manager: AppManager,
    game: Game,
}

lazy_static! {
    static ref ENGINE: Mutex<Option<EngineInstance>> = Mutex::new(None);
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    surface: JObject,
    game_path: JString,
) -> jlong {
    android_logger::init_once(Config::default().with_tag("CocosRustEngine"));
    info!("Initializing Cocos4-Rust Native Engine...");

    let game_path: String = env.get_string(&game_path).expect("Couldn't get java string!").into();
    info!("Loading game from: {}", game_path);
    
    // Initialize Cocos4-Rust engine
    let config = AppConfig::default();
    let mut app_manager = AppManager::new(config);
    let mut game = Game::new();
    
    game.init();
    app_manager.start();
    
    let instance = EngineInstance {
        app_manager,
        game,
    };
    
    let mut engine = ENGINE.lock().unwrap();
    *engine = Some(instance);
    
    info!("Engine initialization complete.");
    1
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
    width: jint,
    height: jint,
) {
    debug!("Native resize: {}x{}", width, height);
    if let Some(_instance) = ENGINE.lock().unwrap().as_mut() {
        // Handle resize
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) {
    info!("Destroying native engine...");
    let mut engine = ENGINE.lock().unwrap();
    if let Some(mut instance) = engine.take() {
        instance.app_manager.stop();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativePause(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) {
    info!("Engine pause");
    if let Some(instance) = ENGINE.lock().unwrap().as_mut() {
        instance.app_manager.pause();
        instance.game.pause();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cocos_gamestudio_NativeEngine_nativeResume(
    _env: JNIEnv,
    _class: JClass,
    _handle: jlong,
) {
    info!("Engine resume");
    if let Some(instance) = ENGINE.lock().unwrap().as_mut() {
        instance.app_manager.resume();
        instance.game.resume();
    }
}
