/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

use zeroclaw_api::content::{AssistantEvent, ContentBlock};
use zeroclaw_api::provider::{StreamChunk, StreamEvent};

/// Normalizes provider-specific streaming events into standardized `AssistantEvent` payloads.
pub struct ProviderOutputNormalizer {
    conversation_id: String,
    message_id: String,
    current_block_index: u32,
    active_thinking_block_id: Option<String>,
    active_text_block_id: Option<String>,
}

impl ProviderOutputNormalizer {
    pub fn new(conversation_id: impl Into<String>, message_id: impl Into<String>) -> Self {
        Self {
            conversation_id: conversation_id.into(),
            message_id: message_id.into(),
            current_block_index: 0,
            active_thinking_block_id: None,
            active_text_block_id: None,
        }
    }

    /// Normalizes a provider `StreamEvent` into zero or more `AssistantEvent`s.
    pub fn normalize_event(&mut self, event: &StreamEvent) -> Vec<AssistantEvent> {
        let mut events = Vec::new();

        match event {
            StreamEvent::TextDelta(chunk) => {
                events.extend(self.normalize_chunk(chunk));
            }
            StreamEvent::ToolCall(tool_call) => {
                let block_id = format!("{}_b{}", self.message_id, self.current_block_index);
                self.current_block_index += 1;

                let block = ContentBlock::ToolCard {
                    version: 1,
                    block_id: block_id.clone(),
                    parent_block_id: None,
                    sequence_index: self.current_block_index,
                    tool_call_id: tool_call.id.clone(),
                    tool_name: tool_call.name.clone(),
                    status: "executing".to_string(),
                    input_json: tool_call.arguments.clone(),
                    result_blocks: Vec::new(),
                    execution_duration_ms: None,
                    state: zeroclaw_api::content::BlockState::Streaming,
                };

                events.push(AssistantEvent::BlockStarted {
                    version: 1,
                    message_id: self.message_id.clone(),
                    conversation_id: self.conversation_id.clone(),
                    block,
                });
            }
            StreamEvent::PreExecutedToolCall { name, args } => {
                let block_id = format!("{}_b{}", self.message_id, self.current_block_index);
                self.current_block_index += 1;

                let block = ContentBlock::ToolCard {
                    version: 1,
                    block_id,
                    parent_block_id: None,
                    sequence_index: self.current_block_index,
                    tool_call_id: format!("pre_exec_{name}"),
                    tool_name: name.clone(),
                    status: "executing".to_string(),
                    input_json: args.clone(),
                    result_blocks: Vec::new(),
                    execution_duration_ms: None,
                    state: zeroclaw_api::content::BlockState::Streaming,
                };

                events.push(AssistantEvent::BlockStarted {
                    version: 1,
                    message_id: self.message_id.clone(),
                    conversation_id: self.conversation_id.clone(),
                    block,
                });
            }
            StreamEvent::PreExecutedToolResult { name: _, output } => {
                let block_id = format!("{}_b{}", self.message_id, self.current_block_index);
                self.current_block_index += 1;

                let block = ContentBlock::Markdown {
                    version: 1,
                    block_id,
                    parent_block_id: None,
                    sequence_index: self.current_block_index,
                    markdown: output.clone(),
                    state: zeroclaw_api::content::BlockState::Ready,
                };

                events.push(AssistantEvent::BlockStarted {
                    version: 1,
                    message_id: self.message_id.clone(),
                    conversation_id: self.conversation_id.clone(),
                    block,
                });
            }
            StreamEvent::Final => {
                if let Some(block_id) = self.active_thinking_block_id.take() {
                    events.push(AssistantEvent::ReasoningFinished {
                        version: 1,
                        message_id: self.message_id.clone(),
                        conversation_id: self.conversation_id.clone(),
                        block_id,
                    });
                }
                if let Some(block_id) = self.active_text_block_id.take() {
                    events.push(AssistantEvent::BlockFinished {
                        version: 1,
                        message_id: self.message_id.clone(),
                        conversation_id: self.conversation_id.clone(),
                        block_id,
                    });
                }
                events.push(AssistantEvent::StreamFinished {
                    version: 1,
                    message_id: self.message_id.clone(),
                    conversation_id: self.conversation_id.clone(),
                    total_tokens: None,
                    duration_ms: 0,
                });
            }
        }

        events
    }

    /// Normalizes a `StreamChunk` into thinking or text deltas.
    pub fn normalize_chunk(&mut self, chunk: &StreamChunk) -> Vec<AssistantEvent> {
        let mut events = Vec::new();

        if let Some(reasoning) = &chunk.reasoning {
            if !reasoning.is_empty() {
                let block_id = self.active_thinking_block_id.get_or_insert_with(|| {
                    let id = format!("{}_think_{}", self.message_id, self.current_block_index);
                    self.current_block_index += 1;
                    id
                }).clone();

                events.push(AssistantEvent::ThinkingChunk {
                    version: 1,
                    message_id: self.message_id.clone(),
                    conversation_id: self.conversation_id.clone(),
                    block_id,
                    delta: reasoning.clone(),
                });
            }
        }

        if !chunk.delta.is_empty() {
            let block_id = self.active_text_block_id.get_or_insert_with(|| {
                let id = format!("{}_text_{}", self.message_id, self.current_block_index);
                self.current_block_index += 1;
                id
            }).clone();

            events.push(AssistantEvent::TextChunk {
                version: 1,
                message_id: self.message_id.clone(),
                conversation_id: self.conversation_id.clone(),
                block_id,
                delta: chunk.delta.clone(),
            });
        }

        if chunk.is_final {
            if let Some(block_id) = self.active_thinking_block_id.take() {
                events.push(AssistantEvent::ReasoningFinished {
                    version: 1,
                    message_id: self.message_id.clone(),
                    conversation_id: self.conversation_id.clone(),
                    block_id,
                });
            }
            if let Some(block_id) = self.active_text_block_id.take() {
                events.push(AssistantEvent::BlockFinished {
                    version: 1,
                    message_id: self.message_id.clone(),
                    conversation_id: self.conversation_id.clone(),
                    block_id,
                });
            }
        }

        events
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_normalizer_text_chunk() {
        let mut normalizer = ProviderOutputNormalizer::new("conv_1", "msg_1");
        let chunk = StreamChunk::delta("Hello world");
        let events = normalizer.normalize_chunk(&chunk);

        assert_eq!(events.len(), 1);
        match &events[0] {
            AssistantEvent::TextChunk { delta, block_id, .. } => {
                assert_eq!(delta, "Hello world");
                assert!(block_id.contains("msg_1_text_"));
            }
            _ => panic!("Expected TextChunk"),
        }
    }

    #[test]
    fn test_normalizer_thinking_chunk() {
        let mut normalizer = ProviderOutputNormalizer::new("conv_1", "msg_1");
        let chunk = StreamChunk::reasoning("Thinking step 1");
        let events = normalizer.normalize_chunk(&chunk);

        assert_eq!(events.len(), 1);
        match &events[0] {
            AssistantEvent::ThinkingChunk { delta, block_id, .. } => {
                assert_eq!(delta, "Thinking step 1");
                assert!(block_id.contains("msg_1_think_"));
            }
            _ => panic!("Expected ThinkingChunk"),
        }
    }
}
