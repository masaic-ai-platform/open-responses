# Local Storage Guide: Persistent Configuration Management

This document provides comprehensive information about localStorage implementation for persisting user configurations, MCP tool settings, and chat interface state across browser sessions.

## Overview

The application uses localStorage to maintain user preferences and configuration state across browser sessions. This includes system prompts, MCP tool configurations, selected tools, and chat interface settings.

## Storage Keys and Structure

### Primary Storage Keys

```typescript
// Core configuration keys
const STORAGE_KEYS = {
  SYSTEM_PROMPT: 'playground_system_prompt',
  SELECTED_TOOLS: 'playground_selected_tools', 
  MCP_TOOL_CONFIGS: 'playground_mcp_tool_configs',
  CHAT_RESPONSE_ID: 'playground_chat_response_id',
  STICKY_HEADER_SETTINGS: 'playground_sticky_header_settings'
} as const;
```

### Data Structure Overview

```typescript
// System prompt storage
interface SystemPromptStorage {
  prompt: string;
  lastUpdated: string; // ISO timestamp
}

// Selected tools storage
interface SelectedToolsStorage {
  selectedTools: string[]; // Array of MCP tool labels
  lastUpdated: string;
}

// MCP tool configurations storage
interface MCPToolConfigsStorage {
  configs: Record<string, MCPToolConfig>; // Label as key
  lastUpdated: string;
}

interface MCPToolConfig {
  label: string;
  serverUrl: string;
  selectedTools: string[];
  headers: Record<string, string>;
  created: string;    // ISO timestamp
  modified: string;   // ISO timestamp
}

// Chat response ID storage
interface ChatResponseStorage {
  responseId: string | null;
  conversationStarted: string; // ISO timestamp
}
```

## System Prompt Persistence

### Implementation

System prompts are automatically saved to localStorage whenever the user updates the prompt in the configuration panel, ensuring continuity across sessions.

```typescript
// Storage functions
function saveSystemPrompt(prompt: string): void {
  const promptData: SystemPromptStorage = {
    prompt,
    lastUpdated: new Date().toISOString()
  };
  
  try {
    localStorage.setItem(STORAGE_KEYS.SYSTEM_PROMPT, JSON.stringify(promptData));
  } catch (error) {
    console.error('Failed to save system prompt:', error);
  }
}

function loadSystemPrompt(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.SYSTEM_PROMPT);
    if (stored) {
      const promptData: SystemPromptStorage = JSON.parse(stored);
      return promptData.prompt;
    }
  } catch (error) {
    console.error('Failed to load system prompt:', error);
  }
  
  // Return default prompt if no stored value or error
  return 'You are a helpful assistant.';
}

// React hook integration
function useSystemPromptPersistence() {
  const [systemPrompt, setSystemPrompt] = useState(loadSystemPrompt);
  
  // Auto-save when prompt changes
  useEffect(() => {
    saveSystemPrompt(systemPrompt);
  }, [systemPrompt]);
  
  return [systemPrompt, setSystemPrompt] as const;
}
```

### Usage in Components

```typescript
// ConfigurationPanel component
function ConfigurationPanel({ onConfigChange }: ConfigurationPanelProps) {
  const [systemPrompt, setSystemPrompt] = useSystemPromptPersistence();
  
  const handlePromptChange = (newPrompt: string) => {
    setSystemPrompt(newPrompt);
    onConfigChange({ systemPrompt: newPrompt });
  };
  
  return (
    <div className="configuration-panel">
      <Textarea
        value={systemPrompt}
        onChange={(e) => handlePromptChange(e.target.value)}
        placeholder="Enter system prompt..."
      />
    </div>
  );
}
```

## MCP Tool Configuration Management

### Configuration Storage Structure

MCP tool configurations are stored using the tool label as the key, allowing for easy retrieval and editing of individual tool configurations.

```typescript
// MCP tool configuration persistence
function saveMCPToolConfig(config: MCPToolConfig): void {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.MCP_TOOL_CONFIGS);
    const configsData: MCPToolConfigsStorage = stored 
      ? JSON.parse(stored)
      : { configs: {}, lastUpdated: new Date().toISOString() };
    
    // Update or add configuration
    configsData.configs[config.label] = {
      ...config,
      modified: new Date().toISOString()
    };
    configsData.lastUpdated = new Date().toISOString();
    
    localStorage.setItem(STORAGE_KEYS.MCP_TOOL_CONFIGS, JSON.stringify(configsData));
  } catch (error) {
    console.error('Failed to save MCP tool config:', error);
  }
}

function loadMCPToolConfigs(): Record<string, MCPToolConfig> {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.MCP_TOOL_CONFIGS);
    if (stored) {
      const configsData: MCPToolConfigsStorage = JSON.parse(stored);
      return configsData.configs;
    }
  } catch (error) {
    console.error('Failed to load MCP tool configs:', error);
  }
  
  return {};
}

function deleteMCPToolConfig(label: string): void {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.MCP_TOOL_CONFIGS);
    if (stored) {
      const configsData: MCPToolConfigsStorage = JSON.parse(stored);
      delete configsData.configs[label];
      configsData.lastUpdated = new Date().toISOString();
      
      localStorage.setItem(STORAGE_KEYS.MCP_TOOL_CONFIGS, JSON.stringify(configsData));
    }
  } catch (error) {
    console.error('Failed to delete MCP tool config:', error);
  }
}
```

### Hook for MCP Configuration Management

```typescript
function useMCPToolConfigs() {
  const [configs, setConfigs] = useState<Record<string, MCPToolConfig>>(loadMCPToolConfigs);
  
  const saveConfig = useCallback((config: MCPToolConfig) => {
    saveMCPToolConfig(config);
    setConfigs(prev => ({
      ...prev,
      [config.label]: config
    }));
  }, []);
  
  const deleteConfig = useCallback((label: string) => {
    deleteMCPToolConfig(label);
    setConfigs(prev => {
      const newConfigs = { ...prev };
      delete newConfigs[label];
      return newConfigs;
    });
  }, []);
  
  const getConfigByLabel = useCallback((label: string) => {
    return configs[label] || null;
  }, [configs]);
  
  return {
    configs,
    saveConfig,
    deleteConfig,
    getConfigByLabel,
    configLabels: Object.keys(configs)
  };
}
```

### MCP Configuration Modal Integration

```typescript
// MCPModal component with persistence
function MCPModal({ isOpen, onClose, onSave }: MCPModalProps) {
  const { configs, saveConfig, deleteConfig, getConfigByLabel } = useMCPToolConfigs();
  const [editingLabel, setEditingLabel] = useState<string | null>(null);
  
  // Load configuration for editing
  const loadConfigForEditing = (label: string) => {
    const config = getConfigByLabel(label);
    if (config) {
      setLabel(config.label);
      setServerUrl(config.serverUrl);
      setSelectedTools(config.selectedTools);
      setHeaders(config.headers);
      setEditingLabel(label);
    }
  };
  
  // Save configuration
  const handleSave = () => {
    const config: MCPToolConfig = {
      label,
      serverUrl,
      selectedTools,
      headers,
      created: editingLabel ? getConfigByLabel(editingLabel)?.created || new Date().toISOString() : new Date().toISOString(),
      modified: new Date().toISOString()
    };
    
    saveConfig(config);
    onSave?.(config);
    onClose();
  };
  
  // Delete configuration
  const handleDelete = (label: string) => {
    deleteConfig(label);
    if (editingLabel === label) {
      resetForm();
    }
  };
  
  return (
    <Dialog open={isOpen} onOpenChange={onClose}>
      <DialogContent>
        {/* Configuration list */}
        <div className="saved-configs">
          {Object.values(configs).map((config) => (
            <div key={config.label} className="config-item">
              <span>{config.label}</span>
              <div className="config-actions">
                <Button onClick={() => loadConfigForEditing(config.label)}>
                  Edit
                </Button>
                <Button onClick={() => handleDelete(config.label)} variant="destructive">
                  Delete
                </Button>
              </div>
            </div>
          ))}
        </div>
        
        {/* Configuration form */}
        <div className="config-form">
          {/* Form fields */}
        </div>
      </DialogContent>
    </Dialog>
  );
}
```

## Selected Tools Persistence

### Implementation

Selected tools are persisted to maintain the user's tool selection across browser sessions, ensuring configured MCP tools remain active.

```typescript
// Selected tools persistence
function saveSelectedTools(selectedTools: string[]): void {
  const toolsData: SelectedToolsStorage = {
    selectedTools,
    lastUpdated: new Date().toISOString()
  };
  
  try {
    localStorage.setItem(STORAGE_KEYS.SELECTED_TOOLS, JSON.stringify(toolsData));
  } catch (error) {
    console.error('Failed to save selected tools:', error);
  }
}

function loadSelectedTools(): string[] {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.SELECTED_TOOLS);
    if (stored) {
      const toolsData: SelectedToolsStorage = JSON.parse(stored);
      return toolsData.selectedTools;
    }
  } catch (error) {
    console.error('Failed to load selected tools:', error);
  }
  
  return [];
}

// React hook for selected tools
function useSelectedToolsPersistence() {
  const [selectedTools, setSelectedTools] = useState<string[]>(loadSelectedTools);
  
  // Auto-save when selection changes
  useEffect(() => {
    saveSelectedTools(selectedTools);
  }, [selectedTools]);
  
  return [selectedTools, setSelectedTools] as const;
}
```

### Integration with Configuration Panel

```typescript
// ConfigurationPanel with tool selection persistence
function ConfigurationPanel({ onSelectedToolsChange }: ConfigurationPanelProps) {
  const [selectedTools, setSelectedTools] = useSelectedToolsPersistence();
  const { configs } = useMCPToolConfigs();
  
  // Validate selected tools against available configs
  useEffect(() => {
    const validTools = selectedTools.filter(tool => configs[tool]);
    if (validTools.length !== selectedTools.length) {
      setSelectedTools(validTools);
    }
  }, [configs, selectedTools, setSelectedTools]);
  
  const handleToolSelectionChange = (tools: string[]) => {
    setSelectedTools(tools);
    onSelectedToolsChange?.(tools);
  };
  
  return (
    <div className="configuration-panel">
      <ToolsSelectionModal
        selectedTools={selectedTools}
        onSelectionChange={handleToolSelectionChange}
      />
    </div>
  );
}
```

## Chat Response ID Persistence

### Implementation

The chat response ID is persisted to maintain conversation continuity across browser sessions, enabling context-aware conversations.

```typescript
// Chat response ID persistence
function saveChatResponseId(responseId: string | null): void {
  const responseData: ChatResponseStorage = {
    responseId,
    conversationStarted: responseId ? new Date().toISOString() : ''
  };
  
  try {
    localStorage.setItem(STORAGE_KEYS.CHAT_RESPONSE_ID, JSON.stringify(responseData));
  } catch (error) {
    console.error('Failed to save chat response ID:', error);
  }
}

function loadChatResponseId(): string | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.CHAT_RESPONSE_ID);
    if (stored) {
      const responseData: ChatResponseStorage = JSON.parse(stored);
      return responseData.responseId;
    }
  } catch (error) {
    console.error('Failed to load chat response ID:', error);
  }
  
  return null;
}

function clearChatResponseId(): void {
  try {
    localStorage.removeItem(STORAGE_KEYS.CHAT_RESPONSE_ID);
  } catch (error) {
    console.error('Failed to clear chat response ID:', error);
  }
}

// React hook for response ID persistence
function useChatResponsePersistence() {
  const [previousResponseId, setPreviousResponseId] = useState<string | null>(loadChatResponseId);
  
  // Auto-save when response ID changes
  useEffect(() => {
    saveChatResponseId(previousResponseId);
  }, [previousResponseId]);
  
  const resetConversation = useCallback(() => {
    setPreviousResponseId(null);
    clearChatResponseId();
  }, []);
  
  return {
    previousResponseId,
    setPreviousResponseId,
    resetConversation
  };
}
```

### Integration with Chat Component

```typescript
// Chat component with response ID persistence
function Chat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const { previousResponseId, setPreviousResponseId, resetConversation } = useChatResponsePersistence();
  
  // Handle new response
  const handleApiResponse = (response: any) => {
    if (response.id) {
      setPreviousResponseId(response.id);
    }
    
    // Add response to messages
    setMessages(prev => [...prev, {
      id: response.id,
      role: 'assistant',
      content: response.output[0].content[0].text,
      contentBlocks: []
    }]);
  };
  
  // Reset chat and conversation
  const handleResetChat = () => {
    setMessages([]);
    resetConversation();
  };
  
  return (
    <div className="chat-container">
      <ChatHeader 
        responseId={previousResponseId}
        onResetChat={handleResetChat}
      />
      <ChatMessages messages={messages} />
    </div>
  );
}
```

## Sticky Header Settings Persistence

### Implementation

Chat interface settings like sticky header preferences are persisted for consistent user experience.

```typescript
// Sticky header settings
interface StickyHeaderSettings {
  enabled: boolean;
  showResponseId: boolean;
  autoHide: boolean;
  lastUpdated: string;
}

function saveStickyHeaderSettings(settings: Partial<StickyHeaderSettings>): void {
  try {
    const currentSettings = loadStickyHeaderSettings();
    const updatedSettings: StickyHeaderSettings = {
      ...currentSettings,
      ...settings,
      lastUpdated: new Date().toISOString()
    };
    
    localStorage.setItem(STORAGE_KEYS.STICKY_HEADER_SETTINGS, JSON.stringify(updatedSettings));
  } catch (error) {
    console.error('Failed to save sticky header settings:', error);
  }
}

function loadStickyHeaderSettings(): StickyHeaderSettings {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.STICKY_HEADER_SETTINGS);
    if (stored) {
      return JSON.parse(stored);
    }
  } catch (error) {
    console.error('Failed to load sticky header settings:', error);
  }
  
  // Default settings
  return {
    enabled: true,
    showResponseId: true,
    autoHide: false,
    lastUpdated: new Date().toISOString()
  };
}
```

## File Search and Agentic File Search Tool Configuration Persistence

### Storage Keys

- `platform_fileSearchTools`: Stores the configuration for the File Search tool
- `platform_agenticFileSearchTools`: Stores the configuration for the Agentic File Search tool

### Data Structure

```json
// File Search Tool
{
  "type": "file_search",
  "selectedVectorStores": ["vs_123", "vs_456"]
}

// Agentic File Search Tool
{
  "type": "agentic_search",
  "selectedVectorStores": ["vs_123", "vs_456"],
  "max_iterations": 3,
  "max_num_results": 4
}
```

### Usage

- When a user saves the configuration in the File Search modal, the selected vector store IDs are saved to `platform_fileSearchTools` in the above format.
- When a user saves the configuration in the Agentic File Search modal, the selected vector store IDs, max iterations, and max number of results are saved to `platform_agenticFileSearchTools` in the above format.
- No file IDs or file associations are stored in localStorage; only the vector store configuration is persisted.

### Example

```javascript
// Save File Search configuration
localStorage.setItem('platform_fileSearchTools', JSON.stringify({
  type: 'file_search',
  selectedVectorStores: ['vs_abc', 'vs_xyz']
}));

// Save Agentic File Search configuration
localStorage.setItem('platform_agenticFileSearchTools', JSON.stringify({
  type: 'agentic_search',
  selectedVectorStores: ['vs_abc', 'vs_xyz'],
  max_iterations: 5,
  max_num_results: 10
}));
```

## Data Validation and Migration

### Storage Version Management

```typescript
const STORAGE_VERSION = '1.0';
const VERSION_KEY = 'playground_storage_version';

function validateStorageVersion(): boolean {
  try {
    const storedVersion = localStorage.getItem(VERSION_KEY);
    return storedVersion === STORAGE_VERSION;
  } catch (error) {
    return false;
  }
}

function initializeStorage(): void {
  if (!validateStorageVersion()) {
    // Clear old storage format if version mismatch
    clearDeprecatedStorage();
    localStorage.setItem(VERSION_KEY, STORAGE_VERSION);
  }
}

function clearDeprecatedStorage(): void {
  const keysToRemove = [
    'old_system_prompt_key',
    'old_tool_configs_key',
    // Add other deprecated keys
  ];
  
  keysToRemove.forEach(key => {
    try {
      localStorage.removeItem(key);
    } catch (error) {
      console.error(`Failed to remove deprecated key ${key}:`, error);
    }
  });
}
```

### Data Validation

```typescript
function validateMCPToolConfig(config: any): config is MCPToolConfig {
  return (
    typeof config === 'object' &&
    typeof config.label === 'string' &&
    typeof config.serverUrl === 'string' &&
    Array.isArray(config.selectedTools) &&
    typeof config.headers === 'object' &&
    typeof config.created === 'string' &&
    typeof config.modified === 'string'
  );
}

function sanitizeMCPToolConfigs(configs: any): Record<string, MCPToolConfig> {
  if (typeof configs !== 'object' || !configs) {
    return {};
  }
  
  const sanitized: Record<string, MCPToolConfig> = {};
  
  Object.entries(configs).forEach(([key, config]) => {
    if (validateMCPToolConfig(config)) {
      sanitized[key] = config;
    }
  });
  
  return sanitized;
}
```

## Error Handling and Fallbacks

### Storage Quota Management

```typescript
function getStorageUsage(): { used: number; total: number } {
  try {
    let totalSize = 0;
    for (const key in localStorage) {
      if (localStorage.hasOwnProperty(key)) {
        totalSize += localStorage.getItem(key)?.length || 0;
      }
    }
    
    return {
      used: totalSize,
      total: 5 * 1024 * 1024 // 5MB typical limit
    };
  } catch (error) {
    return { used: 0, total: 0 };
  }
}

function isStorageAvailable(): boolean {
  try {
    const test = '__storage_test__';
    localStorage.setItem(test, test);
    localStorage.removeItem(test);
    return true;
  } catch (error) {
    return false;
  }
}
```

### Graceful Degradation

```typescript
function safeStorageOperation<T>(
  operation: () => T,
  fallback: T,
  errorMessage: string
): T {
  try {
    if (!isStorageAvailable()) {
      console.warn('localStorage not available, using fallback');
      return fallback;
    }
    
    return operation();
  } catch (error) {
    console.error(errorMessage, error);
    return fallback;
  }
}

// Usage example
function saveSystemPromptSafely(prompt: string): void {
  safeStorageOperation(
    () => saveSystemPrompt(prompt),
    undefined,
    'Failed to save system prompt to localStorage'
  );
}
```

## Performance Optimization

### Debounced Storage Operations

```typescript
function createDebouncedStorage<T>(
  key: string,
  delay: number = 500
): (data: T) => void {
  let timeoutId: NodeJS.Timeout;
  
  return (data: T) => {
    clearTimeout(timeoutId);
    timeoutId = setTimeout(() => {
      try {
        localStorage.setItem(key, JSON.stringify(data));
      } catch (error) {
        console.error(`Failed to save ${key}:`, error);
      }
    }, delay);
  };
}

// Usage for frequently updated data
const debouncedSaveSystemPrompt = createDebouncedStorage<string>(
  STORAGE_KEYS.SYSTEM_PROMPT,
  1000
);
```

### Lazy Loading

```typescript
function createLazyLoader<T>(
  key: string,
  defaultValue: T,
  validator?: (data: any) => data is T
): () => T {
  let cached: T | null = null;
  let loaded = false;
  
  return () => {
    if (!loaded) {
      try {
        const stored = localStorage.getItem(key);
        if (stored) {
          const parsed = JSON.parse(stored);
          if (!validator || validator(parsed)) {
            cached = parsed;
          } else {
            cached = defaultValue;
          }
        } else {
          cached = defaultValue;
        }
      } catch (error) {
        console.error(`Failed to load ${key}:`, error);
        cached = defaultValue;
      }
      loaded = true;
    }
    
    return cached!;
  };
}
```

## Best Practices

### Storage Best Practices

1. **Key Naming**: Use consistent, descriptive prefixes for all storage keys
2. **Data Structure**: Include metadata like timestamps and version information
3. **Validation**: Always validate data when loading from localStorage
4. **Error Handling**: Implement graceful fallbacks for storage failures
5. **Performance**: Debounce frequent updates and implement lazy loading

### Security Considerations

1. **Sensitive Data**: Never store API keys or sensitive authentication data
2. **Data Sanitization**: Validate and sanitize all data loaded from localStorage
3. **Size Limits**: Monitor storage usage and implement cleanup strategies
4. **Privacy**: Respect user privacy and provide clear data management options

### User Experience

1. **Persistence Transparency**: Make it clear when data is automatically saved
2. **Manual Controls**: Provide options to clear stored data
3. **Export/Import**: Consider providing data export/import functionality
4. **Conflict Resolution**: Handle cases where stored data conflicts with current state

### Maintenance

1. **Version Management**: Implement storage schema versioning
2. **Migration Scripts**: Provide smooth migration paths for storage updates
3. **Cleanup**: Regularly clean up deprecated or invalid stored data
4. **Monitoring**: Track storage usage and error rates in production 

> **Note:** Only vector store configuration is persisted. File associations (attaching files to vector stores) are handled via API and are not stored in localStorage. 