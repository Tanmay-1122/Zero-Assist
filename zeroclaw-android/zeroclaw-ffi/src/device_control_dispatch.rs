// Copyright (c) 2026 ZeroClaw Community. MIT License.

#![allow(clippy::unnecessary_literal_bound)]

//! Device-control tool dispatch via UniFFI callback into Kotlin.
//!
//! The `device_control` tool is registered in the Rust tools registry but
//! executes entirely in Kotlin (accessibility service + LLM-backed planner).
//! This module provides a [`DeviceControlHandler`] callback interface that
//! Kotlin implements, plus a [`dispatch`] function called from the Rust
//! tool's `execute()` method.
//!
//! Pattern matches [`crate::shared_folder`] — direct FFI callback instead
//! of a localhost HTTP bridge.

use crate::FfiError;
use std::sync::Mutex;

/// Callback interface implemented in Kotlin for device-control execution.
#[uniffi::export(callback_interface)]
pub trait DeviceControlHandler: Send + Sync {
    /// Executes a device-control goal.
    ///
    /// `params_json` contains the tool call arguments as JSON, e.g.
    /// `{"goal":"Open Settings","max_steps":30}`.
    ///
    /// Returns a JSON object with `success`, `message`/`error`, and `steps`.
    fn execute_device_control(&self, params_json: String) -> Result<String, FfiError>;
}

static HANDLER: Mutex<Option<Box<dyn DeviceControlHandler>>> = Mutex::new(None);

/// Registers the Kotlin-side device-control handler.
#[uniffi::export]
pub fn register_device_control_handler(handler: Box<dyn DeviceControlHandler>) {
    // Keep the handler alive in our global so `dispatch()` can call into it.
    {
        let mut guard = HANDLER
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        *guard = Some(handler);
    }

    // Also register a dispatch closure in the runtime tool registry so that
    // `all_tools_with_runtime()` picks up the device_control tool in the
    // channel/orchestrator path.
    zeroclaw_runtime::tools::register_device_control_dispatch(|params_json| {
        dispatch(params_json)
    });
}

/// Unregisters the device-control handler.
#[uniffi::export]
pub fn unregister_device_control_handler() {
    let mut guard = HANDLER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *guard = None;
}

/// Dispatches a device-control tool call to the registered Kotlin handler.
pub(crate) fn dispatch(params_json: &str) -> Result<String, String> {
    let guard = HANDLER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    match guard.as_ref() {
        Some(handler) => handler
            .execute_device_control(params_json.to_string())
            .map_err(|e| format!("device_control handler error: {e}")),
        None => Err(
            "device_control handler not registered. The app must call register_device_control_handler.".to_string(),
        ),
    }
}