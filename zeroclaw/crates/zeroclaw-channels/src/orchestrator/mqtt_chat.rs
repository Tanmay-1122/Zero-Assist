//! Bidirectional MQTT AI chat channel.
//!
//! Implements the `Channel` trait to allow any MQTT-capable device to send
//! requests to the Zero-Assist agent and receive AI-generated responses.
//!
//! # Protocol
//!
//! Devices publish JSON or plain-text requests to a request topic and
//! subscribe to a per-device response topic:
//!
//! ```text
//! Request:  zeroassist/devices/{device_id}/request
//! Response: zeroassist/devices/{device_id}/response
//! ```
//!
//! # JSON payload format
//!
//! ```json
//! {
//!   "version": 1,
//!   "message_id": "abc123",
//!   "conversation_id": "default",
//!   "type": "text",
//!   "content": "Hello Zero-Assist"
//! }
//! ```
//!
//! # Plain-text mode
//!
//! Any non-JSON payload is treated as `type = "text"` with `conversation_id = "default"`.

use std::sync::Arc;

use async_trait::async_trait;
use rumqttc::{AsyncClient, Event, MqttOptions, Packet, QoS, Transport};
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, Mutex};
use tracing::{info, warn};

use zeroclaw_api::channel::{Channel, ChannelMessage, SendMessage};
use zeroclaw_config::schema::MqttConfig;

/// Maximum message ID length to prevent abuse.
const MAX_MESSAGE_ID_LEN: usize = 128;
/// Maximum conversation ID length.
const MAX_CONVERSATION_ID_LEN: usize = 128;
/// Maximum device ID length (from topic parsing).
const MAX_DEVICE_ID_LEN: usize = 128;
/// Maximum content length.
const MAX_CONTENT_LEN: usize = 256 * 1024;

/// Parsed incoming MQTT chat request.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MqttChatRequest {
    /// Protocol version (currently 1).
    #[serde(default = "default_version")]
    pub version: u32,
    /// Client-generated message ID for deduplication and reply correlation.
    #[serde(default)]
    pub message_id: String,
    /// Conversation ID for session persistence (default: "default").
    #[serde(default = "default_conversation_id")]
    pub conversation_id: String,
    /// Message type (currently only "text").
    #[serde(default = "default_type")]
    pub msg_type: String,
    /// The user's message content.
    pub content: String,
}

fn default_version() -> u32 {
    1
}
fn default_conversation_id() -> String {
    "default".into()
}
fn default_type() -> String {
    "text".into()
}

/// Outgoing MQTT chat response.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[allow(dead_code)]
pub struct MqttChatResponse {
    /// Protocol version.
    pub version: u32,
    /// Server-generated response message ID.
    pub message_id: String,
    /// Correlates to the request's `message_id`.
    pub reply_to: String,
    /// Conversation ID from the request.
    pub conversation_id: String,
    /// Message type.
    #[serde(rename = "type")]
    pub msg_type: String,
    /// The AI-generated response content.
    pub content: String,
}

/// Bidirectional MQTT AI chat channel.
///
/// Connects to an external MQTT broker, subscribes to a wildcard request
/// topic, routes incoming messages through the Zero-Assist agent pipeline,
/// and publishes responses to per-device response topics.
pub struct MqttChatChannel {
    config: MqttConfig,
    /// Shared MQTT client for publishing responses.
    client: Arc<Mutex<Option<AsyncClient>>>,
}

impl MqttChatChannel {
    pub fn new(config: MqttConfig) -> Self {
        Self {
            config,
            client: Arc::new(Mutex::new(None)),
        }
    }

    /// Parse an incoming MQTT topic to extract the device ID.
    ///
    /// Expects topic format: `zeroassist/devices/{device_id}/request`
    /// Returns `None` if the topic doesn't match the expected pattern.
    fn extract_device_id(topic: &str) -> Option<String> {
        let parts: Vec<&str> = topic.split('/').collect();
        // Expected: ["zeroassist", "devices", "{device_id}", "request"]
        if parts.len() >= 4
            && parts[0] == "zeroassist"
            && parts[1] == "devices"
            && parts[3] == "request"
        {
            let device_id = parts[2];
            if !device_id.is_empty() && device_id.len() <= MAX_DEVICE_ID_LEN {
                // Validate: no path traversal characters
                if !device_id.contains("..")
                    && !device_id.contains('/')
                    && !device_id.contains('\\')
                {
                    return Some(device_id.to_string());
                }
            }
        }
        None
    }

    /// Build the response topic from the template and device ID.
    fn response_topic(&self, device_id: &str) -> String {
        self.config
            .chat_response_topic
            .replace("{device_id}", device_id)
    }

    /// Parse an incoming MQTT payload as a chat request.
    ///
    /// Supports both JSON and plain-text modes.
    fn parse_request(
        &self,
        payload: &str,
        topic: &str,
    ) -> Option<MqttChatRequest> {
        let trimmed = payload.trim();
        if trimmed.is_empty() {
            return None;
        }

        // Size check
        if trimmed.len() > self.config.chat_max_payload_bytes {
            warn!(
                "MQTT chat: payload too large ({} bytes, max {})",
                trimmed.len(),
                self.config.chat_max_payload_bytes
            );
            return None;
        }

        let mode = self.config.chat_payload_mode.as_str();

        // Try JSON parsing first in "json" or "auto" modes
        if mode == "json" || mode == "auto" {
            if let Ok(req) = serde_json::from_str::<MqttChatRequest>(trimmed) {
                // Validate content length
                if req.content.len() > MAX_CONTENT_LEN {
                    warn!("MQTT chat: content too large ({} bytes)", req.content.len());
                    return None;
                }
                return Some(req);
            } else if mode == "json" {
                // Explicit JSON mode but failed to parse
                warn!("MQTT chat: invalid JSON payload on topic {topic}");
                return None;
            }
        }

        // Plain-text fallback: treat entire payload as text content
        if trimmed.len() > MAX_CONTENT_LEN {
            warn!("MQTT chat: text content too large ({} bytes)", trimmed.len());
            return None;
        }

        Some(MqttChatRequest {
            version: 1,
            message_id: format!(
                "mqtt_{}",
                chrono::Utc::now().timestamp_millis()
            ),
            conversation_id: "default".into(),
            msg_type: "text".into(),
            content: trimmed.to_string(),
        })
    }

}

#[async_trait]
impl Channel for MqttChatChannel {
    fn name(&self) -> &str {
        "mqtt"
    }

    async fn send(&self, message: &SendMessage) -> anyhow::Result<()> {
        let guard = self.client.lock().await;
        let client = guard
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("MQTT not connected"))?;

        // The recipient is the response topic
        let topic = &message.recipient;
        let payload = message.content.as_bytes();

        let qos = match self.config.qos {
            0 => QoS::AtMostOnce,
            1 => QoS::AtLeastOnce,
            _ => QoS::ExactlyOnce,
        };

        client.publish(topic, qos, false, payload).await?;
        info!("MQTT chat: published response to {topic}");
        Ok(())
    }

    async fn listen(&self, tx: mpsc::Sender<ChannelMessage>) -> anyhow::Result<()> {
        self.config.validate()?;

        let mut mqtt_options = MqttOptions::new(
            &self.config.client_id,
            self.config.broker_host(),
            self.config.broker_port(),
        );
        mqtt_options
            .set_keep_alive(std::time::Duration::from_secs(self.config.keep_alive_secs));

        if let (Some(user), Some(pass)) = (&self.config.username, &self.config.password) {
            mqtt_options.set_credentials(user, pass);
        }

        if self.config.use_tls {
            mqtt_options.set_transport(Transport::tls_with_default_config());
            info!("MQTT chat: TLS transport enabled");
        }

        let (client, mut eventloop) = AsyncClient::new(mqtt_options, 64);

        // Store client for send()
        {
            let mut guard = self.client.lock().await;
            *guard = Some(client.clone());
        }

        let qos = match self.config.qos {
            0 => QoS::AtMostOnce,
            1 => QoS::AtLeastOnce,
            _ => QoS::ExactlyOnce,
        };

        // Subscribe to the chat request topic (wildcard for all devices)
        client
            .subscribe(&self.config.chat_request_topic, qos)
            .await?;
        info!(
            "MQTT chat: subscribed to '{}'",
            self.config.chat_request_topic
        );

        // Also subscribe to SOP automation topics if configured
        for topic in &self.config.topics {
            client.subscribe(topic, qos).await?;
            info!("MQTT chat: subscribed to SOP topic '{topic}'");
        }

        zeroclaw_runtime::health::mark_component_ok("mqtt_chat");

        // Event loop
        loop {
            match eventloop.poll().await {
                Ok(Event::Incoming(Packet::Publish(msg))) => {
                    let topic = msg.topic.clone();
                    let payload = String::from_utf8_lossy(&msg.payload).to_string();

                    // Determine if this is a chat request or SOP event
                    if let Some(device_id) = Self::extract_device_id(&topic) {
                        // Chat request
                        self.handle_chat_request(
                            &tx, &topic, &payload, &device_id,
                        )
                        .await;
                    } else {
                        // SOP automation topic — skip (handled by mqtt.rs SOP listener)
                        tracing::debug!(
                            "MQTT chat: non-chat message on topic '{topic}', skipping"
                        );
                    }
                }
                Ok(Event::Incoming(Packet::ConnAck(_))) => {
                    zeroclaw_runtime::health::mark_component_ok("mqtt_chat");
                    info!("MQTT chat: connected to broker");
                }
                Ok(_) => {
                    // Other events (PingResp, SubAck, etc.)
                }
                Err(e) => {
                    zeroclaw_runtime::health::mark_component_error(
                        "mqtt_chat",
                        e.to_string(),
                    );
                    warn!("MQTT chat: connection error: {e}");
                    // rumqttc handles auto-reconnect; loop continues
                }
            }
        }
    }

    async fn health_check(&self) -> bool {
        let guard = self.client.lock().await;
        guard.is_some()
    }
}

impl MqttChatChannel {
    /// Handle an incoming chat request from an MQTT device.
    async fn handle_chat_request(
        &self,
        tx: &mpsc::Sender<ChannelMessage>,
        topic: &str,
        payload: &str,
        device_id: &str,
    ) {
        let Some(request) = self.parse_request(payload, topic) else {
            warn!("MQTT chat: failed to parse request from topic '{topic}'");
            return;
        };

        // Validate IDs
        if request.message_id.len() > MAX_MESSAGE_ID_LEN {
            warn!("MQTT chat: message_id too long");
            return;
        }
        if request.conversation_id.len() > MAX_CONVERSATION_ID_LEN {
            warn!("MQTT chat: conversation_id too long");
            return;
        }
        if request.content.is_empty() {
            return;
        }

        // Build response topic for this device
        let response_topic = self.response_topic(device_id);

        // The session key uses the response topic as reply_target,
        // ensuring per-device, per-conversation isolation.
        let channel_msg = ChannelMessage {
            id: format!(
                "mqtt_{}_{}",
                chrono::Utc::now().timestamp_millis(),
                &request.message_id[..request.message_id.len().min(16)]
            ),
            sender: device_id.to_string(),
            reply_target: response_topic,
            content: request.content,
            channel: "mqtt".to_string(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
            thread_ts: None,
            interruption_scope_id: None,
            attachments: vec![],
        };

        if tx.send(channel_msg).await.is_err() {
            info!("MQTT chat: dispatch channel closed, shutting down listener");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn default_config() -> MqttConfig {
        MqttConfig {
            enabled: true,
            broker_url: "mqtt://localhost:1883".into(),
            client_id: "test-chat".into(),
            topics: vec!["sensors/#".into()],
            qos: 1,
            username: None,
            password: None,
            use_tls: false,
            keep_alive_secs: 30,
            chat_enabled: true,
            chat_request_topic: "zeroassist/devices/+/request".into(),
            chat_response_topic: "zeroassist/devices/{device_id}/response".into(),
            chat_payload_mode: "auto".into(),
            chat_max_payload_bytes: 65536,
        }
    }

    #[test]
    fn extract_device_id_from_valid_topic() {
        assert_eq!(
            MqttChatChannel::extract_device_id("zeroassist/devices/esp32-terminal/request"),
            Some("esp32-terminal".into())
        );
        assert_eq!(
            MqttChatChannel::extract_device_id("zeroassist/devices/rpi-01/request"),
            Some("rpi-01".into())
        );
        assert_eq!(
            MqttChatChannel::extract_device_id("zeroassist/devices/kitchen-display/request"),
            Some("kitchen-display".into())
        );
    }

    #[test]
    fn extract_device_id_rejects_invalid_topics() {
        assert_eq!(
            MqttChatChannel::extract_device_id("sensors/temperature"),
            None
        );
        assert_eq!(
            MqttChatChannel::extract_device_id("zeroassist/devices/esp32/response"),
            None
        );
        assert_eq!(
            MqttChatChannel::extract_device_id("other/devices/esp32/request"),
            None
        );
        assert_eq!(
            MqttChatChannel::extract_device_id("zeroassist/devices//request"),
            None
        );
        assert_eq!(
            MqttChatChannel::extract_device_id("zeroassist/devices/../etc/passwd/request"),
            None
        );
    }

    #[test]
    fn response_topic_generation() {
        let ch = MqttChatChannel::new(default_config());
        assert_eq!(
            ch.response_topic("esp32-terminal"),
            "zeroassist/devices/esp32-terminal/response"
        );
    }

    #[test]
    fn parse_json_request() {
        let ch = MqttChatChannel::new(default_config());
        let json = r#"{"version":1,"message_id":"abc","conversation_id":"conv1","type":"text","content":"Hello"}"#;
        let req = ch.parse_request(json, "zeroassist/devices/dev1/request").unwrap();
        assert_eq!(req.message_id, "abc");
        assert_eq!(req.conversation_id, "conv1");
        assert_eq!(req.content, "Hello");
    }

    #[test]
    fn parse_plain_text_request() {
        let ch = MqttChatChannel::new(default_config());
        let req = ch
            .parse_request("What time is it?", "zeroassist/devices/dev1/request")
            .unwrap();
        assert_eq!(req.content, "What time is it?");
        assert_eq!(req.conversation_id, "default");
    }

    #[test]
    fn parse_empty_payload_returns_none() {
        let ch = MqttChatChannel::new(default_config());
        assert!(ch.parse_request("", "zeroassist/devices/dev1/request").is_none());
        assert!(ch.parse_request("   ", "zeroassist/devices/dev1/request").is_none());
    }

    #[test]
    fn parse_oversized_payload_returns_none() {
        let mut cfg = default_config();
        cfg.chat_max_payload_bytes = 10;
        let ch = MqttChatChannel::new(cfg);
        assert!(
            ch.parse_request("this is definitely more than ten bytes", "zeroassist/devices/dev1/request")
                .is_none()
        );
    }

    #[test]
    fn json_mode_rejects_invalid_json() {
        let mut cfg = default_config();
        cfg.chat_payload_mode = "json".into();
        let ch = MqttChatChannel::new(cfg);
        assert!(
            ch.parse_request("not json at all", "zeroassist/devices/dev1/request").is_none()
        );
    }

    #[test]
    fn text_mode_ignores_json() {
        let mut cfg = default_config();
        cfg.chat_payload_mode = "text".into();
        let ch = MqttChatChannel::new(cfg);
        let json = r#"{"version":1,"content":"Hello"}"#;
        let req = ch.parse_request(json, "zeroassist/devices/dev1/request").unwrap();
        // In text mode, JSON is treated as plain text
        assert_eq!(req.content, json);
    }

    #[test]
    fn config_validation_rejects_empty_chat_topic() {
        let mut cfg = default_config();
        cfg.chat_request_topic = "".into();
        assert!(cfg.validate().is_err());
    }

    #[test]
    fn config_validation_rejects_bad_payload_mode() {
        let mut cfg = default_config();
        cfg.chat_payload_mode = "xml".into();
        assert!(cfg.validate().is_err());
    }

    #[test]
    fn config_validation_passes_with_defaults() {
        assert!(default_config().validate().is_ok());
    }

    #[test]
    fn config_validation_skips_chat_when_disabled() {
        let mut cfg = default_config();
        cfg.chat_enabled = false;
        cfg.chat_request_topic = "".into();
        // Should pass — chat validation only runs when chat_enabled
        assert!(cfg.validate().is_ok());
    }

    #[test]
    fn broker_host_and_port() {
        let mut cfg = default_config();
        cfg.broker_url = "mqtt://192.168.1.100:1883".into();
        assert_eq!(cfg.broker_host(), "192.168.1.100");
        assert_eq!(cfg.broker_port(), 1883);

        cfg.broker_url = "mqtts://broker.example.com:8883".into();
        assert_eq!(cfg.broker_host(), "broker.example.com");
        assert_eq!(cfg.broker_port(), 8883);

        cfg.broker_url = "mqtt://localhost".into();
        assert_eq!(cfg.broker_host(), "localhost");
        assert_eq!(cfg.broker_port(), 1883);
    }

    #[test]
    fn channel_name() {
        let ch = MqttChatChannel::new(default_config());
        assert_eq!(ch.name(), "mqtt");
    }
}
