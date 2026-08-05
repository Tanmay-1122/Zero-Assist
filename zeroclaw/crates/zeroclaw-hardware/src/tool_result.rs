/// Helper constructors for ToolResult — keeps call sites concise.
///
/// Usage:
///   `return tool_ok("success message");`
///   `return tool_err("error message");`

use zeroclaw_api::tool::ToolResult;

/// Build a successful [`ToolResult`].
#[inline]
pub fn tool_ok(output: impl Into<String>) -> anyhow::Result<ToolResult> {
    Ok(ToolResult {
        success: true,
        output: output.into(),
        error: None,
        blocks: Vec::new(),
    metadata: None,
    })
}

/// Build a failed [`ToolResult`].
#[inline]
pub fn tool_err(message: impl Into<String>) -> anyhow::Result<ToolResult> {
    let msg = message.into();
    Ok(ToolResult {
        success: false,
        output: String::new(),
        error: Some(msg),
        blocks: Vec::new(),
    metadata: None,
    })
}