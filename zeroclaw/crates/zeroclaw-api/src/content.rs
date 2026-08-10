use serde::{Deserialize, Serialize};

fn default_version() -> u32 {
    1
}

/// Lifecycle execution state for individual content blocks.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BlockState {
    Loading,
    Streaming,
    Ready,
    Error {
        error_code: String,
        error_message: String,
    },
    Cancelled,
}

impl Default for BlockState {
    fn default() -> Self {
        BlockState::Ready
    }
}

/// Minimum Viable Set (MVS) of versioned content block representations.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ContentBlock {
    Text {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        #[serde(default)]
        parent_block_id: Option<String>,
        sequence_index: u32,
        text: String,
        #[serde(default)]
        state: BlockState,
    },
    Markdown {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        #[serde(default)]
        parent_block_id: Option<String>,
        sequence_index: u32,
        markdown: String,
        #[serde(default)]
        state: BlockState,
    },
    Reasoning {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        #[serde(default)]
        parent_block_id: Option<String>,
        sequence_index: u32,
        reasoning_text: String,
        is_complete: bool,
        signature: Option<String>,
        #[serde(default)]
        state: BlockState,
    },
    Image {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        #[serde(default)]
        parent_block_id: Option<String>,
        sequence_index: u32,
        url: Option<String>,
        mime_type: String,
        base64_data: Option<String>,
        alt_text: Option<String>,
        #[serde(default)]
        state: BlockState,
    },
    File {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        #[serde(default)]
        parent_block_id: Option<String>,
        sequence_index: u32,
        file_name: String,
        mime_type: String,
        size_bytes: u64,
        uri: Option<String>,
        #[serde(default)]
        state: BlockState,
    },
    ToolCard {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        #[serde(default)]
        parent_block_id: Option<String>,
        sequence_index: u32,
        tool_call_id: String,
        tool_name: String,
        status: String,
        input_json: String,
        result_blocks: Vec<ContentBlock>,
        execution_duration_ms: Option<u64>,
        #[serde(default)]
        state: BlockState,
    },
    Unknown {
        #[serde(default = "default_version")]
        version: u32,
        block_id: String,
        #[serde(default)]
        parent_block_id: Option<String>,
        sequence_index: u32,
        type_id: String,
        raw_json: String,
        #[serde(default)]
        state: BlockState,
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

    pub fn parent_block_id(&self) -> Option<&str> {
        match self {
            ContentBlock::Text { parent_block_id, .. } => parent_block_id.as_deref(),
            ContentBlock::Markdown { parent_block_id, .. } => parent_block_id.as_deref(),
            ContentBlock::Reasoning { parent_block_id, .. } => parent_block_id.as_deref(),
            ContentBlock::Image { parent_block_id, .. } => parent_block_id.as_deref(),
            ContentBlock::File { parent_block_id, .. } => parent_block_id.as_deref(),
            ContentBlock::ToolCard { parent_block_id, .. } => parent_block_id.as_deref(),
            ContentBlock::Unknown { parent_block_id, .. } => parent_block_id.as_deref(),
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

    pub fn state(&self) -> &BlockState {
        match self {
            ContentBlock::Text { state, .. } => state,
            ContentBlock::Markdown { state, .. } => state,
            ContentBlock::Reasoning { state, .. } => state,
            ContentBlock::Image { state, .. } => state,
            ContentBlock::File { state, .. } => state,
            ContentBlock::ToolCard { state, .. } => state,
            ContentBlock::Unknown { state, .. } => state,
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
    BlockDelta {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        block_id: String,
        delta: String,
    },
    BlockUpdated {
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
    BlockError {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        block_id: String,
        error_code: String,
        error_message: String,
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
    StreamCancelled {
        #[serde(default = "default_version")]
        version: u32,
        message_id: String,
        conversation_id: String,
        reason: String,
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
            parent_block_id: Some("p1".to_string()),
            sequence_index: 0,
            markdown: "Hello world".to_string(),
            state: BlockState::Streaming,
        };

        let json = serde_json::to_string(&block).unwrap();
        assert!(json.contains("\"type\":\"markdown\""));
        assert!(json.contains("\"version\":1"));
        assert!(json.contains("\"parent_block_id\":\"p1\""));
        assert!(json.contains("\"state\":\"streaming\""));

        let deserialized: ContentBlock = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized, block);
    }

    #[test]
    fn test_content_block_legacy_deserialization_defaults() {
        let legacy_json = r#"{"type":"text","version":1,"block_id":"b1","sequence_index":0,"text":"Hello"}"#;
        let deserialized: ContentBlock = serde_json::from_str(legacy_json).unwrap();
        assert_eq!(deserialized.parent_block_id(), None);
        assert_eq!(deserialized.state(), &BlockState::Ready);
    }

    #[test]
    fn test_assistant_event_serde_roundtrip() {
        let event = AssistantEvent::BlockDelta {
            version: 1,
            message_id: "m1".to_string(),
            conversation_id: "c1".to_string(),
            block_id: "b1".to_string(),
            delta: "chunk".to_string(),
        };

        let json = serde_json::to_string(&event).unwrap();
        assert!(json.contains("\"event_type\":\"block_delta\""));

        let deserialized: AssistantEvent = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized, event);
    }
}

