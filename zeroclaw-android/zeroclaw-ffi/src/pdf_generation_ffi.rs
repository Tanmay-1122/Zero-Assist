/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Direct PDF generation against supported third-party providers.
//!
//! The Kotlin layer builds provider-specific JSON request bodies via
//! `PdfProviderAdapter` and passes them here as the historical `html`
//! argument in the UniFFI export. This module is responsible for choosing the
//! endpoint, attaching authentication, downloading or decoding the resulting
//! PDF bytes, and writing them to the requested output path.

use crate::error::FfiError;
use base64::Engine as _;
use reqwest::header::CONTENT_TYPE;
use serde_json::Value;
use std::path::Path;
use tokio::time::Duration;

/// HTTP timeout used for PDF generation requests.
const PDF_TIMEOUT_SECS: u64 = 120;

/// Supported provider endpoints for PDF generation.
enum PdfProvider {
    /// CustomJS HTML-to-PDF endpoint.
    CustomJs,
    /// PDF Generator API HTML-to-PDF conversion endpoint.
    PdfGeneratorApi,
    /// CraftMyPDF template rendering endpoint.
    CraftMyPdf,
}

/// Generates a PDF and writes it to the requested output path.
pub(crate) fn generate_pdf_inner(
    provider_name: String,
    api_key: String,
    payload_json: String,
    output_path: String,
) -> Result<String, FfiError> {
    let provider = classify_provider(&provider_name)?;
    if payload_json.trim().is_empty() {
        return Err(FfiError::ConfigError {
            detail: "pdf payload is empty".into(),
        });
    }

    let handle = crate::runtime::get_or_create_runtime()?;
    handle.block_on(async move {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(PDF_TIMEOUT_SECS))
            .build()
            .map_err(|e| FfiError::SpawnError {
                detail: format!("failed to build PDF HTTP client: {e}"),
            })?;

        let (url, auth_header_name, auth_header_value) = match provider {
            PdfProvider::CustomJs => ("https://e.customjs.io/html2pdf", "x-api-key", api_key),
            PdfProvider::PdfGeneratorApi => (
                "https://us1.pdfgeneratorapi.com/api/v4/conversion/html2pdf",
                "Authorization",
                format!("Bearer {api_key}"),
            ),
            PdfProvider::CraftMyPdf => {
                ("https://api.craftmypdf.com/v1/create", "X-API-KEY", api_key)
            }
        };

        let response = client
            .post(url)
            .header(auth_header_name, auth_header_value)
            .header(CONTENT_TYPE, "application/json")
            .body(payload_json)
            .send()
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("pdf request failed: {e}"),
            })?;

        let status = response.status();
        let content_type = response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("")
            .to_string();

        if !status.is_success() {
            let body = response.text().await.unwrap_or_default();
            let truncated = truncate_error_body(&body);
            return Err(FfiError::SpawnError {
                detail: format!("pdf provider returned status {status}: {truncated}"),
            });
        }

        let pdf_bytes = if content_type.contains("application/pdf") {
            response
                .bytes()
                .await
                .map(|bytes| bytes.to_vec())
                .map_err(|e| FfiError::SpawnError {
                    detail: format!("failed to read pdf bytes: {e}"),
                })?
        } else {
            let body = response.text().await.map_err(|e| FfiError::SpawnError {
                detail: format!("failed to read non-binary pdf response: {e}"),
            })?;
            extract_pdf_bytes(&client, &body).await?
        };

        write_pdf_file(&output_path, &pdf_bytes)?;
        Ok(output_path)
    })
}

fn classify_provider(provider_name: &str) -> Result<PdfProvider, FfiError> {
    match provider_name.trim().to_lowercase().as_str() {
        "custom_js" | "customjs" => Ok(PdfProvider::CustomJs),
        "pdf_generator_api" | "pdfgeneratorapi" => Ok(PdfProvider::PdfGeneratorApi),
        "craft_my_pdf" | "craftmypdf" => Ok(PdfProvider::CraftMyPdf),
        other => Err(FfiError::InvalidArgument {
            detail: format!("unsupported pdf provider: {other}"),
        }),
    }
}

async fn extract_pdf_bytes(client: &reqwest::Client, body: &str) -> Result<Vec<u8>, FfiError> {
    let trimmed = body.trim();
    if trimmed.is_empty() {
        return Err(FfiError::SpawnError {
            detail: "pdf provider returned an empty response".into(),
        });
    }

    if trimmed.starts_with('{') || trimmed.starts_with('[') {
        let value: Value = serde_json::from_str(trimmed).map_err(|e| FfiError::SpawnError {
            detail: format!("failed to parse pdf provider response JSON: {e}"),
        })?;
        return extract_pdf_bytes_from_json(client, &value).await;
    }

    if looks_like_url(trimmed) {
        return download_pdf_bytes(client, trimmed).await;
    }

    decode_base64_pdf(trimmed)
}

async fn extract_pdf_bytes_from_json(
    client: &reqwest::Client,
    value: &Value,
) -> Result<Vec<u8>, FfiError> {
    if let Some(candidate) = find_string_field(value, &["response", "file", "pdf", "content"]) {
        if looks_like_url(candidate) {
            return download_pdf_bytes(client, candidate).await;
        }
        if let Ok(bytes) = decode_base64_pdf(candidate) {
            return Ok(bytes);
        }
    }

    if let Some(url) = find_string_field(value, &["url", "download_url", "file_url"]) {
        return download_pdf_bytes(client, url).await;
    }

    if let Some(meta) = value.get("meta")
        && let Some(url) = find_string_field(meta, &["url", "download_url", "file_url"])
    {
        return download_pdf_bytes(client, url).await;
    }

    Err(FfiError::SpawnError {
        detail: "pdf provider response did not include PDF bytes or a download URL".into(),
    })
}

fn find_string_field<'a>(value: &'a Value, fields: &[&str]) -> Option<&'a str> {
    fields
        .iter()
        .find_map(|field| value.get(*field).and_then(Value::as_str))
}

async fn download_pdf_bytes(client: &reqwest::Client, url: &str) -> Result<Vec<u8>, FfiError> {
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to download generated pdf: {e}"),
        })?;
    let status = response.status();
    if !status.is_success() {
        return Err(FfiError::SpawnError {
            detail: format!("generated pdf download returned status {status}"),
        });
    }
    response
        .bytes()
        .await
        .map(|bytes| bytes.to_vec())
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to read generated pdf download: {e}"),
        })
}

fn decode_base64_pdf(data: &str) -> Result<Vec<u8>, FfiError> {
    base64::engine::general_purpose::STANDARD
        .decode(data)
        .or_else(|_| base64::engine::general_purpose::STANDARD_NO_PAD.decode(data))
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to decode generated pdf base64 payload: {e}"),
        })
}

fn looks_like_url(value: &str) -> bool {
    value.starts_with("https://") || value.starts_with("http://")
}

fn write_pdf_file(output_path: &str, bytes: &[u8]) -> Result<(), FfiError> {
    let output = Path::new(output_path);
    if let Some(parent) = output.parent() {
        std::fs::create_dir_all(parent).map_err(|e| FfiError::SpawnError {
            detail: format!("failed to create output directory: {e}"),
        })?;
    }
    std::fs::write(output, bytes).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to write generated pdf: {e}"),
    })
}

fn truncate_error_body(body: &str) -> String {
    match body.char_indices().nth(500) {
        Some((index, _)) => format!("{}...", &body[..index]),
        None => body.to_string(),
    }
}
