cmake_minimum_required(VERSION 3.10.2)
project("cocos_studio")

# Rust cargo project directory
set(RUST_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../native")

# Build output name for cdylib
set(RUST_LIB_NAME "cocos_studio")
set(RUST_TARGET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../native/target")
set(RUST_STAGED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/build/rust-libs/${ANDROID_ABI}")

if (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif (ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET "i686-linux-android")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()

if (NOT DEFINED RUST_TARGET)
    message(FATAL_ERROR "RUST_TARGET was not resolved for ABI ${ANDROID_ABI}")
endif()

set(RUST_LIB_PATH "${RUST_TARGET_DIR}/${RUST_TARGET}/release/lib${RUST_LIB_NAME}.so")
set(RUST_STAGED_LIB_PATH "${RUST_STAGED_DIR}/lib${RUST_LIB_NAME}.so")

add_custom_command(
    OUTPUT "${RUST_STAGED_LIB_PATH}"
    WORKING_DIRECTORY "${RUST_SOURCE_DIR}"
    DEPENDS "${RUST_SOURCE_DIR}/Cargo.toml"
    COMMAND cargo build --manifest-path Cargo.toml --target ${RUST_TARGET} --release
    COMMAND ${CMAKE_COMMAND} -E make_directory "${RUST_STAGED_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${RUST_LIB_PATH}" "${RUST_STAGED_LIB_PATH}"
    COMMENT "Stage Rust native library for ABI ${ANDROID_ABI}: ${RUST_STAGED_LIB_PATH}"
)

add_custom_target(${RUST_LIB_NAME}_stage ALL
    DEPENDS "${RUST_STAGED_LIB_PATH}"
)

add_library(${RUST_LIB_NAME} SHARED IMPORTED)
set_target_properties(${RUST_LIB_NAME} PROPERTIES
    IMPORTED_LOCATION "${RUST_STAGED_LIB_PATH}"
    IMPORTED_NO_SONAME TRUE
)
add_dependencies(${RUST_LIB_NAME} ${RUST_LIB_NAME}_stage)
