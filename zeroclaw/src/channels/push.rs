use crate::channels::traits::{Channel, ChannelMessage, SendMessage};
use crate::config::schema::PushConfig;
use async_trait::async_trait;
use reqwest::Client;
use std::sync::Arc;
use tokio::sync::mpsc::Sender;
use tracing::{debug, error, info};

/// Generic push notification channel supporting ntfy, Pushover, and Gotify.
/// Currently outgoing-only.
pub struct PushChannel {
    config: PushConfig,
    client: Client,
}

impl PushChannel {
    pub fn new(config: PushConfig) -> Self {
        Self {
            config,
            client: Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
        }
    }

    async fn send_ntfy(&self, message: &SendMessage) -> anyhow::Result<()> {
        if let Some(ref url) = self.config.ntfy_url {
            debug!("Sending ntfy notification to {}", url);
            let mut request = self.client.post(url).body(message.content.clone());
            
            if let Some(ref subject) = message.subject {
                request = request.header("Title", subject);
            }
            
            let res = request.send().await?;
            if !res.status().is_success() {
                let status = res.status();
                let body = res.text().await.unwrap_or_default();
                error!("Failed to send ntfy notification: {} - {}", status, body);
                return Err(anyhow::anyhow!("ntfy failed: {} ({})", status, body));
            }
        }
        Ok(())
    }

    async fn send_pushover(&self, message: &SendMessage) -> anyhow::Result<()> {
        if let (Some(ref token), Some(ref user)) = (&self.config.pushover_token, &self.config.pushover_user) {
            debug!("Sending Pushover notification");
            let mut params = vec![
                ("token", token.as_str()),
                ("user", user.as_str()),
                ("message", message.content.as_str()),
            ];
            
            if let Some(ref subject) = message.subject {
                params.push(("title", subject.as_str()));
            }

            let res = self.client
                .post("https://api.pushover.net/1/messages.json")
                .form(&params)
                .send()
                .await?;
                
            if !res.status().is_success() {
                let status = res.status();
                let body = res.text().await.unwrap_or_default();
                error!("Failed to send Pushover notification: {} - {}", status, body);
                return Err(anyhow::anyhow!("Pushover failed: {} ({})", status, body));
            }
        }
        Ok(())
    }

    async fn send_gotify(&self, message: &SendMessage) -> anyhow::Result<()> {
        if let (Some(ref url), Some(ref token)) = (&self.config.gotify_url, &self.config.gotify_token) {
            debug!("Sending Gotify notification to {}", url);
            let endpoint = format!("{}/message?token={}", url.trim_end_matches('/'), token);
            
            let mut body = serde_json::json!({
                "message": message.content,
                "priority": 5,
            });
            
            if let Some(ref subject) = message.subject {
                body["title"] = serde_json::Value::String(subject.clone());
            }

            let res = self.client
                .post(&endpoint)
                .json(&body)
                .send()
                .await?;
                
            if !res.status().is_success() {
                let status = res.status();
                let body = res.text().await.unwrap_or_default();
                error!("Failed to send Gotify notification: {} - {}", status, body);
                return Err(anyhow::anyhow!("Gotify failed: {} ({})", status, body));
            }
        }
        Ok(())
    }
}

#[async_trait]
impl Channel for PushChannel {
    fn name(&self) -> &str {
        "push"
    }

    async fn send(&self, message: &SendMessage) -> anyhow::Result<()> {
        let mut errors = Vec::new();

        if self.config.ntfy_url.is_some() {
            if let Err(e) = self.send_ntfy(message).await {
                errors.push(format!("ntfy: {}", e));
            }
        }
        if self.config.pushover_token.is_some() {
            if let Err(e) = self.send_pushover(message).await {
                errors.push(format!("Pushover: {}", e));
            }
        }
        if self.config.gotify_url.is_some() {
            if let Err(e) = self.send_gotify(message).await {
                errors.push(format!("Gotify: {}", e));
            }
        }

        if !errors.is_empty() {
            return Err(anyhow::anyhow!("Push notification error(s): {}", errors.join("; ")));
        }

        Ok(())
    }

    async fn listen(&self, _tx: Sender<ChannelMessage>) -> anyhow::Result<()> {
        // Outgoing only in this iteration.
        // We stay alive to satisfy the runtime but do not produce messages.
        info!("Push notification channel started (outgoing-only)");
        std::future::pending::<()>().await;
        Ok(())
    }

    async fn health_check(&self) -> bool {
        // Simple reachability check for base URLs could be added here.
        true
    }
}
