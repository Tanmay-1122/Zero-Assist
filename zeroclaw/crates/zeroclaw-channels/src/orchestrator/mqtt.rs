//! MQTT → SOP event fan-in listener.
//!
//! This is NOT a `Channel` trait implementor — it routes MQTT messages
//! to the SOP engine via `dispatch_sop_event`, not to the chat loop.

use std::sync::{Arc, Mutex};

use anyhow::Result;
use rumqttc::{AsyncClient, Event, MqttOptions, Packet, QoS, Transport};
use tracing::{info, warn};

use zeroclaw_config::schema::MqttConfig;
use zeroclaw_runtime::sop::audit::SopAuditLogger;
use zeroclaw_runtime::sop::dispatch::{dispatch_sop_event, process_headless_results};
use zeroclaw_runtime::sop::engine::{SopEngine, now_iso8601};
use zeroclaw_runtime::sop::types::{SopEvent, SopTriggerSource};

/// Run the MQTT SOP listener loop.
///
/// Subscribes to configured topics and dispatches incoming publishes
/// to the SOP engine. Blocks until disconnected or cancelled.
pub async fn run_mqtt_sop_listener(
    config: &MqttConfig,
    engine: Arc<Mutex<SopEngine>>,
    audit: Arc<SopAuditLogger>,
) -> Result<()> {
    config.validate()?;

    let mut mqtt_options = MqttOptions::new(
        &config.client_id,
        config.broker_host(),
        config.broker_port(),
    );
    mqtt_options.set_keep_alive(std::time::Duration::from_secs(config.keep_alive_secs));

    if let (Some(user), Some(pass)) = (&config.username, &config.password) {
        mqtt_options.set_credentials(user, pass);
    }

    // Configure TLS transport when mqtts:// scheme is used
    if config.use_tls {
        mqtt_options.set_transport(Transport::tls_with_default_config());
        info!("MQTT SOP listener: TLS transport enabled");
    }

    let (client, mut eventloop) = AsyncClient::new(mqtt_options, 64);

    let qos = match config.qos {
        0 => QoS::AtMostOnce,
        1 => QoS::AtLeastOnce,
        _ => QoS::ExactlyOnce,
    };

    // Subscribe to all configured topics
    for topic in &config.topics {
        client.subscribe(topic, qos).await?;
        info!("MQTT SOP listener: subscribed to '{topic}'");
    }

    zeroclaw_runtime::health::mark_component_ok("mqtt");

    loop {
        match eventloop.poll().await {
            Ok(Event::Incoming(Packet::Publish(msg))) => {
                let topic = msg.topic.clone();
                let payload = String::from_utf8_lossy(&msg.payload).to_string();

                let event = SopEvent {
                    source: SopTriggerSource::Mqtt,
                    topic: Some(topic),
                    payload: Some(payload),
                    timestamp: now_iso8601(),
                };

                let results = dispatch_sop_event(&engine, &audit, event).await;
                process_headless_results(&results);
            }
            Ok(Event::Incoming(Packet::ConnAck(_))) => {
                zeroclaw_runtime::health::mark_component_ok("mqtt");
                info!("MQTT SOP listener: connected to broker");
            }
            Ok(_) => {
                // Other events (PingResp, SubAck, etc.) — ignore
            }
            Err(e) => {
                zeroclaw_runtime::health::mark_component_error("mqtt", e.to_string());
                warn!("MQTT SOP listener: connection error: {e}");
                // rumqttc handles auto-reconnect; loop continues
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_config(broker_url: &str, client_id: &str, topics: Vec<String>, qos: u8) -> MqttConfig {
        MqttConfig {
            enabled: true,
            broker_url: broker_url.into(),
            client_id: client_id.into(),
            topics,
            qos,
            username: None,
            password: None,
            use_tls: false,
            keep_alive_secs: 30,
            chat_enabled: false,
            chat_request_topic: "zeroassist/devices/+/request".into(),
            chat_response_topic: "zeroassist/devices/{device_id}/response".into(),
            chat_payload_mode: "auto".into(),
            chat_max_payload_bytes: 65536,
        }
    }

    #[test]
    fn mqtt_config_validation_rejects_bad_qos() {
        let config = make_config("mqtt://localhost:1883", "zeroclaw", vec!["test".into()], 3);
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("qos must be 0, 1, or 2"));
    }

    #[test]
    fn mqtt_config_validation_rejects_bad_url() {
        let config = make_config("http://localhost:1883", "zeroclaw", vec!["test".into()], 1);
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("mqtt://"));
    }

    #[test]
    fn mqtt_config_validation_rejects_empty_topics() {
        let config = make_config("mqtt://localhost:1883", "zeroclaw", vec![], 1);
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("at least one topic"));
    }

    #[test]
    fn mqtt_config_validation_rejects_empty_client_id() {
        let config = make_config("mqtt://localhost:1883", "", vec!["test".into()], 1);
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("client_id must not be empty"));
    }

    #[test]
    fn mqtt_config_validation_accepts_valid() {
        let config = make_config("mqtt://localhost:1883", "zeroclaw", vec!["sensors/#".into()], 1);
        assert!(config.validate().is_ok());
    }

    #[test]
    fn mqtt_tls_flag_rejects_mqtt_scheme_with_use_tls() {
        let mut config = make_config("mqtt://localhost:1883", "zeroclaw", vec!["test".into()], 1);
        config.use_tls = true;
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("use_tls is true"));
    }

    #[test]
    fn mqtt_tls_flag_rejects_mqtts_scheme_without_use_tls() {
        let config = make_config("mqtts://localhost:8883", "zeroclaw", vec!["test".into()], 1);
        let err = config.validate().unwrap_err();
        assert!(err.to_string().contains("mqtts://"));
    }

    #[test]
    fn mqtt_tls_flag_accepts_mqtts_with_use_tls() {
        let mut config = make_config("mqtts://localhost:8883", "zeroclaw", vec!["test".into()], 1);
        config.use_tls = true;
        assert!(config.validate().is_ok());
    }

    #[test]
    fn broker_host_extracts_host() {
        let c1 = make_config("mqtt://myhost:1883", "x", vec![], 1);
        assert_eq!(c1.broker_host(), "myhost");
        let c2 = make_config("mqtts://secure.example.com:8883", "x", vec![], 1);
        assert_eq!(c2.broker_host(), "secure.example.com");
    }

    #[test]
    fn broker_port_extracts_port() {
        let c1 = make_config("mqtt://localhost:1883", "x", vec![], 1);
        assert_eq!(c1.broker_port(), 1883);
        let c2 = make_config("mqtts://host:8883", "x", vec![], 1);
        assert_eq!(c2.broker_port(), 8883);
    }

    #[test]
    fn broker_port_defaults_1883_for_mqtt() {
        let c = make_config("mqtt://localhost", "x", vec![], 1);
        assert_eq!(c.broker_port(), 1883);
    }

    #[test]
    fn broker_port_defaults_8883_for_mqtts() {
        let c = make_config("mqtts://secure.example.com", "x", vec![], 1);
        assert_eq!(c.broker_port(), 8883);
    }
}
