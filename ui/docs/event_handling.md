# Event Handling Guide: Streaming API Events

This document provides detailed information about handling Server-Sent Events (SSE) from the `/v1/responses` endpoint, including content blocks architecture, tool progress tracking, and real-time UI updates.

## Overview

The streaming API delivers responses as Server-Sent Events when `stream: true` is set in the request. Events are processed sequentially to build content blocks that handle both text content and tool execution progress.

## Event Types

### Core Response Events

#### 1. `response.created`
- **Purpose**: Indicates response stream has started
- **Timing**: First event in every response
- **Data**: Contains response `id` for conversation continuity
- **Action**: Capture `id` for next request's `previous_response_id`

```typescript
// Event example
event: response.created
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}

// Handler
if (eventType === 'response.created') {
  const responseId = data.id;
  setPreviousResponseId(responseId);
}
```

#### 2. `response.output_text.delta`
- **Purpose**: Delivers streaming text content chunks
- **Timing**: Multiple events throughout text generation
- **Data**: Partial text content to append
- **Action**: Accumulate content in current text block

```typescript
// Event example
event: response.output_text.delta
data: {"output": [{"content": [{"text": "Hello world", "type": "output_text"}], "role": "assistant"}]}

// Handler
if (eventType === 'response.output_text.delta') {
  const textChunk = data.output[0].content[0].text;
  appendToCurrentTextBlock(textChunk);
}
```

#### 3. `response.output_text.done`
- **Purpose**: Signals completion of text generation
- **Timing**: After all text delta events
- **Data**: Empty text content (marks end)
- **Action**: Finalize current text block

```typescript
// Event example
event: response.output_text.done
data: {"output": [{"content": [{"text": "", "type": "output_text"}], "role": "assistant"}]}

// Handler
if (eventType === 'response.output_text.done') {
  finalizeCurrentTextBlock();
}
```

#### 4. `response.completed`
- **Purpose**: Indicates entire response is complete
- **Timing**: Final event in stream
- **Data**: Response metadata
- **Action**: Clean up streaming state

```typescript
// Event example
event: response.completed
data: {"id": "o27dGvM-zqrih-95b754a028c7918d", "object": "response"}

// Handler
if (eventType === 'response.completed') {
  setIsLoading(false);
  cleanupStreamingState();
}
```

### MCP Tool Progress Events

#### 5. `response.mcp_call.{tool_identifier}.in_progress`
- **Purpose**: Indicates MCP tool execution has started
- **Timing**: When tool begins execution
- **Identifier Format**: `{server_name}_{tool_name}` (e.g., `shopify_search_shop_catalog`)
- **Action**: Add tool to progress tracking

```typescript
// Event example
event: response.mcp_call.shopify_search_shop_catalog.in_progress
data: {}

// Handler
if (eventType.includes('mcp_call') && eventType.includes('in_progress')) {
  const [, , toolIdentifier] = eventType.split('.');
  const [serverName, ...toolNameParts] = toolIdentifier.split('_');
  const toolName = toolNameParts.join('_');
  
  addToolProgress(serverName, toolName, 'in_progress');
}
```

#### 6. `response.mcp_call.{tool_identifier}.completed`
- **Purpose**: Indicates MCP tool execution has finished
- **Timing**: When tool completes execution
- **Identifier Format**: Same as in_progress event
- **Action**: Update tool status to completed

```typescript
// Event example
event: response.mcp_call.shopify_search_shop_catalog.completed
data: {}

// Handler
if (eventType.includes('mcp_call') && eventType.includes('completed')) {
  const [, , toolIdentifier] = eventType.split('.');
  const [serverName, ...toolNameParts] = toolIdentifier.split('_');
  const toolName = toolNameParts.join('_');
  
  updateToolProgress(serverName, toolName, 'completed');
}
```

### File Search Tool Progress Events

#### 7. `response.file_search.in_progress`
- **Purpose**: Indicates file search tool execution has started
- **Timing**: When file search begins execution
- **Action**: Add file search tool to progress tracking

```typescript
// Event example
event: response.file_search.in_progress
data: {}

// Handler
if (eventType === 'response.file_search.in_progress') {
  const serverName = 'file_search';
  const toolName = 'search';
  
  addToolProgress(serverName, toolName, 'in_progress');
}
```

#### 8. `response.file_search.completed`
- **Purpose**: Indicates file search tool execution has finished
- **Timing**: When file search completes execution
- **Action**: Update file search tool status to completed

```typescript
// Event example
event: response.file_search.completed
data: {}

// Handler
if (eventType === 'response.file_search.completed') {
  const serverName = 'file_search';
  const toolName = 'search';
  
  updateToolProgress(serverName, toolName, 'completed');
}
```

## Content Blocks Architecture

### ContentBlock Interface

The system uses a sequential content blocks approach to handle mixed content streams:

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

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;           // For completed messages
  contentBlocks: ContentBlock[]; // For streaming messages
  formatType?: 'text' | 'json_object' | 'json_schema';
}
```

### Content Block Flow

1. **Tool Progress Events** → Create or update `tool_progress` blocks
2. **Text Delta Events** → Create or update `text` blocks  
3. **Sequential Rendering** → Blocks render in exact order events were received

### Block Creation Logic

```typescript
function processStreamEvent(eventType: string, data: any) {
  // Tool progress events
  if (eventType.includes('mcp_call') && eventType.includes('in_progress')) {
    createOrUpdateToolProgressBlock(eventType);
  }
  
  if (eventType.includes('mcp_call') && eventType.includes('completed')) {
    updateToolProgressBlock(eventType);
  }
  
  // Text content events
  if (eventType === 'response.output_text.delta') {
    createOrUpdateTextBlock(data.output[0].content[0].text);
  }
  
  if (eventType === 'response.output_text.done') {
    finalizeCurrentTextBlock();
  }
}

function createOrUpdateToolProgressBlock(eventType: string) {
  const [serverName, toolName] = parseToolIdentifier(eventType);
  
  // Find existing tool progress block or create new one
  let toolBlock = contentBlocks.find(block => 
    block.type === 'tool_progress' && 
    block.toolExecutions?.some(exec => exec.status === 'in_progress')
  );
  
  if (!toolBlock) {
    toolBlock = { type: 'tool_progress', toolExecutions: [] };
    contentBlocks.push(toolBlock);
  }
  
  // Add or update tool execution
  const existingExecution = toolBlock.toolExecutions?.find(exec => 
    exec.serverName === serverName && exec.toolName === toolName
  );
  
  if (existingExecution) {
    existingExecution.status = 'in_progress';
  } else {
    toolBlock.toolExecutions?.push({
      serverName,
      toolName,
      status: 'in_progress'
    });
  }
}

function createOrUpdateTextBlock(textChunk: string) {
  // Find current text block or create new one
  let textBlock = contentBlocks.find(block => 
    block.type === 'text' && block.content !== undefined
  );
  
  if (!textBlock) {
    textBlock = { type: 'text', content: '' };
    contentBlocks.push(textBlock);
  }
  
  // Append text chunk
  textBlock.content += textChunk;
}
```

## Event Sequence Patterns

### Basic Text Response
```
response.created 
→ response.output_text.delta (multiple)
→ response.output_text.done 
→ response.completed
```

**Content Blocks Result**:
- Single `text` block with accumulated content

### Response with Single MCP Tool
```
response.created 
→ response.mcp_call.shopify_search_shop_catalog.in_progress
→ response.mcp_call.shopify_search_shop_catalog.completed
→ response.output_text.delta (multiple)
→ response.output_text.done 
→ response.completed
```

**Content Blocks Result**:
- `tool_progress` block with shopify server execution
- `text` block with generated response

### Response with Multiple MCP Tools
```
response.created 
→ response.mcp_call.shopify_search_shop_catalog.in_progress
→ response.mcp_call.shopify_get_product_details.in_progress
→ response.mcp_call.shopify_search_shop_catalog.completed
→ response.mcp_call.shopify_get_product_details.completed
→ response.output_text.delta (multiple)
→ response.output_text.done 
→ response.completed
```

**Content Blocks Result**:
- Single `tool_progress` block with multiple shopify tool executions
- `text` block with generated response

### Response with File Search Tools
```
response.created 
→ response.file_search.in_progress
→ response.file_search.completed
→ response.output_text.delta (multiple)
→ response.output_text.done 
→ response.completed
```

**Content Blocks Result**:
- `tool_progress` block with file search execution
- `text` block with generated response

### Response with Mixed MCP and File Search Tools
```
response.created 
→ response.mcp_call.shopify_search_shop_catalog.in_progress
→ response.file_search.in_progress
→ response.mcp_call.shopify_search_shop_catalog.completed
→ response.file_search.completed
→ response.output_text.delta (multiple)
→ response.output_text.done 
→ response.completed
```

**Content Blocks Result**:
- Single `tool_progress` block with both MCP and file search tool executions
- `text` block with generated response

### Interleaved Tools and Text
```
response.created 
→ response.mcp_call.shopify_search_shop_catalog.in_progress
→ response.mcp_call.shopify_search_shop_catalog.completed
→ response.output_text.delta (some text)
→ response.mcp_call.inventory_check_stock.in_progress
→ response.mcp_call.inventory_check_stock.completed
→ response.output_text.delta (more text)
→ response.output_text.done 
→ response.completed
```

**Content Blocks Result**:
- `tool_progress` block with shopify execution
- `text` block with partial content
- `tool_progress` block with inventory execution  
- `text` block with remaining content

## Tool Progress Tracking

### Tool Identifier Parsing

```typescript
function parseToolIdentifier(eventType: string): [string, string] {
  // Extract tool identifier from event type
  // Format: response.mcp_call.{server_name}_{tool_name}.{status}
  const parts = eventType.split('.');
  const toolIdentifier = parts[2]; // e.g., "shopify_search_shop_catalog"
  
  // Split on first underscore to separate server from tool
  const underscoreIndex = toolIdentifier.indexOf('_');
  const serverName = toolIdentifier.substring(0, underscoreIndex);
  const toolName = toolIdentifier.substring(underscoreIndex + 1);
  
  return [serverName, toolName];
}
```

### Progress State Management

```typescript
interface ToolProgressState {
  activeExecutions: Map<string, ToolExecution>;
  completedExecutions: Map<string, ToolExecution>;
}

function updateToolProgress(serverName: string, toolName: string, status: 'in_progress' | 'completed') {
  const key = `${serverName}_${toolName}`;
  
  if (status === 'in_progress') {
    activeExecutions.set(key, { serverName, toolName, status });
  } else if (status === 'completed') {
    const execution = activeExecutions.get(key);
    if (execution) {
      execution.status = 'completed';
      completedExecutions.set(key, execution);
      activeExecutions.delete(key);
    }
  }
}
```

### Visual Progress Components

```typescript
function ToolExecutionProgress({ toolExecutions }: { toolExecutions: ToolExecution[] }) {
  // Group executions by server
  const serverGroups = toolExecutions.reduce((groups, execution) => {
    if (!groups[execution.serverName]) {
      groups[execution.serverName] = [];
    }
    groups[execution.serverName].push(execution);
    return groups;
  }, {} as Record<string, ToolExecution[]>);

  return (
    <div className="tool-progress-container">
      {Object.entries(serverGroups).map(([serverName, executions]) => (
        <div key={serverName} className="server-group">
          <div className="server-header">
            <Server className="server-icon" />
            <span>{serverName}</span>
          </div>
          {executions.map((execution) => (
            <div key={`${execution.serverName}_${execution.toolName}`} className="tool-execution">
              {execution.status === 'in_progress' ? (
                <Loader2 className="animate-spin tool-spinner" />
              ) : (
                <CheckCircle className="tool-completed" />
              )}
              <span>{execution.toolName}</span>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
```

## Real-Time UI Updates

### EventSource Setup

```typescript
function setupEventSource(apiUrl: string, requestBody: any) {
  const eventSource = new EventSource(apiUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${apiKey}`
    },
    body: JSON.stringify(requestBody)
  });

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      const eventType = event.type;
      processStreamEvent(eventType, data);
    } catch (error) {
      console.error('Error parsing SSE data:', error);
    }
  };

  eventSource.onerror = (error) => {
    console.error('EventSource error:', error);
    eventSource.close();
    setIsLoading(false);
  };

  return eventSource;
}
```

### State Updates During Streaming

```typescript
function processStreamEvent(eventType: string, data: any) {
  switch (eventType) {
    case 'response.created':
      setPreviousResponseId(data.id);
      initializeStreamingMessage(data.id);
      break;
      
    case 'response.output_text.delta':
      const textChunk = data.output[0].content[0].text;
      appendToStreamingContent(textChunk);
      createOrUpdateTextBlock(textChunk);
      break;
      
    case 'response.output_text.done':
      finalizeCurrentTextBlock();
      break;
      
    case 'response.completed':
      finalizeStreamingMessage();
      setIsLoading(false);
      break;
      
    default:
      if (eventType.includes('mcp_call') && eventType.includes('in_progress')) {
        handleToolProgressStart(eventType);
      } else if (eventType.includes('mcp_call') && eventType.includes('completed')) {
        handleToolProgressComplete(eventType);
      }
  }
  
  // Trigger UI re-render
  setContentBlocks([...contentBlocks]);
}
```

### Content Block Rendering

```typescript
function ContentBlockRenderer({ blocks, formatType }: { 
  blocks: ContentBlock[], 
  formatType?: string 
}) {
  return (
    <div className="content-blocks">
      {blocks.map((block, index) => (
        <div key={index} className="content-block">
          {block.type === 'tool_progress' ? (
            <ToolExecutionProgress toolExecutions={block.toolExecutions || []} />
          ) : (
            <ContentRenderer 
              content={block.content || ''} 
              formatType={formatType}
            />
          )}
        </div>
      ))}
    </div>
  );
}
```

## Error Handling

### Stream Error Recovery

```typescript
function handleStreamError(error: Event) {
  console.error('Stream error:', error);
  
  // Clean up current streaming state
  setIsLoading(false);
  setStreamingContent('');
  setContentBlocks([]);
  
  // Show user-friendly error message
  setMessages(prev => [...prev, {
    id: generateId(),
    role: 'assistant',
    content: 'Sorry, there was an error processing your request. Please try again.',
    contentBlocks: []
  }]);
  
  // Close event source
  if (eventSourceRef.current) {
    eventSourceRef.current.close();
    eventSourceRef.current = null;
  }
}
```

### Tool Execution Error Handling

```typescript
function handleToolError(serverName: string, toolName: string, error: any) {
  // Update tool status to error state
  const key = `${serverName}_${toolName}`;
  const execution = activeExecutions.get(key);
  
  if (execution) {
    execution.status = 'error';
    execution.error = error.message;
  }
  
  // Continue with response processing
  // Don't block text generation due to tool errors
}
```

## Performance Optimizations

### Debounced UI Updates

```typescript
const debouncedUpdateUI = useCallback(
  debounce(() => {
    setContentBlocks([...contentBlocks]);
  }, 16), // ~60fps
  [contentBlocks]
);

function appendToStreamingContent(textChunk: string) {
  // Update content immediately
  streamingContentRef.current += textChunk;
  
  // Debounce UI updates for performance
  debouncedUpdateUI();
}
```

### Memory Management

```typescript
function cleanupStreamingState() {
  // Clear large objects to prevent memory leaks
  setStreamingContent('');
  setActiveToolExecutions(new Map());
  
  // Close event source
  if (eventSourceRef.current) {
    eventSourceRef.current.close();
    eventSourceRef.current = null;
  }
}
```

## Best Practices

### Event Processing
1. **Process events sequentially** - Don't parallelize event handling
2. **Maintain event order** - Content blocks must reflect exact event sequence
3. **Handle missing events gracefully** - Don't assume perfect event delivery
4. **Debounce UI updates** - Avoid excessive re-renders during rapid events

### Tool Progress
1. **Group by server** - Display tools from same server together
2. **Show real-time status** - Update progress indicators immediately
3. **Handle tool errors** - Don't let tool failures block text generation
4. **Clear completed tools** - Remove finished tools from active display

### Content Blocks
1. **Minimize block creation** - Reuse existing blocks when possible
2. **Preserve event order** - Blocks must appear in chronological order
3. **Handle format switching** - Support different content types within blocks
4. **Optimize rendering** - Use React keys and memoization appropriately

### Error Recovery
1. **Graceful degradation** - Continue processing despite individual event errors
2. **User feedback** - Show clear error messages for stream failures
3. **State cleanup** - Always clean up resources on errors
4. **Retry logic** - Implement reasonable retry mechanisms for transient failures 