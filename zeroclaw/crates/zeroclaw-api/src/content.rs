use serde::{Deserialize, Serialize};

fn default_version() -> u32 {
    1
}

/// Minimum Viable Set (MVS) of versioned content block representations.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ContentBlock {
    Text {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        sequence_index: u32,
        text: String,
    },
    Markdown {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        sequence_index: u32,
        markdown: String,
    },
    Reasoning {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        sequence_index: u32,
        reasoning_text: String,
        is_complete: bool,
        signature: Option<String>,
    },
    Image {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        sequence_index: u32,
        url: Option<String>,
        mime_type: String,
        base64_data: Option<String>,
        alt_text: Option<String>,
    },
    File {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        sequence_index: u32,
        file_name: String,
        mime_type: String,
        size_bytes: u64,
        uri: Option<String>,
    },
    ToolCard {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        sequence_index: u32,
        tool_call_id: String,
        tool_name: String,
        status: String,
        input_json: String,
        result_blocks: Vec<ContentBlock>,
        execution_duration_ms: Option<u64>,
    },
    Unknown {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        sequence_index: u32,
        type_id: String,
        raw_json: String,
    },
}

impl ContentBlock {
    pub fn version(&self) -> u32 {
        match self {
            ContentBlock::Text { version, .. } => *version,
            ContentBlock::Markdown { version, .. } => *version,
            ContentBlock::Reasoning { version, .. } => *version,
            ContentBlock::Image { version, .. } => *version,
            ContentBlock::File { version, .. } => *version,
            ContentBlock::ToolCard { version, .. } => *version,
            ContentBlock::Unknown { version, .. } => *version,
        }
    }

    pub fn block_id(&self) -> &str {
        match self {
            ContentBlock::Text { block_id, .. } => block_id,
            ContentBlock::Markdown { block_id, .. } => block_id,
            ContentBlock::Reasoning { block_id, .. } => block_id,
            ContentBlock::Image { block_id, .. } => block_id,
            ContentBlock::File { block_id, .. } => block_id,
            ContentBlock::ToolCard { block_id, .. } => block_id,
            ContentBlock::Unknown { block_id, .. } => block_id,
        }
    }

    pub fn sequence_index(&self) -> u32 {
        match self {
            ContentBlock::Text { sequence_index, .. } => *sequence_index,
            ContentBlock::Markdown { sequence_index, .. } => *sequence_index,
            ContentBlock::Reasoning { sequence_index, .. } => *sequence_index,
            ContentBlock::Image { sequence_index, .. } => *sequence_index,
            ContentBlock::File { sequence_index, .. } => *sequence_index,
            ContentBlock::ToolCard { sequence_index, .. } => *sequence_index,
            ContentBlock::Unknown { sequence_index, .. } => *sequence_index,
        }
    }

    pub fn text_content(&self) -> Option<&str> {
        match self {
            ContentBlock::Text { text, .. } => Some(text.as_str()),
            ContentBlock::Markdown { markdown, .. } => Some(markdown.as_str()),
            _ => None,
        }
    }
}

/// Versioned event-driven assistant stream events.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "event_type", rename_all = "snake_case")]
pub enum AssistantEvent {
    StreamStarted {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        sender_id: String,
        sender_name: String,
    },
    ThinkingChunk {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        block_id: String,
        delta: String,
    },
    ReasoningFinished {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        block_id: String,
    },
    TextChunk {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        block_id: String,
        delta: String,
    },
    BlockStarted {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        block: ContentBlock,
    },
    BlockFinished {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        block_id: String,
    },
    StreamFinished {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        total_tokens: Option<u32>,
        duration_ms: u64,
    },
    StreamError {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        error_code: String,
        error_message: String,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_content_block_serde_roundtrip() {
        let block = ContentBlock::Markdown {
            version: 1,
            block_id: "b1".to_string(),
            sequence_index: 0,
            markdown: "Hello world".to_string(),
        };

        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains("\"type\":\"markdown\""));
        assert!(json.contains("\"version\":1"));

        let deserialized: ContentBlock = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized, block);
    }

    #[test]
    fn test_assistant_event_serde_roundtrip() {
        let event = AssistantEvent::TextChunk {
            version: 1,
            message_id: "m1".to_string(),
            conversation_id: "c1".to_string(),
            block_id: "b1".to_string(),
            delta: "chunk".to_string(),
        };

        let json = serde_json::to_string(&event).unwrap();
        assert!(json.contains("\"event_type\":\"text_chunk\""));

        let deserialized: AssistantEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized, event);
    }
}
