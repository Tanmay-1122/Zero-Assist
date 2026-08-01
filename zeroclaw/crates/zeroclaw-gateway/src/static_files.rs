//! Static file serving for the web dashboard.
//!
//! Serves the compiled `web/dist/` directory from the filesystem at runtime.
//! The directory path is configured via `gateway.web_dist_dir`.

use axum::{
    extract::State,
    http::{StatusCode, Uri, header},
    response::{IntoResponse, Response},
};
use std::path::PathBuf;

use super::AppState;

const PAUSED_HTML: &str = r#"<!DOCTYPE html>
<html><head><title>ZeroClaw — Paused</title></head>
<body style="display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#0a0a0f;color:#e2e8f0;font-family:system-ui">
  <div style="text-align:center">
    <h1>Web Dashboard Paused</h1>
    <p style="color:#94a3b8">The web dashboard has been disabled.</p>
    <button onclick="fetch('/admin/web/toggle',{method:'POST'}).then(()=>location.reload())"
      style="margin-top:1rem;padding:0.75rem 2rem;background:#3b82f6;color:white;border:none;border-radius:8px;cursor:pointer;font-size:1rem">
      Resume
    </button>
  </div>
</body></html>"#;

/// Serve static files from `/_app/*` path
pub async fn handle_static(State(state): State<AppState>, uri: Uri) -> Response {
    if !state.web_enabled.load(std::sync::atomic::Ordering::Relaxed) {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
            PAUSED_HTML,
        )
            .into_response();
    }

    let path = uri
        .path()
        .strip_prefix("/_app/")
        .unwrap_or(uri.path())
        .trim_start_matches('/');

    serve_fs_file(state.web_dist_dir.as_ref(), path).await
}

/// SPA fallback: serve index.html for any non-API, non-static GET request.
/// Injects `window.__ZEROCLAW_BASE__` so the frontend knows the path prefix.
pub async fn handle_spa_fallback(State(state): State<AppState>) -> Response {
    if !state.web_enabled.load(std::sync::atomic::Ordering::Relaxed) {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            [(header::CONTENT_TYPE, "text/html; charset=utf-8")],
            PAUSED_HTML,
        )
            .into_response();
    }

    let Some(ref dist_dir) = state.web_dist_dir else {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            "Web dashboard not available. Set gateway.web_dist_dir in your config \
             and build the frontend with: cd web && npm ci && npm run build",
        )
            .into_response();
    };

    let index_path = dist_dir.join("index.html");
    let Ok(bytes) = tokio::fs::read(&index_path).await else {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            "Web dashboard not available. Build it with: cd web && npm ci && npm run build",
        )
            .into_response();
    };

    let html = String::from_utf8_lossy(&bytes);

    // Inject path prefix for the SPA and rewrite asset paths in the HTML
    let html = if state.path_prefix.is_empty() {
        html.into_owned()
    } else {
        let pfx = &state.path_prefix;
        // JSON-encode the prefix to safely embed in a <script> block
        let json_pfx = serde_json::to_string(pfx).unwrap_or_else(|_| "\"\"".to_string());
        let script = format!("<script>window.__ZEROCLAW_BASE__={json_pfx};</script>");
        // Rewrite absolute /_app/ references so the browser requests {prefix}/_app/...
        html.replace("/_app/", &format!("{pfx}/_app/"))
            .replace("<head>", &format!("<head>{script}"))
    };

    (
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "text/html; charset=utf-8".to_string()),
            (header::CACHE_CONTROL, "no-cache".to_string()),
        ],
        html,
    )
        .into_response()
}

async fn serve_fs_file(dist_dir: Option<&PathBuf>, path: &str) -> Response {
    let Some(dir) = dist_dir else {
        return (StatusCode::NOT_FOUND, "Not found").into_response();
    };

    // Sanitize: reject path traversal attempts
    if path.contains("..") {
        return (StatusCode::BAD_REQUEST, "Invalid path").into_response();
    }

    let file_path = dir.join(path);

    match tokio::fs::read(&file_path).await {
        Ok(content) => {
            let mime = mime_guess::from_path(path)
                .first_or_octet_stream()
                .to_string();

            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, mime),
                    (
                        header::CACHE_CONTROL,
                        if path.contains("assets/") {
                            // Hashed filenames — immutable cache
                            "public, max-age=31536000, immutable".to_string()
                        } else {
                            // index.html etc — no cache
                            "no-cache".to_string()
                        },
                    ),
                ],
                content,
            )
                .into_response()
        }
        Err(_) => (StatusCode::NOT_FOUND, "Not found").into_response(),
    }
}
