# Request

## Compatibility: Responses API → Chat Completions API

### Property Mappings

| Responses API Property                 | Chat Completions API Equivalent                     |
|----------------------------------------|-----------------------------------------------------|
| `input`                                | `messages`                                          |
| `input.content`                        | `messages.content`                                  |
| `input.content.text`                   | `messages.content.text`                             |
| `input.content.image_url`              | `messages.content.image_url`                        |
| `input.content.file_id`                | `messages.content.file_id`                          |
| `input.role`                           | `messages.role`                                     |
| `model`                                | `model`                                             |
| `instructions`                         | `messages` (role: `system` or `developer`)          |
| `max_output_tokens`                    | `max_completion_tokens`                             |
| `parallel_tool_calls`                  | `parallel_tool_calls`                               |
| `previous_response_id`                 | (Not directly supported)                            |
| `temperature`                          | `temperature`                                       |
| `top_p`                                | `top_p`                                             |
| `tools`                                | `tools`                                             |
| `tool_choice`                          | `tool_choice`                                       |
| `metadata`                             | `metadata`                                          |
| `stream`                               | `stream`                                            |
| `include`                              | (Limited support via output options)                |
| `store`                                | `store`                                             |
| `truncation`                           | (Not directly supported)                            |
| `reasoning.effort`                     | `reasoning_effort`                                  |
| `reasoning.generate_summary`           | (Not directly supported)                            |
| `text.format`                          | `response_format.type`                              |
| `text.format.type`                     | `response_format.type`                              |
| `text.format.json_schema`              | `response_format.json_schema`                       |

### Unsupported Properties in Chat Completions API

- `previous_response_id` (conversation chaining managed manually)
- `truncation` strategy (`auto` and `disabled` not supported explicitly)
- `include` parameter (specific output expansions are limited or handled differently)
- `reasoning.generate_summary` (limited direct support)

### Notes on Modalities:
- Both APIs support multimodal inputs (`text`, `image`, `audio`).
- Output modalities (`audio`) supported via the `audio` property in Chat Completions.

### Using Completions API via Responses API
To utilize the Completions API through the Responses API, structure your requests as follows:
- Set `input` as a single message within a `messages` array.
- Clearly specify roles (`user`, `system`, or `developer`) within the message.
- Map properties directly based on the compatibility mappings above.

### Example curl Commands

1. Basic Text Completion:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","input":"Hello, world!"}'
```

2. Completion with Instructions:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","instructions":"Answer concisely.","input":"Explain AI."}'
```

3. Completion with Temperature Adjustment:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","temperature":0.5,"input":"Suggest a creative startup idea."}'
```

4. Structured JSON Output:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","text":{"format":{"type":"json_schema"}},"input":"Provide weather in JSON format."}'
```

5. Multimodal Text & Image Input:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","input":[{"type":"input_text","text":"Describe this image:"},{"type":"input_image","image_url":"IMAGE_URL"}]}'
```

6. Using Tools:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","tools":[{"type":"function","name":"get_current_weather"}],"input":"What's the weather today?"}'
```

7. Audio Response:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o-audio-preview","input":"Read this text aloud.","audio":{"format":"mp3","voice":"alloy"}}'
```

8. Streaming Response:
```bash
curl -N -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","input":"Stream your response.","stream":true}'
```

9. Metadata Usage:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","input":"Metadata example.","metadata":{"request_id":"12345","user":"test_user"}}'
```

10. Tool Choice Explicit:
```bash
curl -X POST https://api.openai.com/v1/responses \
-H "Content-Type: application/json" \
-H "Authorization: Bearer YOUR_API_KEY" \
-d '{"model":"gpt-4o","tools":[{"type":"function","name":"get_weather"}],"tool_choice":{"type":"function","name":"get_weather"},"input":"Force tool call."}'
```

### Masaic Layer Support

# Response

### Property Mappings

| Responses API Response Property   | Chat Completions API Response Equivalent         |
|-----------------------------------|-------------------------------------------------|
| `created_at`                      | `created`                                       |
| `error`                           | (Not explicitly provided; errors returned separately) |
| `id`                              | `id`                                            |
| `model`                           | `model`                                         |
| `object`                          | `object` (always `chat.completion`)             |
| `metadata`                        | (No direct equivalent; must be managed manually) |
| `output`                          | `choices[].message.content`                     |
| `output_text`                     | `choices[].message.content`                     |
| `status`                          | `choices[].finish_reason`                       |
| `usage`                           | `usage`                                         |
| `reasoning`                       | (Limited or not explicitly supported)           |
| `tool_calls`                      | `choices[].message.tool_calls`                  |
| `parallel_tool_calls`             | Managed via multiple tool calls in response     |
| `instructions`                    | (Instructions are part of request, not response)|
| `previous_response_id`            | (Not supported directly; managed manually)      |
| `temperature`                     | (Request parameter only; not returned)          |
| `text.format`                     | Managed through response request format         |

### Differences and Notes

- **Error Handling**:
    - Responses API provides an `error` object directly in response.
    - Chat Completions API handles errors via separate HTTP error responses.

- **Output Structure**:
    - Responses API uses a flat `output` array containing multiple content items.
    - Chat Completions API organizes content under `choices[].message.content`.

- **Tool Calls**:
    - Both APIs support `tool_calls`, but the Chat Completions API nests them explicitly under `choices[].message.tool_calls`.

- **Reasoning and Detailed Metadata**:
    - Reasoning chains or detailed metadata like summaries and additional reasoning details provided explicitly by Responses API are either limited or need to be managed manually with the Chat Completions API.

### Unsupported Properties

- `incomplete_details`
- Explicit `instructions` echoed in response
- `metadata` echoed in response (must manage separately)
- `reasoning.generate_summary`
- Explicit `previous_response_id` handling (must be manually managed)

### Masaic Layer Support


