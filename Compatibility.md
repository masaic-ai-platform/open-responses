# Using Chat Completions API via Responses API

This guide provides a comprehensive mapping to help you use the Chat Completions API functionality through the Responses API interface.

## Property Mappings

### Request Parameters

| Responses API Property                | Chat Completions API Equivalent                    | Notes                                      |
|---------------------------------------|---------------------------------------------------|---------------------------------------------|
| `input`                               | `messages`                                        | Structure your input as messages array     |
| `input.content`                       | `messages.content`                                | Content structure for messages             |
| `input.content.text`                  | `messages.content.text`                           | Text content in message                    |
| `input.content.image_url`             | `messages.content.image_url`                      | Image URL for multimodal inputs            |
| `input.content.file_id`               | `messages.content.file_id`                        | File reference                             |
| `input.role`                          | `messages.role`                                   | User, assistant, or system role            |
| `model`                               | `model`                                           | Model identifier                           |
| `instructions`                        | `messages` (role: `system` or `developer`)        | Add as system/developer messages           |
| `max_output_tokens`                   | `max_completion_tokens`                           | Maximum tokens in response                 |
| `parallel_tool_calls`                 | `parallel_tool_calls`                             | Enable parallel tool calls                 |
| `temperature`                         | `temperature`                                     | Controls randomness (0-1)                  |
| `top_p`                               | `top_p`                                           | Controls diversity via nucleus sampling    |
| `tools`                               | `tools`                                           | Available tools for the model              |
| `tool_choice`                         | `tool_choice`                                     | Specifies which tool to use                |
| `metadata`                            | `metadata`                                        | Custom metadata for the request            |
| `stream`                              | `stream`                                          | Enable streaming response                  |
| `store`                               | `store`                                           | Persist conversation history               |
| `reasoning.effort`                    | `reasoning_effort`                                | Controls reasoning depth                   |
| `text.format`                         | `response_format.type`                            | Specifies response format                  |
| `text.format.type`                    | `response_format.type`                            | Type of response format                    |
| `text.format.json_schema`             | `response_format.json_schema`                     | JSON schema for structured responses       |

### Response Properties

| Responses API Response Property       | Chat Completions API Response Equivalent          | Notes                                     |
|---------------------------------------|--------------------------------------------------|-------------------------------------------|
| `created_at`                          | `created`                                        | Timestamp of response creation            |
| `id`                                  | `id`                                             | Unique identifier for the response        |
| `model`                               | `model`                                          | Model used for the response               |
| `object`                              | `object` (always `chat.completion`)              | Object type identifier                    |
| `output`                              | `choices[].message.content`                      | Main content of the response              |
| `output_text`                         | `choices[].message.content`                      | Text content of the response              |
| `status`                              | `choices[].finish_reason`                        | Reason for completion                     |
| `usage`                               | `usage`                                          | Token usage statistics                    |
| `tool_calls`                          | `choices[].message.tool_calls`                   | Tool calls in the response                |

## Unsupported Properties

When using Chat Completions API via Responses API, the following Responses API features have limited or no direct support:

- `previous_response_id` (conversation chaining must be managed manually)
- `truncation` strategy (`auto` and `disabled` not supported explicitly)
- `include` parameter (specific output expansions are limited)
- `reasoning.generate_summary` (limited direct support)

## Modality Support

Both interfaces support:
- Multimodal inputs (text, image, audio)
- Audio outputs via the `audio` property

## Implementation Notes

When using Chat Completions functionality through the Responses API:
- Structure your `input` as a single message within a `messages` array
- Clearly specify roles (`user`, `system`, or `developer`) within each message
- Map properties according to the compatibility tables above

## Example API Calls

### Basic Text Completion

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "input": "Hello, world!"
}'
```

### Completion with Instructions

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "instructions": "Answer concisely.",
  "input": "Explain AI."
}'
```

### Completion with Temperature Adjustment

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "temperature": 0.5,
  "input": "Suggest a creative startup idea."
}'
```

### Structured JSON Output

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "text": {
    "format": {
      "type": "json_schema"
    }
  },
  "input": "Provide weather in JSON format."
}'
```

### Multimodal Text & Image Input

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "input": [
    {
      "type": "input_text",
      "text": "Describe this image:"
    },
    {
      "type": "input_image",
      "image_url": "IMAGE_URL"
    }
  ]
}'
```

### Using Tools

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "tools": [
    {
      "type": "function",
      "name": "get_current_weather"
    }
  ],
  "input": "What's the weather today?"
}'
```

### Audio Response

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o-audio-preview",
  "input": "Read this text aloud.",
  "audio": {
    "format": "mp3",
    "voice": "alloy"
  }
}'
```

### Streaming Response

```bash
curl -N -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "input": "Stream your response.",
  "stream": true
}'
```

### With Metadata

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "input": "Metadata example.",
  "metadata": {
    "request_id": "12345",
    "user": "test_user"
  }
}'
```

### Explicit Tool Choice

```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{
  "model": "gpt-4o",
  "tools": [
    {
      "type": "function",
      "name": "get_weather"
    }
  ],
  "tool_choice": {
    "type": "function",
    "name": "get_weather"
  },
  "input": "Force tool call."
}'
```