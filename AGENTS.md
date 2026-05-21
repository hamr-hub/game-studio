# Agents Configuration

This file documents the agents and their roles within the Game Studio project.

## Project Overview
Game Studio is a Cocos-based engine integration with a Rust native core, targeting Android.

## Structure
- `app/`: Android application layer (Java/Kotlin, Gradle).
- `native/`: Core engine logic implemented in Rust.
- `scripts/`: Utility scripts for setup and deployment.

## Agents & Sub-agents
- **Main Orchestrator**: Handles high-level task coordination and user interaction.
- **Native Expert**: Specialized in Rust development, JNI bridging, and performance optimization.
- **Android Specialist**: Manages Gradle builds, Android manifests, and UI components.

## Development Workflow
1. Modify Rust code in `native/src/`.
2. Build native libraries.
3. Sync Gradle and build the Android app.
