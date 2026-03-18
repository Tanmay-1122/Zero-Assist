use crate::channels::traits::{Channel, ChannelMessage, SendMessage};
use anyhow::{Context, Result};
use async_trait::async_trait;
use reqwest::Client;
use std::collections::HashMap;

/// Twilio channel (SMS/WhatsApp)
pub struct TwilioChannel {
    client: Client,
    account_sid: String,
    auth_token: String,
    from_phone: String,
    allowed_numbers: Vec<String>,
}

impl TwilioChannel {
    pub fn new(
        account_sid: String,
        auth_token: String,
        from_phone: String,
        allowed_numbers: Vec<String>,
    ) -> Self {
        Self {
            client: Client::new(),
            account_sid,
            auth_token,
            from_phone,
            allowed_numbers,
        }
    }

    /// Parse webhook payload from form data
    pub fn parse_webhook_payload(&self, payload: &HashMap<String, String>) -> Vec<ChannelMessage> {
        let from = payload.get("From").map(|s| s.clone()).unwrap_or_default();
        let body = payload.get("Body").map(|s| s.clone()).unwrap_or_default();
        let message_sid = payload.get("MessageSid").map(|s| s.clone()).unwrap_or_default();

        if from.is_empty() || body.is_empty() {
            return vec![];
        }

        // Check if the sender is allowed (if allowed_numbers is not empty)
        if !self.allowed_numbers.is_empty() && !self.allowed_numbers.contains(&from) {
            tracing::warn!("Twilio: rejected message from unauthorized number: {}", from);
            return vec![];
        }

        vec![ChannelMessage {
            id: message_sid.clone(),
            sender: from.clone(),
            reply_target: from.clone(),
            content: body,
            channel: "twilio".to_string(),
            timestamp: chrono::Utc::now().timestamp() as u64,
            thread_ts: None,
        }]
    }
}

#[async_trait]
impl Channel for TwilioChannel {
    fn name(&self) -> &str {
        "twilio"
    }

    async fn send(&self, msg: &SendMessage) -> Result<()> {
        let to = &msg.recipient;

        let url = format!(
            "https://api.twilio.com/2010-04-01/Accounts/{}/Messages.json",
            self.account_sid
        );

        let mut params = HashMap::new();
        params.insert("To", to.as_str());
        params.insert("From", self.from_phone.as_str());
        params.insert("Body", msg.content.as_str());

        let res = self.client
            .post(&url)
            .basic_auth(&self.account_sid, Some(&self.auth_token))
            .form(&params)
            .send()
            .await
            .context("Failed to send Twilio message")?;

        if !res.status().is_success() {
            let error_body = res.text().await.unwrap_or_else(|_| "Unknown error".to_string());
            tracing::error!("Twilio API error: {}", error_body);
            return Err(anyhow::anyhow!("Twilio API returned error: {}", error_body));
        }

        Ok(())
    }

    async fn listen(&self, _tx: tokio::sync::mpsc::Sender<ChannelMessage>) -> Result<()> {
        // Webhook-based channel, so listen does nothing in this context since it receives messages via HTTP.
        // The core HTTP server will invoke `parse_webhook_payload` and pass messages to the channel.
        std::future::pending::<()>().await;
        Ok(())
    }
}
