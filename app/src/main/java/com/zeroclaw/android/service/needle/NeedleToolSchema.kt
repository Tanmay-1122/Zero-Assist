package com.zeroclaw.android.service.needle

/**
 * The exact tool surface passed to `needle_init` as `tools_json`.
 *
 * Needle compact format (`name` / `description` / JSON-schema `parameters`),
 * matching the `tools.json` contract from the Needle 2 model card. Eight
 * tools: with more than five declared, the engine's built-in retrieval head
 * renders only the top five per turn, which is the desired behavior here.
 *
 * Excluded deliberately (weak for a 45M tool-caller, routed to cloud):
 * `click_at` and `swipe` (float-coordinate grounding), `share_file`
 * (URI/MIME/package structured payload).
 */
object NeedleToolSchema {

    val toolsJson: String = """
        [
          {"name":"click_text","description":"Tap the visible UI element with this exact label","parameters":{"type":"object","properties":{"text":{"type":"string","description":"exact visible label from NODES"}},"required":["text"]}},
          {"name":"click_index","description":"Tap the UI element at this numbered position","parameters":{"type":"object","properties":{"index":{"type":"integer","description":"0-based position from NODES"}},"required":["index"]}},
          {"name":"type_text","description":"Type text into the focused editable field","parameters":{"type":"object","properties":{"text":{"type":"string"},"field_hint":{"type":"string"}},"required":["text"]}},
          {"name":"press_enter","description":"Press the Enter/IME action key","parameters":{"type":"object","properties":{}}},
          {"name":"scroll","description":"Scroll the scrollable view","parameters":{"type":"object","properties":{"direction":{"type":"string","enum":["UP","DOWN"]}},"required":["direction"]}},
          {"name":"open_app","description":"Launch an installed app by name","parameters":{"type":"object","properties":{"app_name":{"type":"string"},"package_name":{"type":"string"}},"required":["app_name"]}},
          {"name":"wait","description":"Wait for the UI to settle","parameters":{"type":"object","properties":{"millis":{"type":"integer"}}}},
          {"name":"done","description":"The goal is already complete on screen","parameters":{"type":"object","properties":{"message":{"type":"string","description":"brief summary of what was achieved"}},"required":["message"]}}
        ]
    """.trimIndent()

    val toolNames: Set<String> = setOf(
        "click_text",
        "click_index",
        "type_text",
        "press_enter",
        "scroll",
        "open_app",
        "wait",
        "done",
    )
}
