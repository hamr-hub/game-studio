cmake_minimum_required(VERSION 3.10.2)
project("cocos_studio")

# Add the Rust library
# We expect the .so to be built by cargo and placed in a predictable location
# Or we can use a custom command to run cargo

set(RUST_LIB_NAME "cocos_studio_native")
set(RUST_TARGET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../native/target")

if (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif (ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET "i686-linux-android")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
endif()

add_library(${RUST_LIB_NAME} SHARED IMPORTED)
set_target_properties(${RUST_LIB_NAME} PROPERTIES IMPORTED_LOCATION
    "${RUST_TARGET_DIR}/${RUST_TARGET}/release/lib${RUST_LIB_NAME}.so")

# For now, we just define the target. In a real build, we'd add a custom command to run cargo build.
