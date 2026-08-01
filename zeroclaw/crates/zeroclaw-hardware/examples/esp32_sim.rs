// ESP32 serial simulator — simulates a ZeroClaw-compatible ESP32 device
// responding to newline-delimited JSON commands over a virtual serial port.
//
// Usage:
//   cargo run --example esp32_sim --features hardware
//
// Creates a pty pair. Connect the ZeroClaw agent to the slave side.

#[cfg(not(unix))]
fn main() {
    eprintln!("esp32_sim: PTY simulation only supported on Unix systems.");
}

#[cfg(unix)]
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    use std::io::Write;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    println!("ESP32 ZeroClaw Simulator");
    println!("Listening for newline-delimited JSON commands...");
    println!("Press Ctrl+C to stop.\n");

    let stdin = tokio::io::stdin();
    let stdout = tokio::io::stdout();
    let mut reader = BufReader::new(stdin);
    let mut writer = stdout;

    loop {
        let mut line = String::new();
        match reader.read_line(&mut line).await {
            Ok(0) => break, // EOF
            Ok(_) => {}
            Err(e) => {
                eprintln!("Read error: {e}");
                break;
            }
        }

        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let response = handle_command(trimmed);
        let mut out = serde_json::to_string(&response).unwrap_or_default();
        out.push('\n');

        if let Err(e) = writer.write_all(out.as_bytes()).await {
            eprintln!("Write error: {e}");
            break;
        }
        writer.flush().await?;
    }

    Ok(())
}

#[cfg(unix)]
fn handle_command(line: &str) -> serde_json::Value {
    use serde_json::json;

    let cmd: serde_json::Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(e) => {
            return json!({ "ok": false, "error": format!("Parse error: {e}") });
        }
    };

    let name = cmd.get("cmd").and_then(|v| v.as_str()).unwrap_or("");
    let params = cmd.get("params").cloned().unwrap_or(json!({}));

    match name {
        "ping" => json!({ "ok": true, "data": { "pong": true } }),

        "capabilities" => json!({
            "ok": true,
            "data": {
                "device": "ESP32",
                "firmware": "ZeroClaw-ESP32 v0.1.0",
                "commands": ["ping", "capabilities", "gpio_read", "gpio_write", "sensor_read"]
            }
        }),

        "gpio_read" => {
            let pin = params.get("pin").and_then(|v| v.as_u64()).unwrap_or(0);
            // Simulate alternating high/low
            let value = (pin % 2) as u8;
            json!({ "ok": true, "data": { "pin": pin, "value": value } })
        }

        "gpio_write" => {
            let pin   = params.get("pin").and_then(|v| v.as_u64()).unwrap_or(0);
            let value = params.get("value").and_then(|v| v.as_u64()).unwrap_or(0);
            json!({ "ok": true, "data": { "pin": pin, "value": value, "state": if value == 1 { "HIGH" } else { "LOW" } } })
        }

        "sensor_read" => {
            json!({
                "ok": true,
                "data": {
                    "temperature_c": 23.5,
                    "humidity_pct": 55.2,
                    "sensor": params.get("sensor").and_then(|v| v.as_str()).unwrap_or("aht10")
                }
            })
        }

        other => {
            json!({ "ok": false, "error": format!("Unknown command: {other}") })
        }
    }
}
