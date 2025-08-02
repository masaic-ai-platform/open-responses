# API Integration Guide: `/v1/responses`

This document provides front-end developers with the necessary information to integrate with the `/v1/responses` endpoint for the chat functionality.

## Endpoint

- **URL:** `/v1/responses`
- **Method:** `POST`
- **Host:** `http://localhost:6644`

## Headers

- **Content-Type:** `application/json`
- **Authorization:** `Bearer <YOUR_API_KEY>`
  - The API key should be dynamically retrieved based on the selected model's provider.

## Request Body

The request body is a JSON object with the following structure:

| Field                  | Type                               | Description                                                                                                                                                             | Required |
| ---------------------- | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `model`                | `string`                           | The model identifier, formatted as `provider@model_name` (e.g., `openai@gpt-4.1-mini`).                                                                                   | Yes      |
| `tools`                | `array`                            | An array of tool configurations for the model to use. Supports MCP servers, function calling, and other tool types.                                                    | No       |
| `instructions`         | `string`                           | The system prompt or instructions for the model.                                                                                                                        | Yes      |
| `input`                | `array`                            | An array containing the user's message. For conversation history, this should only contain the most recent user message.                                                  | Yes      |
| `text`                 | `object`                           | An object containing text generation parameters.                                                                                                                        | Yes      |
| `temperature`          | `number`                           | Controls randomness. A value between 0.0 and 2.0.                                                                                                                       | Yes      |
| `max_output_tokens`    | `number`                           | The maximum number of tokens to generate.                                                                                                                               | Yes      |
| `top_p`                | `number`                           | Controls diversity via nucleus sampling. A value between 0.0 and 1.0.                                                                                                   | Yes      |
| `store`                | `boolean`                          | If `true`, the response is stored for future reference.                                                                                                                 | Yes      |
| `stream`               | `boolean`                          | If `true`, the response will be streamed using Server-Sent Events (SSE). If `false` or omitted, returns a complete response.                                            | No       |
| `previous_response_id` | `string`                           | The `id` from the previous API response. This is used to maintain conversational context. Omit this field for the first message in a conversation.                       | No       |

### `tools` Array Structure

The `tools` array allows you to configure various types of tools for the model to use during response generation.

#### MCP Server Tool Configuration

MCP (Model Context Protocol) servers provide external tool capabilities. When configured, MCP tools are included in every request.

**Configuration Source**: All MCP tool parameters come from the user's configuration in the MCP tool config modal.

| Field           | Type     | Description                                                                                      | Required |
| --------------- | -------- | ------------------------------------------------------------------------------------------------ | -------- |
| `type`          | `string` | Always `"mcp"` for MCP server tools (hardcoded).                                                | Yes      |
| `server_label`  | `string` | User-configured unique identifier for the MCP server (from MCP tool config modal).             | Yes      |
| `server_url`    | `string` | User-configured URL endpoint of the MCP server (from MCP tool config modal).                   | Yes      |
| `allowed_tools` | `array`  | User-selected array of tool names from MCP tool config modal.                                   | Yes      |
| `headers`       | `object` | User-configured HTTP headers for MCP server authentication (from MCP tool config modal).       | No       |

#### Field Configuration Details

1. **`type`**: Always hardcoded as `"mcp"` for any MCP tool being sent
2. **`server_label`**: User-configured value from MCP tool config modal
3. **`server_url`**: User-configured value from MCP tool config modal  
4. **`allowed_tools`**: User-selected tools from MCP tool config modal
5. **`headers`**: User-configured headers from MCP tool config modal (for authentication, etc.)

#### MCP Tool Example

```json
{
  "type": "mcp",
  "server_label": "shopify",
  "server_url": "https://axzx8j-61.myshopify.com/api/mcp",
  "allowed_tools": [
    "search_shop_catalog",
    "get_product_details",
    "check_inventory"
  ],
  "headers": {
    "Authorization": "Bearer your-api-token",
    "X-Shop-Domain": "your-shop.myshopify.com",
    "Content-Type": "application/json"
  }
}
```

#### File Search Tool Configuration

File search tools provide access to vector store content for document search and retrieval. When configured, file search tools are included in requests.

**Configuration Source**: All file search tool parameters come from the user's configuration in the file search tool config modal.

| Field               | Type     | Description                                                                                      | Required |
| ------------------- | -------- | ------------------------------------------------------------------------------------------------ | -------- |
| `type`              | `string` | Always `"file_search"` for file search tools (hardcoded).                                       | Yes      |
| `vector_store_ids`  | `array`  | User-configured array containing the vector store ID from file search tool config modal.        | Yes      |

#### Field Configuration Details

1. **`type`**: Always hardcoded as `"file_search"` for any file search tool being sent
2. **`vector_store_ids`**: Array containing the user-selected vector store ID from file search tool config modal

#### File Search Tool Example

```json
{
  "type": "file_search",
  "vector_store_ids": [
    "vs_686d2325cfda5b000000"
  ]
}
```

### `input` Object Structure

Each object in the `input` array has the following structure:

| Field     | Type    | Description                                       |
| --------- | ------- | ------------------------------------------------- |
| `role`    | `string`| The role of the message sender, currently only `user`. |
| `content` | `array` | An array containing the content of the message.   |

The `content` array contains objects with `type` and `text` fields:
- `type`: "input_text"
- `text`: The user's prompt.

### `text.format` Object Structure

The `text.format` object specifies the desired output format.

| `type`         | Description                                                                                                                                                             |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `text`         | The default, for a plain text response.                                                                                                                                 |
| `json_object`  | To receive a response formatted as a JSON object.                                                                                                                       |
| `json_schema`  | To receive a response that conforms to a specific JSON schema. Requires `name` and `schema` fields.                                                                       |

### Example Request Body (Non-Streaming)

```json
{
    "model": "openai@gpt-4.1-mini",
    "instructions": "You are a helpful assistant.",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "Write a short poem about coding."
                }
            ]
        }
    ],
    "text": {
        "format": {
            "type": "text"
        }
    },
    "temperature": 1,
    "max_output_tokens": 2048,
    "top_p": 1,
    "store": true,
    "previous_response_id": "o27dGvM-zqrih-95b754a028c7918d"
}
```

### Example Request Body (Streaming)

```json
{
    "model": "openai@gpt-4.1-mini",
    "instructions": "You are a helpful assistant.",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "Write a short poem about coding."
                }
            ]
        }
    ],
    "text": {
        "format": {
            "type": "text"
        }
    },
    "temperature": 1,
    "max_output_tokens": 2048,
    "top_p": 1,
    "store": true,
    "stream": true,
    "previous_response_id": "o27dGvM-zqrih-95b754a028c7918d"
}
```

### Example Request Body (With MCP Tools)

```json
{
    "model": "openai@gpt-4.1-mini",
    "tools": [
        {
            "type": "mcp",
            "server_label": "shopify",
            "server_url": "https://axzx8j-61.myshopify.com/api/mcp",
            "allowed_tools": [
                "search_shop_catalog"
            ],
            "headers": {
                "key1": "value1",
                "key2": "value2"
            }
        }
    ],
    "instructions": "Use search_shop_catalog to search the product and then bring complete details of the product including all available images. Do not mention names of tools in the response",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "Find me hp printer"
                }
            ]
        }
    ],
    "text": {
        "format": {
            "type": "text"
        }
    },
    "temperature": 1,
    "max_output_tokens": 2048,
    "top_p": 1,
    "store": true,
    "stream": true,
    "previous_response_id": "o27cfiQ-zqrih-95b751bd4d01d854"
}
```

### Example Request Body (With File Search Tools)

```json
{
    "model": "openai@gpt-4.1-mini",
    "tools": [
        {
            "type": "file_search",
            "vector_store_ids": [
                "vs_686d2325cfda5b000000"
            ]
        }
    ],
    "instructions": "Search through the uploaded documents to find relevant information and provide a comprehensive answer based on the content.",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "What are the main features of the product described in the documentation?"
                }
            ]
        }
    ],
    "text": {
        "format": {
            "type": "text"
        }
    },
    "temperature": 1,
    "max_output_tokens": 2048,
    "top_p": 1,
    "store": true,
    "stream": true,
    "previous_response_id": "o27dGvM-zqrih-95b754a028c7918d"
}
```

### Example Request Body (With Mixed Tools)

```json
{
    "model": "openai@gpt-4.1-mini",
    "tools": [
        {
            "type": "mcp",
            "server_label": "shopify",
            "server_url": "https://axzx8j-61.myshopify.com/api/mcp",
            "allowed_tools": [
                "search_shop_catalog"
            ],
            "headers": {
                "Authorization": "Bearer your-api-token"
            }
        },
        {
            "type": "file_search",
            "vector_store_ids": [
                "vs_686d2325cfda5b000000"
            ]
        }
    ],
    "instructions": "Use both the shop catalog search and document search to provide comprehensive product information. Search the uploaded documentation first, then use the shop catalog for current pricing and availability.",
    "input": [
        {
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "Tell me about HP printers, including technical specifications and current pricing"
                }
            ]
        }
    ],
    "text": {
        "format": {
            "type": "text"
        }
    },
    "temperature": 1,
    "max_output_tokens": 2048,
    "top_p": 1,
    "store": true,
    "stream": true,
    "previous_response_id": "o27cfiQ-zqrih-95b751bd4d01d854"
}
```

## Response Handling

### Non-Streaming Response

A successful non-streaming response will be a JSON object. The two most important fields for the front-end are:

- **`id`**: A unique identifier for the response. This **must** be captured and sent as `previous_response_id` in the next request to maintain the conversation.
- **`output[0].content[0].text`**: The assistant's message content.

#### Example Success Response

```json
{
    "id": "o27dGvM-zqrih-95b754a028c7918d",
    "object": "response",
    "output": [
        {
            "content": [
                {
                    "text": "In lines of logic, dreams take flight,\nA world of syntax, dark and bright.\nWith every keystroke, futures bloom,\nEscaping confines of a room.",
                    "type": "output_text"
                }
            ],
            "role": "assistant"
        }
    ]
    // ... other fields
}
```

### Streaming Response (Server-Sent Events)

When `stream: true` is set, the response will be delivered as Server-Sent Events (SSE). The client should handle the following event types:

#### Core Event Types

1. **`response.created`** - Fired when the response is created
   - Contains the response `id` that should be captured for conversation continuity
   
2. **`response.output_text.delta`** - Fired for each text chunk during streaming
   - Contains partial text content that should be appended to the current message
   
3. **`response.output_text.done`** - Fired when text generation is complete
   - Indicates the end of the current text stream
   
4. **`response.completed`** - Fired when the entire response is complete
   - Final event in the stream

#### MCP Tool Progress Events

When MCP tools are configured and used, additional events are fired for tool execution progress:

5. **`response.mcp_call.{tool_identifier}.in_progress`** - Fired when an MCP tool starts execution
   - `{tool_identifier}` format: `{server_name}_{tool_name}` (e.g., `shopify_search_shop_catalog`)
   - Indicates the start of tool execution for visual progress tracking

6. **`response.mcp_call.{tool_identifier}.completed`** - Fired when an MCP tool completes execution
   - Same identifier format as above
   - Indicates the completion of tool execution

#### File Search Tool Progress Events

When file search tools are configured and used, additional events are fired for tool execution progress:

7. **`response.file_search.in_progress`** - Fired when a file search tool starts execution
   - Indicates the start of file search execution for visual progress tracking

8. **`response.file_search.completed`** - Fired when a file search tool completes execution
   - Indicates the completion of file search execution

#### Event Sequence Examples

**Basic Text Response**:
```
response.created → response.output_text.delta (multiple) → response.output_text.done → response.completed
```

**Response with MCP Tools**:
```
response.created → response.mcp_call.shopify_search_shop_catalog.in_progress → response.mcp_call.shopify_search_shop_catalog.completed → response.output_text.delta (multiple) → response.output_text.done → response.completed
```

**Multiple MCP Tools from Same Server**:
```
response.created → response.mcp_call.shopify_search_shop_catalog.in_progress → response.mcp_call.shopify_get_product_details.in_progress → response.mcp_call.shopify_search_shop_catalog.completed → response.mcp_call.shopify_get_product_details.completed → response.output_text.delta (multiple) → response.output_text.done → response.completed
```

**Response with File Search Tools**:
```
response.created → response.file_search.in_progress → response.file_search.completed → response.output_text.delta (multiple) → response.output_text.done → response.completed
```

**Response with Mixed MCP and File Search Tools**:
```
response.created → response.mcp_call.shopify_search_shop_catalog.in_progress → response.file_search.in_progress → response.mcp_call.shopify_search_shop_catalog.completed → response.file_search.completed → response.output_text.delta (multiple) → response.output_text.done → response.completed
```

#### Example Streaming Events

**Basic Response**:
```
event: response.created
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}

event: response.output_text.delta
data: {"output": [{"content": [{"text": "In lines", "type": "output_text"}], "role": "assistant"}]}

event: response.output_text.delta
data: {"output": [{"content": [{"text": " of logic,", "type": "output_text"}], "role": "assistant"}]}

event: response.output_text.done
data: {"output": [{"content": [{"text": "", "type": "output_text"}], "role": "assistant"}]}

event: response.completed
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}
```

**Response with MCP Tool Progress**:
```
event: response.created
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}

event: response.mcp_call.shopify_search_shop_catalog.in_progress
data: {}

event: response.mcp_call.shopify_search_shop_catalog.completed
data: {}

event: response.output_text.delta
data: {"output": [{"content": [{"text": "I found several HP printers", "type": "output_text"}], "role": "assistant"}]}

event: response.output_text.done
data: {"output": [{"content": [{"text": "", "type": "output_text"}], "role": "assistant"}]}

event: response.completed
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}
```

**Response with File Search Tool Progress**:
```
event: response.created
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}

event: response.file_search.in_progress
data: {}

event: response.file_search.completed
data: {}

event: response.output_text.delta
data: {"output": [{"content": [{"text": "Based on the documents", "type": "output_text"}], "role": "assistant"}]}

event: response.output_text.done
data: {"output": [{"content": [{"text": "", "type": "output_text"}], "role": "assistant"}]}

event: response.completed
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}
```

**Response with Mixed Tool Progress**:
```
event: response.created
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}

event: response.file_search.in_progress
data: {}

event: response.mcp_call.shopify_search_shop_catalog.in_progress
data: {}

event: response.file_search.completed
data: {}

event: response.mcp_call.shopify_search_shop_catalog.completed
data: {}

event: response.output_text.delta
data: {"output": [{"content": [{"text": "Based on the documentation and current catalog", "type": "output_text"}], "role": "assistant"}]}

event: response.output_text.done
data: {"output": [{"content": [{"text": "", "type": "output_text"}], "role": "assistant"}]}

event: response.completed
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}
```

#### Streaming Implementation Notes

1. **Response ID Capture**: The response `id` is available in both `response.created` and `response.completed` events
2. **Text Accumulation**: Each `response.output_text.delta` event contains a text chunk that should be appended to build the complete message
3. **Tool Progress Tracking**: MCP tool events should be used to show visual progress indicators grouped by server
4. **Format Detection**: The `text.format.type` from the request determines how content should be rendered:
   - `text`: Render as markdown with `react-markdown`
   - `json_object`: Apply JSON syntax highlighting during streaming
5. **Error Handling**: If an error occurs during streaming, an error event will be sent

### Error Response

If an error occurs, the response body will contain an `error` object with a `message` field.

#### Example Error Response

```json
{
    "error": {
        "message": "The requested model is not available.",
        "type": "invalid_request_error"
    }
}
```

## Front-End Implementation Details

### Content Blocks Architecture

The application uses a sequential content blocks system to handle mixed content streams (tool progress + text):

#### ContentBlock Interface
```typescript
interface ContentBlock {
  type: 'text' | 'tool_progress';
  content?: string;                    // For text blocks
  toolExecutions?: ToolExecution[];    // For tool progress blocks
}

interface ToolExecution {
  serverName: string;
  toolName: string;
  status: 'in_progress' | 'completed';
}
```

#### Content Block Flow
1. **Tool Progress Events** → Create/update `tool_progress` blocks
2. **Text Delta Events** → Create/update `text` blocks
3. **Sequential Rendering** → Blocks render in the exact order events were received

#### Event Processing Logic
```typescript
// Tool progress events
if (eventType.includes('mcp_call') && eventType.includes('in_progress')) {
  // Create or update tool progress block
  // Group all active tool executions in single block
}

// Text delta events  
if (eventType === 'response.output_text.delta') {
  // Create or update current text block
  // Maintain streaming content accumulation
}
```

### Content Rendering

The application uses enhanced content rendering with format-aware logic:

1. **Format-Based Rendering**: Content rendering is determined by the `text.format.type` from the request rather than content analysis
   - `text`: Rendered as markdown using `react-markdown` with `remark-gfm`
   - `json_object` or `json_schema`: Rendered with custom JSON syntax highlighting

2. **Markdown Support**: 
   - GitHub Flavored Markdown support with `remark-gfm`
   - Clickable links that open in new tabs with security attributes
   - Responsive images with lazy loading
   - Proper spacing for all markdown elements
   - **Link Wrapping**: Long URLs are properly wrapped within chat bubbles using `break-words` and `break-all` CSS classes

3. **JSON Syntax Highlighting**: 
   - Custom highlighting function for consistent color scheme
   - Keys highlighted in theme color (`positive-trend`)
   - Streaming-aware rendering maintains colors during partial JSON
   - Supports both complete and partial JSON during streaming

4. **Streaming Content Handling**:
   - Real-time content updates during Server-Sent Events
   - Format-consistent rendering throughout streaming process
   - No visual jumps when transitioning from streaming to complete content

### Content Rendering Logic

The `ContentRenderer` component uses a simple, reliable approach:

```typescript
interface ContentRendererProps {
  content: string;
  formatType?: 'text' | 'json_object' | 'json_schema';
}

// Rendering logic:
if (formatType === 'json_object' || formatType === 'json_schema') {
  // Render as JSON with syntax highlighting
} else {
  // Render as markdown with react-markdown
}
```

### Link Wrapping Implementation

Links are wrapped properly within chat bubbles using multiple CSS strategies:

```css
/* Container level */
.chat-bubble {
  overflow-hidden;
  break-words;
}

/* Content level */
.content-container {
  break-words;
}

/* Link level */
.markdown-link {
  break-all;
  inline-block;
  max-w-full;
  word-break: break-all;
  overflow-wrap: anywhere;
}
```

### Tool Progress Display

MCP tool execution is displayed with visual progress indicators:

1. **Server Grouping**: Tools are grouped by their server name (extracted from event identifier)
2. **Progress States**: 
   - `in_progress`: Animated spinner with tool name
   - `completed`: Static completion indicator
3. **Visual Design**: Green theme consistent with MCP branding
4. **Real-time Updates**: Progress updates live as events stream in

### Chat Bubble Features

- **Hover Effects**: Scaling and border highlighting on hover
- **Copy Functionality**: Copy button appears on hover with visual feedback
- **Consistent Styling**: Uses theme colors (`positive-trend` for accents)
- **Responsive Design**: Adapts to different screen sizes
- **Format-Aware Display**: Content rendering respects the original request format type
- **Link Overflow Protection**: Long URLs wrap properly within bubble boundaries

### State Management

The application maintains the following state for conversation continuity:

1. **`previousResponseId`**: Captured from each API response to chain conversations
2. **`messages`**: Array of chat messages with user and assistant roles
3. **`isLoading`**: Boolean to show loading states during API calls
4. **`streamingContent`**: Accumulated content during streaming responses
5. **`textFormat`**: Current format type that determines content rendering
6. **`contentBlocks`**: Sequential array of content blocks for mixed content streams
7. **`activeToolExecutions`**: Map tracking current tool execution states

### MCP Tools Integration

#### Configuration Requirements

When MCP server tools are configured, they must be included in every API request:

1. **Persistent Tool Configuration**: MCP tools persist across all requests in a conversation
2. **Server Configuration**: Each MCP server requires:
   - `server_label`: Unique identifier for the server
   - `server_url`: Complete endpoint URL for the MCP server
   - `allowed_tools`: Array of specific tool names the model can use

#### Implementation Notes

- **Configuration Source**: All MCP tool parameters are configured by users in the MCP tool config modal
- **Tool Inclusion**: MCP tools are automatically included in the `tools` array for every request based on saved configuration
- **Headers Support**: Custom HTTP headers can be configured for MCP server authentication and authorization
- **Progress Tracking**: Tool execution progress is displayed in real-time during streaming
- **Error Handling**: MCP server errors are handled within the response flow
- **Tool Responses**: Tool execution results are integrated into the streaming response

#### File Search Tool Implementation Notes

- **Configuration Source**: All file search tool parameters are configured by users in the file search tool config modal
- **Tool Inclusion**: File search tools are automatically included in the `tools` array for every request based on saved configuration
- **Vector Store Integration**: Tools reference specific vector stores containing uploaded documents
- **Automatic Detection**: Both regular file search and agentic file search tools are treated as `file_search` type in the API
- **Local Storage**: File search tool configurations are persisted in local storage using `platform_fileSearchTool` and `platform_agenticFileSearchTool` keys
- **Multiple Vector Stores**: Multiple file search tools can be configured for different vector stores simultaneously

#### Best Practices

1. **Tool Naming**: Use descriptive `server_label` values for MCP tools and meaningful vector store names for file search tools
2. **Security**: Ensure MCP server URLs are secure and authenticated; manage vector store access appropriately
3. **Tool Selection**: Only include necessary tools in `allowed_tools` for MCP servers and configure file search tools with relevant vector stores
4. **Instructions**: Provide clear instructions about tool usage and response formatting for both MCP and file search capabilities
5. **Progress Display**: Implement proper visual feedback for tool execution states
6. **Vector Store Management**: Organize documents in vector stores logically for optimal file search results
7. **Mixed Tool Usage**: When using both MCP and file search tools, provide clear instructions on how they should be used together

### API Integration Flow

1. **Request Preparation**: Build request body with current settings and previous response ID
2. **MCP Tools Integration**: Include configured MCP tools in every request
3. **Format Type Tracking**: Store `text.format.type` for proper content rendering
4. **Streaming Setup**: If streaming is enabled, set up EventSource for SSE
5. **Response Processing**: Handle different event types and update UI accordingly
6. **Content Block Management**: Create and update content blocks based on event sequence
7. **Tool Progress Tracking**: Display real-time tool execution progress
8. **Content Rendering**: Apply format-specific rendering based on request format type
9. **State Updates**: Update conversation state and prepare for next interaction
10. **Error Handling**: Display appropriate error messages and maintain application state

### Configuration Management

The application supports dynamic configuration through:

- **Model Selection**: Different providers and models with appropriate API keys
- **Parameter Tuning**: Temperature, max tokens, top-p via configuration panel
- **Format Selection**: Text, JSON object, or JSON schema output formats with proper rendering
- **Streaming Toggle**: Enable/disable streaming per request
- **MCP Tools Configuration**: Configure MCP servers and allowed tools for enhanced capabilities

### Content Rendering Best Practices

1. **Format Consistency**: Always use the request format type to determine rendering method
2. **Streaming Support**: Maintain visual consistency during streaming for all format types
3. **Link Security**: External links include `target="_blank"` and `rel="noopener noreferrer"`
4. **Image Optimization**: Lazy loading and responsive sizing for better performance
5. **Error Handling**: Graceful fallbacks for malformed content or rendering issues
6. **Link Wrapping**: Implement proper CSS strategies to prevent URL overflow
7. **Tool Progress**: Provide clear visual feedback for tool execution states

### Recent Improvements

**Content Blocks Architecture (Latest Update)**:
- Introduced sequential content blocks system for mixed content streams
- Implemented real-time tool progress tracking with visual indicators
- Enhanced event processing to handle tool progress and text content simultaneously
- Fixed duplicate server rendering issue in tool progress display

**Content Rendering Reliability**:
- Replaced content-based detection with format-type-based rendering
- Eliminated false positives where markdown links were treated as JSON
- Ensured consistent rendering behavior across all content types
- Simplified logic for better maintainability and reliability

**Link Wrapping Improvements**:
- Implemented comprehensive CSS-based link wrapping strategies
- Added multiple fallback approaches for maximum browser compatibility
- Ensured long URLs stay within chat bubble boundaries
- Maintained link functionality while preventing overflow

The rendering system now provides 100% reliable content formatting based on the actual API request format type, with robust tool progress tracking and proper link handling for optimal user experience. 

## File Search and Agentic File Search Tool Configuration (Local Storage)

When users configure file search or agentic file search tools, their selections are persisted in localStorage for future sessions. Only the vector store IDs and agentic parameters are stored—file associations are not persisted.

### Storage Keys and Example Structure

- `platform_fileSearchTools`:
  ```json
  {
    "type": "file_search",
    "selectedVectorStores": ["vs_123", "vs_456"]
  }
  ```
- `platform_agenticFileSearchTools`:
  ```json
  {
    "type": "agentic_search",
    "selectedVectorStores": ["vs_123", "vs_456"],
    "max_iterations": 3,
    "max_num_results": 4
  }
  ```

These configurations are used to pre-populate the modals and tool selection UI on subsequent visits. 

#### Agentic File Search Tool Example

When using the agentic file search tool, the API payload is the same as `file_search`, but includes additional parameters:

```json
{
  "type": "file_search",
  "vector_store_ids": ["vs_686d2325cfda5b000000"],
  "max_iterations": 5,
  "max_num_results": 10
}
```

- `max_iterations`: (integer) Number of agentic search iterations to perform.
- `max_num_results`: (integer) Maximum number of results to return.

> **Note:** File associations (attaching files to vector stores) are performed via separate API calls and are NOT included in the tool configuration or persisted in localStorage. 