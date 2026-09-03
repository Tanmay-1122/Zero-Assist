/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Device-control tool for the channel/runtime tool registry.
//!
//! When the Android app registers a `DeviceControlHandler` via FFI, this
//! global is populated and the tool becomes callable from messaging channels.

use async_trait::async_trait;
use std::sync::{OnceLock, atomic::{AtomicU64, Ordering}};
use super::{Tool, ToolResult};

/// Global dispatch callback, set once at FFI init time.
static DISPATCH: OnceLock<Box<dyn Fn(&str) -> Result<String, String> + Send + Sync>> =
    OnceLock::new();

/// Register the device-control dispatch callback.
///
/// Called once by the FFI layer when `register_device_control_handler` is invoked.
pub fn register_device_control_dispatch(
    handler: impl Fn(&str) -> Result<String, String> + Send + Sync + 'static,
) {
    let _ = DISPATCH.set(Box::new(handler));
}

/// Returns `true` if a device_control handler has been registered.
pub fn device_control_available() -> bool {
    DISPATCH.get().is_some()
}

static DC_REQUEST_ID: AtomicU64 = AtomicU64::new(1);

/// Device-control tool backed by the global Kotlin dispatch callback.
pub struct DeviceControlTool;

#[async_trait]
impl Tool for DeviceControlTool {
    fn name(&self) -> &'static str {
        "device_control"
    }

    fn description(&self) -> &'static str {
        "Control the Android device's screen through accessibility gestures. \
         Provide a SINGLE, COMPLETE natural-language goal and this tool will \
         automate the entire UI workflow internally.\n\n\
         COMPOUND GOALS: Send the FULL objective in ONE call. The internal \
         planner handles multi-step UI execution (app launch, navigation, \
         typing, clicking, scrolling). Do NOT decompose compound goals into \
         separate device_control calls.\n\
         GOOD: \"Open Instagram and send 'hi' to Rohit\"\n\
         BAD: First call \"open Instagram\", then second call \"message Rohit\"\n\n\
         The tool observes the screen, plans actions step-by-step using an \
         internal LLM loop, and returns a final result with success or \
         failure details.\n\n\
         IMPORTANT: The accessibility service must be enabled in Android \
         Settings -> Accessibility -> Zero-Assist. If disabled, this tool \
         will fail immediately."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "goal": {
                    "type": "string",
                    "description": "A clear, specific description of what the phone should do. \
                        For example: \"Open WhatsApp and send 'On my way' to Mom\", \
                        \"Navigate to Settings -> About Phone and read the Android version\", \
                        \"Open YouTube and search for lofi coding music\"."
                },
                "max_steps": {
                    "type": "integer",
                    "description": "Maximum automation steps (default 30, range 1-60). \
                        Use lower values for simple tasks, higher for multi-step workflows."
                }
            },
            "required": ["goal"],
            "additionalProperties": false
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let request_id = DC_REQUEST_ID.fetch_add(1, Ordering::SeqCst);
        let goal = args.get("goal").and_then(|v| v.as_str()).unwrap_or("?").to_string();
        let params_json = serde_json::to_string(&args).unwrap_or_default();

        tracing::info!(
            target: "zeroclaw::device_control",
            request_id = request_id,
            goal = %goal,
            "device_control: tool invoked"
        );

        let dispatch = match DISPATCH.get() {
            Some(d) => d,
            None => {
                tracing::warn!(
                    target: "zeroclaw::device_control",
                    request_id = request_id,
                    "device_control: no handler registered"
                );
                return Ok(ToolResult {
                    success: false,
                    output: "device_control handler not registered. \
                             The app must call register_device_control_handler."
                        .into(),
                    error: Some("No device_control handler registered.".into()),
                    blocks: Vec::new(),
                    metadata: None,
                });
            }
        };

        let started_at = std::time::Instant::now();
        let params_clone = params_json.clone();

        let dispatch_result =
            tokio::task::spawn_blocking(move || dispatch(&params_clone))
                .await
                .unwrap_or_else(|e| Err(format!("device_control task panicked: {e}")));

        let elapsed_ms = started_at.elapsed().as_millis();

        match &dispatch_result {
            Ok(response_json) => {
                let value: serde_json::Value = serde_json::from_str(response_json)
                    .unwrap_or(serde_json::json!({"success": false, "error": response_json}));
                let success = value
                    .get("success")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false);

                tracing::info!(
                    target: "zeroclaw::device_control",
                    request_id = request_id,
                    success = success,
                    latency_ms = elapsed_ms,
                    response_len = response_json.len(),
                    "device_control: completed"
                );

                let output =
                    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string());
                Ok(ToolResult {
                    success,
                    output: output.clone(),
                    error: (!success).then_some(output),
                    blocks: Vec::new(),
                    metadata: None,
                })
            }
            Err(error) => {
                tracing::warn!(
                    target: "zeroclaw::device_control",
                    request_id = request_id,
                    latency_ms = elapsed_ms,
                    error = %error,
                    "device_control: failed"
                );
                Ok(ToolResult {
                    success: false,
                    output: error.clone(),
                    error: Some(error.clone()),
                    blocks: Vec::new(),
                    metadata: None,
                })
            }
        }
    }
}
