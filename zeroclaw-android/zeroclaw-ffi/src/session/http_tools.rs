/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! HTTP and web-fetch tool wrappers used by Android sessions.

use std::time::Duration;

use async_trait::async_trait;
use futures_util::StreamExt;
use zeroclaw::tools::{Tool, ToolResult};

use super::fail_result;
use crate::url_helpers;

/// FFI-specific web fetch tool that bypasses `SecurityPolicy`.
///
/// Fetches a web page and converts HTML to clean plain text for LLM
/// consumption. Follows redirects (up to 10), validating each redirect
/// target against the domain allowlist and blocklist. Non-HTML content
/// types (plain text, markdown, JSON) are passed through as-is.
///
/// The FFI crate keeps this Android wrapper local because upstream
/// `SecurityPolicy` is `pub(crate)`.
pub(super) struct FfiWebFetchTool {
    /// Allowed domains for URL validation (exact or subdomain match).
    allowed_domains: Vec<String>,
    /// Blocked domains that override the allowlist.
    blocked_domains: Vec<String>,
    /// Maximum response body size in bytes before truncation.
    max_response_size: usize,
    /// HTTP request timeout in seconds (0 falls back to 30s).
    timeout_secs: u64,
}

impl FfiWebFetchTool {
    pub(super) fn new(
        allowed_domains: Vec<String>,
        blocked_domains: Vec<String>,
        max_response_size: usize,
        timeout_secs: u64,
    ) -> Self {
        Self {
            allowed_domains,
            blocked_domains,
            max_response_size,
            timeout_secs,
        }
    }

    /// Truncates text to the configured maximum size, appending a
    /// marker when content is cut off.
    fn truncate_response(&self, text: &str) -> String {
        if text.len() > self.max_response_size {
            let mut truncated = text
                .chars()
                .take(self.max_response_size)
                .collect::<String>();
            truncated.push_str("\n\n... [Response truncated due to size limit] ...");
            truncated
        } else {
            text.to_string()
        }
    }

    /// Reads the response body as a byte stream with a hard cap to
    /// prevent unbounded memory allocation from very large pages.
    async fn read_response_text_limited(
        &self,
        response: reqwest::Response,
    ) -> anyhow::Result<String> {
        let mut bytes_stream = response.bytes_stream();
        let hard_cap = self.max_response_size.saturating_add(1);
        let mut bytes = Vec::new();

        while let Some(chunk_result) = bytes_stream.next().await {
            let chunk = chunk_result?;
            let remaining = hard_cap.saturating_sub(bytes.len());
            if remaining == 0 {
                break;
            }
            if chunk.len() > remaining {
                bytes.extend_from_slice(&chunk[..remaining]);
                break;
            }
            bytes.extend_from_slice(&chunk);
        }

        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }

    /// Builds a [`reqwest::Client`] with redirect validation that
    /// checks each redirect target against the domain lists.
    fn build_client(&self) -> Result<reqwest::Client, String> {
        let timeout_secs = if self.timeout_secs == 0 {
            tracing::warn!("web_fetch: timeout_secs is 0, using safe default of 30s");
            30
        } else {
            self.timeout_secs
        };

        let allowed = self.allowed_domains.clone();
        let blocked = self.blocked_domains.clone();
        let redirect_policy = reqwest::redirect::Policy::custom(move |attempt| {
            if attempt.previous().len() >= 10 {
                return attempt.error(std::io::Error::other("Too many redirects (max 10)"));
            }
            if let Err(err) = url_helpers::validate_target_url(
                attempt.url().as_str(),
                &allowed,
                &blocked,
                "web_fetch",
            ) {
                return attempt.error(std::io::Error::new(
                    std::io::ErrorKind::PermissionDenied,
                    format!("Blocked redirect target: {err}"),
                ));
            }
            attempt.follow()
        });

        reqwest::Client::builder()
            .timeout(Duration::from_secs(timeout_secs))
            .connect_timeout(Duration::from_secs(10))
            .redirect(redirect_policy)
            .user_agent("ZeroClaw/0.1 (web_fetch)")
            .build()
            .map_err(|e| format!("Failed to build HTTP client: {e}"))
    }

    /// Determines the processing strategy for the response based on
    /// its `Content-Type` header. Returns `"html"`, `"plain"`, or an
    /// error for unsupported types.
    fn classify_content_type(response: &reqwest::Response) -> Result<&'static str, String> {
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_lowercase();

        if content_type.contains("text/html") || content_type.is_empty() {
            Ok("html")
        } else if content_type.contains("text/plain")
            || content_type.contains("text/markdown")
            || content_type.contains("application/json")
        {
            Ok("plain")
        } else {
            Err(format!(
                "Unsupported content type: {content_type}. \
                 web_fetch supports text/html, text/plain, text/markdown, \
                 and application/json."
            ))
        }
    }
}

#[async_trait]
impl Tool for FfiWebFetchTool {
    fn name(&self) -> &'static str {
        "web_fetch"
    }

    fn description(&self) -> &'static str {
        "Fetch a web page and return its content as clean plain text. \
         HTML pages are automatically converted to readable text. \
         JSON and plain text responses are returned as-is. \
         Only GET requests; follows redirects. \
         Security: allowlist-only domains, no local/private hosts."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The HTTP or HTTPS URL to fetch"
                }
            },
            "required": ["url"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let raw_url = args
            .get("url")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'url' parameter"))?;

        let url = match url_helpers::validate_target_url_with_dns(
            raw_url,
            &self.allowed_domains,
            &self.blocked_domains,
            "web_fetch",
        )
        .await
        {
            Ok(v) => v,
            Err(e) => return Ok(fail_result(e)),
        };

        let client = match self.build_client() {
            Ok(c) => c,
            Err(e) => return Ok(fail_result(e)),
        };

        let response = match client.get(&url).send().await {
            Ok(r) => r,
            Err(e) => return Ok(fail_result(format!("HTTP request failed: {e}"))),
        };

        let status = response.status();
        if !status.is_success() {
            let reason = status.canonical_reason().unwrap_or("Unknown");
            return Ok(fail_result(format!("HTTP {} {reason}", status.as_u16())));
        }

        let body_mode = match Self::classify_content_type(&response) {
            Ok(m) => m,
            Err(e) => return Ok(fail_result(e)),
        };

        let body = match self.read_response_text_limited(response).await {
            Ok(t) => t,
            Err(e) => return Ok(fail_result(format!("Failed to read response body: {e}"))),
        };

        let text = if body_mode == "html" {
            nanohtml2text::html2text(&body)
        } else {
            body
        };

        Ok(ToolResult {
            success: true,
            output: self.truncate_response(&text),
            error: None,
        metadata: None,
        })
    }
}

/// FFI-specific HTTP request tool that bypasses `SecurityPolicy`.
///
/// Supports multiple HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD,
/// OPTIONS) with custom headers and request body. Unlike [`FfiWebFetchTool`],
/// this tool returns the raw response including status line and headers,
/// does not follow redirects, and does not convert HTML.
///
/// The FFI crate keeps this Android wrapper local because upstream
/// `SecurityPolicy` is `pub(crate)`.
pub(super) struct FfiHttpRequestTool {
    /// Allowed domains for URL validation (exact or subdomain match).
    allowed_domains: Vec<String>,
    /// Maximum response body size in bytes before truncation (0 = unlimited).
    max_response_size: usize,
    /// HTTP request timeout in seconds (0 falls back to 30s).
    timeout_secs: u64,
}

impl FfiHttpRequestTool {
    pub(super) fn new(
        allowed_domains: Vec<String>,
        max_response_size: usize,
        timeout_secs: u64,
    ) -> Self {
        Self {
            allowed_domains,
            max_response_size,
            timeout_secs,
        }
    }

    /// Validates an HTTP method string and returns the corresponding
    /// [`reqwest::Method`], or an error for unsupported methods.
    fn validate_method(method: &str) -> Result<reqwest::Method, String> {
        match method.to_uppercase().as_str() {
            "GET" => Ok(reqwest::Method::GET),
            "POST" => Ok(reqwest::Method::POST),
            "PUT" => Ok(reqwest::Method::PUT),
            "DELETE" => Ok(reqwest::Method::DELETE),
            "PATCH" => Ok(reqwest::Method::PATCH),
            "HEAD" => Ok(reqwest::Method::HEAD),
            "OPTIONS" => Ok(reqwest::Method::OPTIONS),
            _ => Err(format!(
                "Unsupported HTTP method: {method}. \
                 Supported: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS"
            )),
        }
    }

    /// Parses a JSON object of header key-value pairs into a `Vec` of
    /// string tuples. Non-string values are silently skipped.
    fn parse_headers(headers: &serde_json::Value) -> Vec<(String, String)> {
        let mut result = Vec::new();
        if let Some(obj) = headers.as_object() {
            for (key, value) in obj {
                if let Some(str_val) = value.as_str() {
                    result.push((key.clone(), str_val.to_string()));
                }
            }
        }
        result
    }

    /// Returns a copy of the headers with sensitive values replaced by
    /// `***REDACTED***` for safe logging.
    fn redact_headers_for_display(headers: &[(String, String)]) -> Vec<(String, String)> {
        headers
            .iter()
            .map(|(key, value)| {
                let lower = key.to_lowercase();
                let is_sensitive = lower.contains("authorization")
                    || lower.contains("api-key")
                    || lower.contains("apikey")
                    || lower.contains("token")
                    || lower.contains("secret");
                if is_sensitive {
                    (key.clone(), "***REDACTED***".into())
                } else {
                    (key.clone(), value.clone())
                }
            })
            .collect()
    }

    /// Truncates text to the configured maximum size.
    ///
    /// A `max_response_size` of 0 means unlimited (no truncation).
    fn truncate_response(&self, text: &str) -> String {
        if self.max_response_size == 0 {
            return text.to_string();
        }
        if text.len() > self.max_response_size {
            let mut truncated = text
                .chars()
                .take(self.max_response_size)
                .collect::<String>();
            truncated.push_str("\n\n... [Response truncated due to size limit] ...");
            truncated
        } else {
            text.to_string()
        }
    }

    /// Builds a [`reqwest::Client`] with no redirect following and the
    /// configured timeout.
    fn build_client(&self) -> Result<reqwest::Client, String> {
        let timeout_secs = if self.timeout_secs == 0 {
            tracing::warn!("http_request: timeout_secs is 0, using safe default of 30s");
            30
        } else {
            self.timeout_secs
        };

        reqwest::Client::builder()
            .timeout(Duration::from_secs(timeout_secs))
            .connect_timeout(Duration::from_secs(10))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|e| format!("Failed to build HTTP client: {e}"))
    }

    /// Formats a successful response into the canonical output string
    /// including status line, headers, and (possibly truncated) body.
    async fn format_response(&self, response: reqwest::Response) -> ToolResult {
        let status = response.status();
        let status_code = status.as_u16();

        let headers_text = response
            .headers()
            .iter()
            .map(|(k, v)| {
                let key = k.as_str();
                if key.to_lowercase().contains("set-cookie") {
                    format!("{key}: ***REDACTED***")
                } else {
                    format!("{key}: {}", v.to_str().unwrap_or("<non-utf8>"))
                }
            })
            .collect::<Vec<_>>()
            .join(", ");

        let response_text = match response.text().await {
            Ok(text) => self.truncate_response(&text),
            Err(e) => format!("[Failed to read response body: {e}]"),
        };

        let reason = status.canonical_reason().unwrap_or("Unknown");
        let output = format!(
            "Status: {status_code} {reason}\n\
             Response Headers: {headers_text}\n\n\
             Response Body:\n{response_text}"
        );

        ToolResult {
            success: status.is_success(),
            output,
            error: if status.is_client_error() || status.is_server_error() {
                Some(format!("HTTP {status_code}"))
            } else {
                None
            },
        metadata: None,
        }
    }
}

#[async_trait]
impl Tool for FfiHttpRequestTool {
    fn name(&self) -> &'static str {
        "http_request"
    }

    fn description(&self) -> &'static str {
        "Make HTTP requests to external APIs. Supports GET, POST, PUT, DELETE, \
         PATCH, HEAD, OPTIONS methods. Returns status line, response headers, \
         and body. Security: allowlist-only domains, no local/private hosts, \
         configurable timeout and response size limits."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "HTTP or HTTPS URL to request"
                },
                "method": {
                    "type": "string",
                    "description": "HTTP method (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)",
                    "enum": ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"],
                    "default": "GET"
                },
                "headers": {
                    "type": "object",
                    "description": "Optional HTTP headers as key-value pairs"
                },
                "body": {
                    "type": "string",
                    "description": "Optional request body (for POST, PUT, PATCH requests)"
                }
            },
            "required": ["url"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let raw_url = args
            .get("url")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'url' parameter"))?;
        let method_str = args.get("method").and_then(|v| v.as_str()).unwrap_or("GET");
        let headers_val = args
            .get("headers")
            .cloned()
            .unwrap_or_else(|| serde_json::json!({}));
        let body = args.get("body").and_then(|v| v.as_str());

        let url = match url_helpers::validate_target_url_with_dns(
            raw_url,
            &self.allowed_domains,
            &[],
            "http_request",
        )
        .await
        {
            Ok(v) => v,
            Err(e) => return Ok(fail_result(e)),
        };

        let method = match Self::validate_method(method_str) {
            Ok(m) => m,
            Err(e) => return Ok(fail_result(e)),
        };

        let request_headers = Self::parse_headers(&headers_val);
        let redacted = Self::redact_headers_for_display(&request_headers);
        tracing::debug!(url = %url, method = %method, headers = ?redacted, "http_request: dispatching");

        let client = match self.build_client() {
            Ok(c) => c,
            Err(e) => return Ok(fail_result(e)),
        };

        let mut request = client.request(method, &url);
        for (key, value) in request_headers {
            request = request.header(&key, &value);
        }
        if let Some(body_str) = body {
            request = request.body(body_str.to_string());
        }

        match request.send().await {
            Ok(response) => Ok(self.format_response(response).await),
            Err(e) => Ok(fail_result(format!("HTTP request failed: {e}"))),
        }
    }
}