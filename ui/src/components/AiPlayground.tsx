import React, { useState, useRef, useEffect } from 'react';
import { Drawer, DrawerTrigger, DrawerContent } from '@/components/ui/drawer';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Card } from '@/components/ui/card';
import UnifiedCard from '@/components/ui/unified-card';
import { Loader2, Send, Sparkles, RotateCcw, Copy, Check, Menu, Code, Brain, Image, Puzzle, Save, Layers } from 'lucide-react';
import { toast } from 'sonner';
import ChatMessage from './ChatMessage';
import ConfigurationPanel from './ConfigurationPanel';
import PlaygroundSidebar from './PlaygroundSidebar';
import CodeTabs from '@/playground/CodeTabs';
import { PlaygroundRequest } from '@/playground/PlaygroundRequest';
import { API_URL } from '@/config';
import { apiClient } from '@/lib/api';
import { usePlatformInfo } from '@/contexts/PlatformContext';

interface ToolExecution {
  serverName: string;
  toolName: string;
  status: 'in_progress' | 'completed';
  agenticSearchLogs?: AgenticSearchLog[];
}

interface AgenticSearchLog {
  iteration: number;
  query: string;
  reasoning: string;
  citations: string[];
  remaining_iterations: number;
}

interface ContentBlock {
  type: 'text' | 'tool_progress' | 'inline_loading';
  content?: string;
  toolExecutions?: ToolExecution[];
}

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  contentBlocks?: ContentBlock[];
  type: 'text' | 'image';
  timestamp: Date;
  hasThinkTags?: boolean;
  isLoading?: boolean;
}

interface PromptMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
}

interface Tool {
  id: string;
  name: string;
  icon: React.ComponentType<{ className?: string }>;
  functionDefinition?: string; // For function tools
  mcpConfig?: any; // For MCP server tools
  fileSearchConfig?: { selectedFiles: string[]; selectedVectorStores: string[]; vectorStoreNames: string[] }; // For file search tools
  agenticFileSearchConfig?: { selectedFiles: string[]; selectedVectorStores: string[]; vectorStoreNames: string[]; iterations: number; maxResults: number }; // For agentic file search tools
}

const getProviderApiKey = (provider: string): string => {
  try {
    const saved = localStorage.getItem('platform_apiKeys');
    if (!saved) return '';
    const savedKeys: { name: string; apiKey: string }[] = JSON.parse(saved);
    return savedKeys.find(item => item.name === provider)?.apiKey || '';
  } catch {
    return '';
  }
};

const isValidUrl = (url: string): boolean => {
  try {
    const urlObj = new URL(url);
    return urlObj.protocol === 'http:' || urlObj.protocol === 'https:';
  } catch {
    return false;
  }
};

const AiPlayground: React.FC = () => {
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('http://localhost:8080');
  const [modelProvider, setModelProvider] = useState('openai');
  const [modelName, setModelName] = useState('gpt-4o');
  const [imageModelProvider, setImageModelProvider] = useState('gemini');
  const [imageModelName, setImageModelName] = useState('imagen-3.0-generate-002');
  const [imageProviderKey, setImageProviderKey] = useState('');
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [previousResponseId, setPreviousResponseId] = useState<string | null>(null);
  const [selectedVectorStore, setSelectedVectorStore] = useState<string>('');
  const [instructions, setInstructions] = useState('');
  
  // Configuration parameters for AI model
  const [temperature, setTemperature] = useState(1.0);
  const [maxTokens, setMaxTokens] = useState(2048);
  const [topP, setTopP] = useState(1.0);
  const [storeLogs, setStoreLogs] = useState(true);
  const [textFormat, setTextFormat] = useState<'text' | 'json_object' | 'json_schema'>('text');
  const [toolChoice, setToolChoice] = useState<'auto' | 'none'>('auto');
  
  // Prompt messages state
  const [promptMessages, setPromptMessages] = useState<PromptMessage[]>([]);
  
  // Tools state
  const [selectedTools, setSelectedTools] = useState<Tool[]>([]);
  
  // Playground state
  const [activeTab, setActiveTab] = useState('responses');
  const [apiKeysModalOpen, setApiKeysModalOpen] = useState(false);
  
  const [jsonSchemaContent, setJsonSchemaContent] = useState('');
  const [jsonSchemaName, setJsonSchemaName] = useState<string | null>(null);

  // Masaic Mocky mode state
  const [mockyMode, setMockyMode] = useState(false);
  const [mockyAgentData, setMockyAgentData] = useState<null | { systemPrompt: string; greetingMessage: string; description: string; tools: any[] }>(null);

  // Model Test Agent mode state
  const [modelTestMode, setModelTestMode] = useState(false);
  const [modelTestAgentData, setModelTestAgentData] = useState<null | { systemPrompt: string; greetingMessage: string; userMessage: string; tools: any[] }>(null);
  const [modelTestUrl, setModelTestUrl] = useState('');
  const [modelTestName, setModelTestName] = useState('');
  const [modelTestApiKey, setModelTestApiKey] = useState('');
  const [isTestingModel, setIsTestingModel] = useState(false);
  const [showSaveModel, setShowSaveModel] = useState(false);
  const [saveModelState, setSaveModelState] = useState<'success' | 'tool_issue' | 'error' | null>(null);

  // Chat header state
  const [copiedResponseId, setCopiedResponseId] = useState(false);
  
  // Code snippet generator state
  const [codeModalOpen, setCodeModalOpen] = useState(false);
  const [lastRequest, setLastRequest] = useState<PlaygroundRequest | null>(null);
  
  // Import API_URL from central config
  const apiUrl = API_URL;

  const messagesEndRef = useRef<HTMLDivElement>(null);

  const { platformInfo } = usePlatformInfo();
  const modelSettings = platformInfo?.modelSettings;

  useEffect(() => {
    // Load saved settings from localStorage
    const savedBaseUrl = localStorage.getItem('aiPlayground_baseUrl') || 'http://localhost:8080';
    const savedModelProvider = localStorage.getItem('platform_modelProvider') || 'openai';
    const savedModelName = localStorage.getItem('platform_modelName') || 'gpt-4o';
    const savedImageModelProvider = localStorage.getItem('aiPlayground_imageModelProvider') || 'gemini';
    const savedImageModelName = localStorage.getItem('aiPlayground_imageModelName') || 'imagen-3.0-generate-002';
    const savedImageProviderKey = localStorage.getItem('aiPlayground_imageProviderKey') || '';
    const savedSelectedVectorStore = localStorage.getItem('aiPlayground_selectedVectorStore') || '';
    const savedInstructions = localStorage.getItem('platform_instructions') || '';
    const savedTemperature = parseFloat(localStorage.getItem('aiPlayground_temperature') || '1.0');
    const savedMaxTokens = parseInt(localStorage.getItem('aiPlayground_maxTokens') || '2048');
    const savedTopP = parseFloat(localStorage.getItem('aiPlayground_topP') || '1.0');
    const savedStoreLogs = localStorage.getItem('aiPlayground_storeLogs') === 'true';
    const savedTextFormat = (localStorage.getItem('aiPlayground_textFormat') || 'text') as 'text' | 'json_object' | 'json_schema';
    const savedToolChoice = (localStorage.getItem('aiPlayground_toolChoice') || 'auto') as 'auto' | 'none';
    const savedPromptMessages = JSON.parse(localStorage.getItem('aiPlayground_promptMessages') || '[]');
    const savedOtherToolsRaw = JSON.parse(localStorage.getItem('aiPlayground_otherTools') || '[]');

    // Helper to map tool id to its icon component
    const getIconForTool = (id: string) => {
      switch (id) {
        case 'image_generation':
          return Image;
        case 'think':
          return Brain;
        case 'fun_req_gathering_tool':
          return Puzzle;
        case 'fun_def_generation_tool':
          return Code;
        case 'mock_fun_save_tool':
          return Save;
        case 'mock_generation_tool':
          return Layers;
        case 'mock_save_tool':
          return Save;
        default:
          return Code; // fallback
      }
    };

    const savedOtherTools = savedOtherToolsRaw.map((tool: any) => ({
      ...tool,
      icon: getIconForTool(tool.id)
    }));
    const savedMCPTools = loadMCPToolsFromStorage();
    const savedFileSearchTools = loadFileSearchToolsFromStorage();
    const savedAgenticFileSearchTools = loadAgenticFileSearchToolsFromStorage();
    
    // Don't load apiKey from localStorage - it's managed by getProviderApiKey function
    setBaseUrl(savedBaseUrl);
    setModelProvider(savedModelProvider);
    setModelName(savedModelName);
    setImageModelProvider(savedImageModelProvider);
    setImageModelName(savedImageModelName);
    setImageProviderKey(savedImageProviderKey);
    setSelectedVectorStore(savedSelectedVectorStore);
    setInstructions(savedInstructions);
    setTemperature(savedTemperature);
    setMaxTokens(savedMaxTokens);
    setTopP(savedTopP);
    setStoreLogs(savedStoreLogs);
    setTextFormat(savedTextFormat);
    setToolChoice(savedToolChoice);
    setPromptMessages(savedPromptMessages);
    setSelectedTools([...savedOtherTools, ...savedMCPTools, ...savedFileSearchTools, ...savedAgenticFileSearchTools]);
  }, []);

  // Save settings to localStorage whenever they change
  useEffect(() => {
    // Don't save platform_apiKeys here - it's managed by ApiKeysModal
    localStorage.setItem('aiPlayground_baseUrl', baseUrl);
    localStorage.setItem('platform_modelProvider', modelProvider);
    localStorage.setItem('platform_modelName', modelName);
    localStorage.setItem('aiPlayground_imageModelProvider', imageModelProvider);
    localStorage.setItem('aiPlayground_imageModelName', imageModelName);
    localStorage.setItem('aiPlayground_imageProviderKey', imageProviderKey);
    localStorage.setItem('aiPlayground_selectedVectorStore', selectedVectorStore);
    localStorage.setItem('platform_instructions', instructions);
    localStorage.setItem('aiPlayground_temperature', temperature.toString());
    localStorage.setItem('aiPlayground_maxTokens', maxTokens.toString());
    localStorage.setItem('aiPlayground_topP', topP.toString());
    localStorage.setItem('aiPlayground_storeLogs', storeLogs.toString());
    localStorage.setItem('aiPlayground_textFormat', textFormat);
    localStorage.setItem('aiPlayground_toolChoice', toolChoice);
    localStorage.setItem('aiPlayground_promptMessages', JSON.stringify(promptMessages));
    
    // Separate tools by type for better management
    const mcpTools = selectedTools.filter(tool => tool.id === 'mcp_server');
    const fileSearchTools = selectedTools.filter(tool => tool.id === 'file_search');
    const agenticFileSearchTools = selectedTools.filter(tool => tool.id === 'agentic_file_search');
    const otherTools = selectedTools.filter(tool => 
      tool.id !== 'mcp_server' && 
      tool.id !== 'file_search' && 
      tool.id !== 'agentic_file_search'
    );
    
    saveMCPToolsToStorage(mcpTools);
    saveFileSearchToolsToStorage(fileSearchTools);
    saveAgenticFileSearchToolsToStorage(agenticFileSearchTools);
    localStorage.setItem('aiPlayground_otherTools', JSON.stringify(otherTools));
  }, [apiKey, baseUrl, modelProvider, modelName, imageModelProvider, imageModelName, imageProviderKey, selectedVectorStore, instructions, temperature, maxTokens, topP, storeLogs, textFormat, toolChoice, promptMessages, selectedTools]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const resetConversation = () => {
    setMessages([]);
    setConversationId(null);
    setPreviousResponseId(null);
    toast.success('Conversation reset');
  };

  // Function to clear all localStorage data (for testing/debugging)
  const clearAllStorageData = () => {
    const keys = Object.keys(localStorage).filter(key => 
      key.startsWith('aiPlayground_') || key.startsWith('platform_')
    );
    keys.forEach(key => localStorage.removeItem(key));
    toast.success('All storage data cleared');
  };

  const handleVectorStoreSelect = (vectorStoreId: string | null) => {
    setSelectedVectorStore(vectorStoreId || '');
    localStorage.setItem('aiPlayground_selectedVectorStore', vectorStoreId || '');
    
    if (vectorStoreId) {
      toast.success('Vector store selected for file search');
    } else {
      toast.success('Vector store deselected');
    }
  };

  const handleAddPromptMessage = (message: PromptMessage) => {
    setPromptMessages(prev => [...prev, message]);
    toast.success(`${message.role === 'user' ? 'User' : 'Assistant'} message added to prompt`);
  };

  const handleRemovePromptMessage = (id: string) => {
    setPromptMessages(prev => prev.filter(msg => msg.id !== id));
    toast.success('Message removed from prompt');
  };

  const handleCopyResponseId = async () => {
    if (!previousResponseId) return;
    
    try {
      await navigator.clipboard.writeText(previousResponseId);
      setCopiedResponseId(true);
      setTimeout(() => setCopiedResponseId(false), 2000);
      toast.success('Response ID copied to clipboard');
    } catch (err) {
      console.error('Failed to copy response ID:', err);
      toast.error('Failed to copy response ID');
    }
  };

  // Enhanced MCP tools persistence using label as key
  const saveMCPToolsToStorage = (tools: Tool[]) => {
    const mcpTools = tools.filter(tool => tool.id === 'mcp_server' && tool.mcpConfig?.label);
    const mcpToolsMap: Record<string, any> = {};
    
    mcpTools.forEach(tool => {
      const label = tool.mcpConfig.label;
      mcpToolsMap[label] = {
        label: tool.mcpConfig.label,
        url: tool.mcpConfig.url,
        authentication: tool.mcpConfig.authentication,
        accessToken: tool.mcpConfig.accessToken,
        customHeaders: tool.mcpConfig.customHeaders,
        selectedTools: tool.mcpConfig.selectedTools
      };
    });
    
    localStorage.setItem('platform_mcpTools', JSON.stringify(mcpToolsMap));
  };

  const loadMCPToolsFromStorage = (): Tool[] => {
    try {
      const stored = localStorage.getItem('platform_mcpTools');
      if (!stored) return [];
      
      const mcpToolsMap = JSON.parse(stored);
      const mcpTools: Tool[] = [];
      
      Object.values(mcpToolsMap).forEach((config: any) => {
        if (config.label) {
          mcpTools.push({
            id: 'mcp_server',
            name: config.label,
            icon: () => null, // Icon will be set by the component
            mcpConfig: config
          });
        }
      });
      
      return mcpTools;
    } catch (error) {
      console.error('Error loading MCP tools from storage:', error);
      return [];
    }
  };

  const getMCPToolByLabel = (label: string) => {
    try {
      const stored = localStorage.getItem('platform_mcpTools');
      if (!stored) return null;
      
      const mcpToolsMap = JSON.parse(stored);
      return mcpToolsMap[label] || null;
    } catch (error) {
      console.error('Error getting MCP tool by label:', error);
      return null;
    }
  };

  // File search tools persistence
  const loadFileSearchToolsFromStorage = (): Tool[] => {
    try {
      const stored = localStorage.getItem('platform_fileSearchTools');
      if (!stored) return [];
      
      const fileSearchToolsMap = JSON.parse(stored);
      const fileSearchTools: Tool[] = [];
      
      Object.values(fileSearchToolsMap).forEach((config: any) => {
        if (config.selectedVectorStores && config.vectorStoreNames && config.selectedVectorStores.length > 0) {
          const displayName = config.vectorStoreNames.join(', ');
          fileSearchTools.push({
            id: 'file_search',
            name: displayName,
            icon: () => null, // Icon will be set by the component
            fileSearchConfig: {
              selectedFiles: config.selectedFiles,
              selectedVectorStores: config.selectedVectorStores,
              vectorStoreNames: config.vectorStoreNames
            }
          });
        }
      });
      
      return fileSearchTools;
    } catch (error) {
      console.error('Error loading file search tools from storage:', error);
      return [];
    }
  };

  // Agentic file search tools persistence
  const loadAgenticFileSearchToolsFromStorage = (): Tool[] => {
    try {
      const stored = localStorage.getItem('platform_agenticFileSearchTools');
      if (!stored) return [];
      
      const agenticFileSearchToolsMap = JSON.parse(stored);
      const agenticFileSearchTools: Tool[] = [];
      
      Object.values(agenticFileSearchToolsMap).forEach((config: any) => {
        if (config.selectedVectorStores && config.vectorStoreNames && config.selectedVectorStores.length > 0) {
          const displayName = config.vectorStoreNames.join(', ');
          agenticFileSearchTools.push({
            id: 'agentic_file_search',
            name: displayName,
            icon: () => null, // Icon will be set by the component
            agenticFileSearchConfig: {
              selectedFiles: config.selectedFiles,
              selectedVectorStores: config.selectedVectorStores,
              vectorStoreNames: config.vectorStoreNames,
              iterations: config.iterations,
              maxResults: config.maxResults || 4 // Default to 4 if not set
            }
          });
        }
      });
      
      return agenticFileSearchTools;
    } catch (error) {
      console.error('Error loading agentic file search tools from storage:', error);
      return [];
    }
  };

  const saveFileSearchToolsToStorage = (tools: Tool[]) => {
    const fileSearchTools = tools.filter(tool => tool.id === 'file_search' && tool.fileSearchConfig?.selectedVectorStores && tool.fileSearchConfig.selectedVectorStores.length > 0);
    const fileSearchToolsMap: Record<string, any> = {};
    
    fileSearchTools.forEach(tool => {
      const combinedKey = tool.fileSearchConfig!.selectedVectorStores.sort().join('|');
      fileSearchToolsMap[combinedKey] = {
        selectedFiles: tool.fileSearchConfig!.selectedFiles,
        selectedVectorStores: tool.fileSearchConfig!.selectedVectorStores,
        vectorStoreNames: tool.fileSearchConfig!.vectorStoreNames,
        lastUpdated: new Date().toISOString()
      };
    });
    
    localStorage.setItem('platform_fileSearchTools', JSON.stringify(fileSearchToolsMap));
  };

  const saveAgenticFileSearchToolsToStorage = (tools: Tool[]) => {
    const agenticFileSearchTools = tools.filter(tool => tool.id === 'agentic_file_search' && tool.agenticFileSearchConfig?.selectedVectorStores && tool.agenticFileSearchConfig.selectedVectorStores.length > 0);
    const agenticFileSearchToolsMap: Record<string, any> = {};
    
    agenticFileSearchTools.forEach(tool => {
      const combinedKey = tool.agenticFileSearchConfig!.selectedVectorStores.sort().join('|');
      agenticFileSearchToolsMap[combinedKey] = {
        selectedFiles: tool.agenticFileSearchConfig!.selectedFiles,
        selectedVectorStores: tool.agenticFileSearchConfig!.selectedVectorStores,
        vectorStoreNames: tool.agenticFileSearchConfig!.vectorStoreNames,
        iterations: tool.agenticFileSearchConfig!.iterations,
        maxResults: tool.agenticFileSearchConfig!.maxResults,
        lastUpdated: new Date().toISOString()
      };
    });
    
    localStorage.setItem('platform_agenticFileSearchTools', JSON.stringify(agenticFileSearchToolsMap));
  };

  const generateResponse = async (prompt: string) => {
    const provider = modelProvider;
    const apiKeyForProvider = getProviderApiKey(provider);
    if (!apiKeyForProvider) {
      toast.error('Please set your API key for the selected provider.');
      return;
    }

    setIsLoading(true);

    // Add user message
    const userMessage: Message = {
      id: Date.now().toString() + '_user',
      role: 'user',
      content: prompt,
      type: 'text',
      timestamp: new Date()
    };
    setMessages(prev => [...prev, userMessage]);

    // Add assistant message placeholder
    const assistantMessageId = Date.now().toString() + '_assistant';
    const assistantMessage: Message = {
      id: assistantMessageId,
      role: 'assistant',
      content: '',
      type: 'text',
      timestamp: new Date(),
      isLoading: true
    };
    setMessages(prev => [...prev, assistantMessage]);

    // Build API request
    const input = [
      {
        role: 'user',
        content: [
          {
            type: 'input_text',
            text: prompt
          }
        ]
      }
    ];

    let textFormatBlock: any = { type: textFormat };
    if (textFormat === 'json_schema') {
      let schema = null;
      let schemaName = jsonSchemaName;
      try {
        if (jsonSchemaContent) {
          const parsed = JSON.parse(jsonSchemaContent);
          schema = parsed.schema;
          schemaName = parsed.name || schemaName;
        }
      } catch {}
      if (!schema || !schemaName) {
        const errorMsg = 'JSON schema is missing or invalid. Please define a valid schema in settings.';
        toast.error(errorMsg);
        setMessages(prev => prev.map(msg =>
          msg.id === assistantMessageId
            ? {
                ...msg,
                content: errorMsg,
                type: 'text',
                hasThinkTags: false,
                isLoading: false
              }
            : msg
        ));
        setIsLoading(false);
        return;
      }
      textFormatBlock = {
        type: 'json_schema',
        name: schemaName,
        schema
      };
    }

    const effectiveInstructions = mockyMode && mockyAgentData ? mockyAgentData.systemPrompt : instructions;

    const requestBody: any = {
      model: `${modelProvider}@${modelName}`,
      instructions: effectiveInstructions,
      input,
      text: {
        format: textFormatBlock
      },
      temperature,
      max_output_tokens: maxTokens,
      top_p: topP,
      store: true,
      stream: true
    };
    
    // Tools handling
    if (mockyMode && mockyAgentData && Array.isArray(mockyAgentData.tools) && mockyAgentData.tools.length > 0) {
      requestBody.tools = mockyAgentData.tools;
    } else if (selectedTools.length > 0) {
      requestBody.tools = selectedTools.map(tool => {
        if (tool.id === 'mcp_server' && tool.mcpConfig) {
          return {
            type: 'mcp',
            server_label: tool.mcpConfig.label,
            server_url: tool.mcpConfig.url,
            allowed_tools: tool.mcpConfig.selectedTools,
            headers: tool.mcpConfig.authentication === 'access_token' && tool.mcpConfig.accessToken
              ? { 'Authorization': `Bearer ${tool.mcpConfig.accessToken}` }
              : tool.mcpConfig.authentication === 'custom_headers' && tool.mcpConfig.customHeaders
              ? tool.mcpConfig.customHeaders.reduce((acc: any, header: any) => {
                  if (header.key && header.value) {
                    acc[header.key] = header.value;
                  }
                  return acc;
                }, {})
              : {}
          };
        } else         if (tool.id === 'file_search' && tool.fileSearchConfig) {
          const toolBody: any = {
            type: 'file_search',
            vector_store_ids: tool.fileSearchConfig.selectedVectorStores
          };
          if (modelSettings?.settingsType === 'RUNTIME') {
            const provider = localStorage.getItem('platform_embedding_modelProvider') || '';
            const modelName = localStorage.getItem('platform_embedding_modelName') || '';
            const apiKeysRaw = localStorage.getItem('platform_apiKeys');
            let bearerToken = '';
            if (apiKeysRaw) {
              try {
                const apiKeys = JSON.parse(apiKeysRaw);
                const found = apiKeys.find((item: any) => item.name === provider);
                if (found) bearerToken = found.apiKey;
              } catch {}
            }
            toolBody.modelInfo = {
              bearerToken,
              model: provider && modelName ? `${provider}@${modelName}` : ''
            };
          }
          return toolBody;
        } else if (tool.id === 'agentic_file_search' && tool.agenticFileSearchConfig) {
          const toolBody: any = {
            type: 'agentic_search',
            vector_store_ids: tool.agenticFileSearchConfig.selectedVectorStores,
            max_iterations: tool.agenticFileSearchConfig.iterations,
            max_num_results: tool.agenticFileSearchConfig.maxResults
          };
          if (modelSettings?.settingsType === 'RUNTIME') {
            const provider = localStorage.getItem('platform_embedding_modelProvider') || '';
            const modelName = localStorage.getItem('platform_embedding_modelName') || '';
            const apiKeysRaw = localStorage.getItem('platform_apiKeys');
            let bearerToken = '';
            if (apiKeysRaw) {
              try {
                const apiKeys = JSON.parse(apiKeysRaw);
                const found = apiKeys.find((item: any) => item.name === provider);
                if (found) bearerToken = found.apiKey;
              } catch {}
            }
            toolBody.modelInfo = {
              bearerToken,
              model: provider && modelName ? `${provider}@${modelName}` : ''
            };
          }
          return toolBody;
        } else if (tool.id === 'fun_req_gathering_tool') {
          // Add Fun Req Assembler tool
          return {
            type: 'fun_req_gathering_tool'
          };
        } else if (tool.id === 'fun_def_generation_tool') {
          // Add Fun Def Generator tool
          return {
            type: 'fun_def_generation_tool'
          };
        } else if (tool.id === 'mock_fun_save_tool') {
          return { type: 'mock_fun_save_tool' };
        } else if (tool.id === 'mock_generation_tool') {
          return { type: 'mock_generation_tool' };
        } else if (tool.id === 'mock_save_tool') {
          return { type: 'mock_save_tool' };
        }
        // Add other tool types as needed
        return null;
      }).filter(Boolean);
    }
    
    if (previousResponseId) {
      requestBody.previous_response_id = previousResponseId;
    }

    // Capture request for code snippet generation
    const playgroundRequest: PlaygroundRequest = {
      method: 'POST',
      url: '/v1/responses',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKeyForProvider}`
      },
      body: requestBody
    };
    setLastRequest(playgroundRequest);

    try {
      const response = await apiClient.rawRequest('/v1/responses', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${apiKeyForProvider}`
        },
        body: JSON.stringify(requestBody)
      });

      if (!response.ok) {
        const errorText = await response.text();
        setMessages(prev => prev.map(msg =>
          msg.id === assistantMessageId
            ? {
                ...msg,
                content: `Error: ${errorText}`,
                type: 'text',
                hasThinkTags: false,
                isLoading: false
              }
            : msg
        ));
        setIsLoading(false);
        return;
      }

      // Handle streaming response
      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let streamingContent = '';
      let responseId = '';
      let isStreaming = false;
      let contentBlocks: ContentBlock[] = [];
      let currentTextBlock: ContentBlock | null = null;
      let activeToolExecutions = new Map<string, ToolExecution>();

      // Track the last SSE event name to properly handle custom events like "error"
      let lastEvent: string | null = null;

      const updateMessage = (blocks: ContentBlock[], fullContent: string) => {
        setMessages(prev => prev.map(msg =>
          msg.id === assistantMessageId
            ? {
                ...msg,
                content: fullContent,
                contentBlocks: [...blocks],
                type: 'text',
                hasThinkTags: false,
                isLoading: false
              }
            : msg
        ));
      };

      const addInlineLoading = (blocks: ContentBlock[]) => {
        // Remove any existing inline loading blocks first
        const blocksWithoutLoading = blocks.filter(block => block.type !== 'inline_loading');
        // Add new inline loading block at the end
        return [...blocksWithoutLoading, { type: 'inline_loading' as const }];
      };

      const removeInlineLoading = (blocks: ContentBlock[]) => {
        return blocks.filter(block => block.type !== 'inline_loading');
      };

      if (reader) {
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
              // Capture event name if present
              if (line.startsWith('event: ')) {
                lastEvent = line.slice(7).trim();
                continue;
              }
              if (line.startsWith('data: ')) {
                try {
                  const data = JSON.parse(line.slice(6));

                  // Handle explicit error events from SSE stream
                  if (lastEvent === 'error' || (data.code && data.message && !data.type)) {
                    const errorCode = data.code || 'error';
                    const errorMsg = data.message || 'Unknown error';
                    const errorContent = `Error: [${errorCode}] ${errorMsg}`;

                    // Update assistant message with error content, stop loading state
                    setMessages(prev => prev.map(msg =>
                      msg.id === assistantMessageId
                        ? {
                            ...msg,
                            content: errorContent,
                            contentBlocks: [
                              {
                                type: 'text',
                                content: `[${errorCode}] ${errorMsg}`
                              }
                            ],
                            type: 'text',
                            hasThinkTags: false,
                            isLoading: false
                          }
                        : msg
                    ));

                    // Exit streaming loop on error
                    reader.cancel().catch(() => {});
                    break;
                  }
                  // Handle different event types
                  if (data.type === 'response.completed') {
                    if (data.response?.id) {
                      responseId = data.response.id;
                    }
                  } else if (data.type === 'response.output_text.delta') {
                    // Start or continue streaming
                    if (!isStreaming) {
                      isStreaming = true;
                    }
                    
                    if (data.delta) {
                      streamingContent += data.delta;
                      
                      // Create or update current text block
                      if (!currentTextBlock) {
                        currentTextBlock = { type: 'text', content: data.delta };
                        contentBlocks.push(currentTextBlock);
                      } else {
                        currentTextBlock.content = (currentTextBlock.content || '') + data.delta;
                      }
                      
                      // Check if we have a complete JSON object for real-time formatting
                      let displayContent = streamingContent;
                      if (textFormat === 'json_object' || textFormat === 'json_schema') {
                        try {
                          JSON.parse(streamingContent);
                          displayContent = streamingContent;
                        } catch {
                          displayContent = streamingContent;
                        }
                      }
                      
                      // Remove any inline loading when text streaming starts
                      const blocksWithoutLoading = removeInlineLoading(contentBlocks);
                      updateMessage(blocksWithoutLoading, displayContent);
                    }
                  } else if (data.type === 'response.output_text.done') {
                    // Streaming completed for this output
                    isStreaming = false;
                    currentTextBlock = null; // Reset for potential next text stream
                    if (data.text) {
                      streamingContent = data.text;
                      // Update the last text block with complete content
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'text') {
                          contentBlocks[i].content = data.text;
                          break;
                        }
                      }
                      updateMessage(contentBlocks, streamingContent);
                    }
                  } else if (data.type && data.type.startsWith('response.mcp_call.')) {
                    // Handle MCP tool events
                    const typeParts = data.type.split('.');
                    if (typeParts.length >= 4) {
                      const toolIdentifier = typeParts[2];
                      const status = typeParts[3];
                      
                      // Parse tool identifier: myshopify_search_shop_catalog
                      const identifierParts = toolIdentifier.split('_');
                      const serverName = identifierParts[0];
                      const toolName = identifierParts.slice(1).join('_');
                      
                      if (status === 'in_progress') {
                        // Add new tool execution
                        const toolExecution: ToolExecution = {
                          serverName,
                          toolName,
                          status: 'in_progress'
                        };
                        activeToolExecutions.set(toolIdentifier, toolExecution);
                        
                        // Find existing tool progress block or create new one
                        let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                        if (!toolProgressBlock) {
                          toolProgressBlock = {
                            type: 'tool_progress',
                            toolExecutions: Array.from(activeToolExecutions.values())
                          };
                          contentBlocks.push(toolProgressBlock);
                        } else {
                          // Update existing tool progress block
                          toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                        }
                        currentTextBlock = null; // Reset text block for potential next text
                        
                        updateMessage(contentBlocks, streamingContent);
                      } else if (status === 'completed') {
                        // Update tool execution status
                        const toolExecution = activeToolExecutions.get(toolIdentifier);
                        if (toolExecution) {
                          toolExecution.status = 'completed';
                          
                                                  // Update the last tool progress block
                        for (let i = contentBlocks.length - 1; i >= 0; i--) {
                          if (contentBlocks[i].type === 'tool_progress') {
                            contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                            break;
                          }
                        }
                          
                          // Add inline loading when tools complete, indicating we're waiting for next text stream
                          const blocksWithLoading = addInlineLoading(contentBlocks);
                          updateMessage(blocksWithLoading, streamingContent);
                        }
                      }
                    }
                  } else if (data.type === 'response.file_search.in_progress') {
                    // Handle file search tool start event
                    const toolExecution: ToolExecution = {
                      serverName: 'file_search',
                      toolName: 'search',
                      status: 'in_progress'
                    };
                    activeToolExecutions.set('file_search', toolExecution);
                    
                    // Find existing tool progress block or create new one
                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = {
                        type: 'tool_progress',
                        toolExecutions: Array.from(activeToolExecutions.values())
                      };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      // Update existing tool progress block
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null; // Reset text block for potential next text
                    
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.file_search.completed') {
                    // Handle file search tool completion event
                    const toolExecution = activeToolExecutions.get('file_search');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      
                      // Update the last tool progress block
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      
                      // Add inline loading when tools complete, indicating we're waiting for next text stream
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  } else if (data.type === 'response.agentic_search.in_progress') {
                    // Handle agentic search tool start event
                    const toolExecution: ToolExecution = {
                      serverName: 'agentic_search',
                      toolName: 'search',
                      status: 'in_progress',
                      agenticSearchLogs: []
                    };
                    activeToolExecutions.set('agentic_search', toolExecution);
                    
                    // Find existing tool progress block or create new one
                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = {
                        type: 'tool_progress',
                        toolExecutions: Array.from(activeToolExecutions.values())
                      };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      // Update existing tool progress block
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null; // Reset text block for potential next text
                    
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.agentic_search.query_phase.iteration') {
                    // Handle agentic search iteration logs
                    const toolExecution = activeToolExecutions.get('agentic_search');
                    if (toolExecution && data.iteration && data.query) {
                      const logEntry: AgenticSearchLog = {
                        iteration: data.iteration,
                        query: data.query,
                        reasoning: data.reasoning || '',
                        citations: data.citations || [],
                        remaining_iterations: data.remaining_iterations || 0
                      };
                      
                      if (!toolExecution.agenticSearchLogs) {
                        toolExecution.agenticSearchLogs = [];
                      }
                      toolExecution.agenticSearchLogs.push(logEntry);
                      
                      // Update the tool progress block
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      
                      updateMessage(contentBlocks, streamingContent);
                    }
                  } else if (data.type === 'response.agentic_search.completed') {
                    // Handle agentic search tool completion event
                    const toolExecution = activeToolExecutions.get('agentic_search');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      
                      // Update the last tool progress block
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      
                      // Add inline loading when tools complete, indicating we're waiting for next text stream
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  } else if (data.type === 'response.fun_req_gathering_tool.in_progress') {
                    // Handle fun req assembler start
                    const toolExecution: ToolExecution = {
                      serverName: 'fun_req_gathering_tool',
                      toolName: 'assemble',
                      status: 'in_progress'
                    };
                    activeToolExecutions.set('fun_req_gathering_tool', toolExecution);

                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = {
                        type: 'tool_progress',
                        toolExecutions: Array.from(activeToolExecutions.values())
                      };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null;
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.fun_req_gathering_tool.completed') {
                    const toolExecution = activeToolExecutions.get('fun_req_gathering_tool');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  } else if (data.type === 'response.fun_def_generation_tool.in_progress') {
                    // Handle fun def generator start
                    const toolExecution: ToolExecution = {
                      serverName: 'fun_def_generation_tool',
                      toolName: 'generate',
                      status: 'in_progress'
                    };
                    activeToolExecutions.set('fun_def_generation_tool', toolExecution);

                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = {
                        type: 'tool_progress',
                        toolExecutions: Array.from(activeToolExecutions.values())
                      };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null;
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.fun_def_generation_tool.completed') {
                    const toolExecution = activeToolExecutions.get('fun_def_generation_tool');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  } else if (data.type === 'response.mock_fun_save_tool.in_progress') {
                    const toolExecution: ToolExecution = {
                      serverName: 'mock_fun_save_tool',
                      toolName: 'save_function',
                      status: 'in_progress'
                    };
                    activeToolExecutions.set('mock_fun_save_tool', toolExecution);

                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = { type: 'tool_progress', toolExecutions: Array.from(activeToolExecutions.values()) };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null;
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.mock_fun_save_tool.completed') {
                    const toolExecution = activeToolExecutions.get('mock_fun_save_tool');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  } else if (data.type === 'response.mock_generation_tool.in_progress') {
                    const toolExecution: ToolExecution = {
                      serverName: 'mock_generation_tool',
                      toolName: 'generate',
                      status: 'in_progress'
                    };
                    activeToolExecutions.set('mock_generation_tool', toolExecution);
                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = { type: 'tool_progress', toolExecutions: Array.from(activeToolExecutions.values()) };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null;
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.mock_generation_tool.completed') {
                    const toolExecution = activeToolExecutions.get('mock_generation_tool');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  } else if (data.type === 'response.mock_save_tool.in_progress') {
                    const toolExecution: ToolExecution = {
                      serverName: 'mock_save_tool',
                      toolName: 'save',
                      status: 'in_progress'
                    };
                    activeToolExecutions.set('mock_save_tool', toolExecution);
                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = { type: 'tool_progress', toolExecutions: Array.from(activeToolExecutions.values()) };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null;
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.mock_save_tool.completed') {
                    const toolExecution = activeToolExecutions.get('mock_save_tool');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  }
                } catch (parseError) {
                  console.error('Error parsing SSE data:', parseError);
                }
              }
            }
          }
        } catch (streamError) {
          console.error('Error reading stream:', streamError);
          setMessages(prev => prev.map(msg =>
            msg.id === assistantMessageId
              ? {
                  ...msg,
                  content: `Error: Failed to read streaming response`,
                  type: 'text',
                  hasThinkTags: false,
                  isLoading: false
                }
              : msg
          ));
        } finally {
          reader.releaseLock();
        }
      }

      // Update previous response ID for next request
      if (responseId) {
        setPreviousResponseId(responseId);
      }

    } catch (error: any) {
      setMessages(prev => prev.map(msg =>
        msg.id === assistantMessageId
          ? {
              ...msg,
              content: `Error: ${error.message}`,
              type: 'text',
              hasThinkTags: false,
              isLoading: false
            }
          : msg
      ));
    } finally {
      setIsLoading(false);
    }
  };

  // Retry logic for error messages
  const handleRetry = (errorMessageId: string) => {
    // Find the index of the error message and its preceding user prompt
    const errorIndex = messages.findIndex(msg => msg.id === errorMessageId);
    if (errorIndex <= 0) return;

    const userMessage = messages[errorIndex - 1];
    if (userMessage.role !== 'user') return;

    const promptToReplay = userMessage.content;

    // Remove the user + error messages from chat history
    setMessages(prev => prev.filter((_, idx) => idx < errorIndex - 1));

    // Trigger the prompt again
    generateResponse(promptToReplay);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (inputValue.trim() && !isLoading) {
      generateResponse(inputValue.trim());
      setInputValue('');
      
      // Reset textarea height to default
      const textarea = document.querySelector('.chat-input-textarea') as HTMLTextAreaElement;
      if (textarea) {
        textarea.style.height = '96px';
      }
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const textarea = e.target;
    setInputValue(textarea.value);
    
    // Auto-resize functionality with minimum and maximum heights
    const minHeight = 96;
    const maxHeight = Math.round(window.innerHeight * 0.4);
    
    // Reset height to auto to get proper scrollHeight
    textarea.style.height = 'auto';
    
    // Set new height
    const newHeight = Math.max(Math.min(textarea.scrollHeight, maxHeight), minHeight);
    textarea.style.height = `${newHeight}px`;
  };

  const handleTabChange = (tab: string) => {
    // First, always reset any active special modes
    const resetMockyMode = () => {
      if (mockyMode) {
        setMockyMode(false);
        setMockyAgentData(null);
        setMessages([]);
        setConversationId(null);
        setPreviousResponseId(null);
      }
    };

    const resetModelTestMode = () => {
      if (modelTestMode) {
        setModelTestMode(false);
        setModelTestAgentData(null);
        setModelTestUrl('');
        setModelTestName('');
        setModelTestApiKey('');
        setIsTestingModel(false);
        setShowSaveModel(false);
        setSaveModelState(null);
        setMessages([]);
        setConversationId(null);
        setPreviousResponseId(null);
      }
    };

    // Special handling for Masaic Mocky option
    if (tab === 'masaic-mocky') {
      // Reset Model Test mode first if active
      resetModelTestMode();
      
      setActiveTab(tab);
      // Fetch agent definition
      fetch(`${API_URL}/v1/agents/Masaic-Mocky`)
        .then(res => res.json())
        .then(data => {
          if (data) {
            setMockyMode(true);
            setMockyAgentData({
              systemPrompt: data.systemPrompt || '',
              greetingMessage: data.greetingMessage || '',
              description: data.description || '',
              tools: data.tools || []
            });

            // reset conversation tracking ids
            setConversationId(null);
            setPreviousResponseId(null);

            // Reset previous conversation and show greeting
            setMessages([]);

            const greetingId = Date.now().toString() + '_assistant';
            const greetingMessage: Message = {
              id: greetingId,
              role: 'assistant',
              content: '',
              type: 'text',
              timestamp: new Date(),
              isLoading: true
            };
            setMessages([greetingMessage]);

            // Artificial streaming of greeting text
            const greetingText: string = data.greetingMessage || '';
            let idx = 0;
            const interval = setInterval(() => {
              idx += 1;
              const partial = greetingText.slice(0, idx);
              setMessages(prev => prev.map(msg => msg.id === greetingId ? { ...msg, content: partial } : msg));
              if (idx >= greetingText.length) {
                clearInterval(interval);
                setMessages(prev => prev.map(msg => msg.id === greetingId ? { ...msg, isLoading: false } : msg));
              }
            }, 25);
          } else {
            toast.error('Failed to load Masaic Mocky agent data');
          }
        })
        .catch(err => {
          console.error(err);
          toast.error('Error fetching Masaic Mocky agent');
        });
      return;
    }

    // Special handling for Add Model option
    if (tab === 'add-model') {
      // Reset Mocky mode first if active
      resetMockyMode();
      
      setActiveTab(tab);
      // Fetch ModelTestAgent definition
      fetch(`${API_URL}/v1/agents/ModelTestAgent`)
        .then(res => res.json())
        .then(data => {
          if (data) {
            setModelTestMode(true);
            setModelTestAgentData({
              systemPrompt: data.systemPrompt || '',
              greetingMessage: data.greetingMessage || '',
              userMessage: data.userMessage || '',
              tools: data.tools || []
            });

            // Reset form fields
            setModelTestUrl('');
            setModelTestName('');
            setModelTestApiKey('');
            setIsTestingModel(false);
            setShowSaveModel(false);

            // reset conversation tracking ids
            setConversationId(null);
            setPreviousResponseId(null);

            // Reset previous conversation
            setMessages([]);
          } else {
            toast.error('Failed to load Model Test Agent data');
          }
        })
        .catch(err => {
          console.error(err);
          toast.error('Error fetching Model Test Agent');
        });
      return;
    }

    // For any other tab, reset both modes
    resetMockyMode();
    resetModelTestMode();

    setActiveTab(tab);
    // Handle API Keys tab by opening the API keys modal
    if (tab === 'api-keys') {
      setApiKeysModalOpen(true);
      // Reset to previous tab since API Keys is a modal action, not a tab
      setActiveTab('responses');
    }
  };

  const handleTestModelConnectivity = async () => {
    // Validation
    if (!modelTestUrl.trim()) {
      toast.error('Please enter a model URL');
      return;
    }
    
    if (!isValidUrl(modelTestUrl.trim())) {
      toast.error('Please enter a valid URL starting with http:// or https://');
      return;
    }
    
    if (!modelTestName.trim()) {
      toast.error('Please enter a model name');
      return;
    }
    
    if (!modelTestApiKey.trim()) {
      toast.error('Please enter an API key');
      return;
    }

    if (!modelTestAgentData) {
      toast.error('Model Test Agent data not loaded');
      return;
    }

    setIsTestingModel(true);
    setShowSaveModel(false);
    setSaveModelState(null);

    // Reset conversation state like Reset Chat CTA
    setMessages([]);
    setConversationId(null);
    setPreviousResponseId(null);

    // Display greeting message from agent
    const greetingId = Date.now().toString() + '_assistant';
    const greetingMessage: Message = {
      id: greetingId,
      role: 'assistant',
      content: '',
      type: 'text',
      timestamp: new Date(),
      isLoading: true
    };
    
    setMessages([greetingMessage]);

    // Artificial streaming of greeting text
    const greetingText: string = modelTestAgentData.greetingMessage || '';
    let idx = 0;
    const greetingInterval = setInterval(() => {
      idx += 1;
      const partial = greetingText.slice(0, idx);
      setMessages(prev => prev.map(msg => msg.id === greetingId ? { ...msg, content: partial } : msg));
      if (idx >= greetingText.length) {
        clearInterval(greetingInterval);
        setMessages(prev => prev.map(msg => msg.id === greetingId ? { ...msg, isLoading: false } : msg));
        
        // After greeting is complete, add user message
        setTimeout(() => {
          const userMessageId = Date.now().toString() + '_user';
          const userMessage: Message = {
            id: userMessageId,
            role: 'user',
            content: '',
            type: 'text',
            timestamp: new Date(),
            isLoading: true
          };
          
          setMessages(prev => [...prev, userMessage]);
          
          // Stream user message
          const userText = modelTestAgentData.userMessage || '';
          let userIdx = 0;
          const userInterval = setInterval(() => {
            userIdx += 1;
            const userPartial = userText.slice(0, userIdx);
            setMessages(prev => prev.map(msg => msg.id === userMessageId ? { ...msg, content: userPartial } : msg));
            if (userIdx >= userText.length) {
              clearInterval(userInterval);
              setMessages(prev => prev.map(msg => msg.id === userMessageId ? { ...msg, isLoading: false } : msg));
              
              // After user message is complete, make API call
              setTimeout(() => {
                makeModelTestApiCall();
              }, 500);
            }
          }, 25);
        }, 1000);
      }
    }, 25);
  };

  const makeModelTestApiCall = async () => {
    if (!modelTestAgentData) return;

    const assistantMessageId = Date.now().toString() + '_assistant_response';
    const assistantMessage: Message = {
      id: assistantMessageId,
      role: 'assistant',
      content: '',
      contentBlocks: [{
        type: 'text',
        content: ''
      }],
      type: 'text',
      timestamp: new Date(),
      isLoading: true
    };

    setMessages(prev => [...prev, assistantMessage]);

    // Build text format from current settings
    let textFormatBlock: any = { type: textFormat };
    if (textFormat === 'json_schema') {
      let schema = null;
      let schemaName = jsonSchemaName;
      try {
        if (jsonSchemaContent) {
          const parsed = JSON.parse(jsonSchemaContent);
          schema = parsed.schema;
          schemaName = parsed.name || schemaName;
        }
      } catch {}
      if (!schema || !schemaName) {
        const errorMsg = 'JSON schema is missing or invalid. Please define a valid schema in settings.';
        toast.error(errorMsg);
        setMessages(prev => prev.map(msg =>
          msg.id === assistantMessageId
            ? {
                ...msg,
                content: errorMsg,
                type: 'text',
                hasThinkTags: false,
                isLoading: false
              }
            : msg
        ));
        setIsTestingModel(false);
        return;
      }
      textFormatBlock = {
        type: 'json_schema',
        name: schemaName,
        schema
      };
    }

    const requestBody = {
      model: `${modelTestUrl.trim()}@${modelTestName.trim()}`,
      instructions: modelTestAgentData.systemPrompt,
      input: [
        {
          role: 'user',
          content: [
            {
              type: 'input_text',
              text: modelTestAgentData.userMessage
            }
          ]
        }
      ],
      text: {
        format: textFormatBlock
      },
      tools: modelTestAgentData.tools || [],
      temperature: temperature,
      max_output_tokens: maxTokens,
      top_p: topP,
      store: true,
      stream: true,
      previous_response_id: null
    };

    try {
      const response = await apiClient.rawRequest('/v1/responses', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${modelTestApiKey.trim()}`
        },
        body: JSON.stringify(requestBody)
      });

      if (!response.ok) {
        const errorText = await response.text();
        setMessages(prev => prev.map(msg =>
          msg.id === assistantMessageId
            ? {
                ...msg,
                content: `Error: ${errorText}`,
                type: 'text',
                hasThinkTags: false,
                isLoading: false
              }
            : msg
        ));
        setIsTestingModel(false);
        return;
      }

      // Handle streaming response (reuse existing streaming logic)
      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let streamingContent = '';
      let responseId = '';
      let isStreaming = false;
      let contentBlocks: ContentBlock[] = [];
      let currentTextBlock: ContentBlock | null = null;
      let activeToolExecutions = new Map<string, ToolExecution>();
      let lastEvent: string | null = null;
      let responseCompleted = false;
      let toolCompleted = false;

      const updateMessage = (blocks: ContentBlock[], fullContent: string) => {
        setMessages(prev => prev.map(msg =>
          msg.id === assistantMessageId
            ? {
                ...msg,
                content: fullContent,
                contentBlocks: [...blocks],
                type: 'text',
                hasThinkTags: false,
                isLoading: false
              }
            : msg
        ));
      };

      const addInlineLoading = (blocks: ContentBlock[]) => {
        const blocksWithoutLoading = blocks.filter(block => block.type !== 'inline_loading');
        return [...blocksWithoutLoading, { type: 'inline_loading' as const }];
      };

      const removeInlineLoading = (blocks: ContentBlock[]) => {
        return blocks.filter(block => block.type !== 'inline_loading');
      };

      if (reader) {
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
              // Capture event name if present
              if (line.startsWith('event: ')) {
                lastEvent = line.slice(7).trim();
                continue;
              }
              if (line.startsWith('data: ')) {
                try {
                  const data = JSON.parse(line.slice(6));

                  // Handle explicit error events from SSE stream
                  if (lastEvent === 'error' || (data.code && data.message && !data.type)) {
                    const errorCode = data.code || 'error';
                    const errorMsg = data.message || 'Unknown error';
                    const errorContent = `Error: [${errorCode}] ${errorMsg}`;

                    // Update assistant message with error content, stop loading state
                    setMessages(prev => prev.map(msg =>
                      msg.id === assistantMessageId
                        ? {
                            ...msg,
                            content: errorContent,
                            contentBlocks: [
                              {
                                type: 'text',
                                content: `[${errorCode}] ${errorMsg}`
                              }
                            ],
                            type: 'text',
                            hasThinkTags: false,
                            isLoading: false
                          }
                        : msg
                    ));

                    // Exit streaming loop on error and reset testing state
                    setIsTestingModel(false);
                    setSaveModelState('error');
                    setShowSaveModel(true);
                    reader.cancel().catch(() => {});
                    return;
                  }

                  // Handle different event types
                  if (data.type === 'response.completed') {
                    if (data.response?.id) {
                      responseId = data.response.id;
                      setPreviousResponseId(responseId);
                    }
                    responseCompleted = true;
                    
                    // Determine save model state based on completion status
                    if (toolCompleted) {
                      // Both response and tool completed successfully
                      setSaveModelState('success');
                    } else {
                      // Response completed but no tool completion - tool calling issue
                      setSaveModelState('tool_issue');
                    }
                    
                    // Response completed - stop testing and show save button
                    setIsTestingModel(false);
                    setShowSaveModel(true);
                  } else if (data.type === 'response.output_text.delta') {
                    // Start or continue streaming
                    if (!isStreaming) {
                      isStreaming = true;
                    }
                    
                    if (data.delta) {
                      streamingContent += data.delta;
                      
                      // Create or update current text block
                      if (!currentTextBlock) {
                        currentTextBlock = { type: 'text', content: data.delta };
                        contentBlocks.push(currentTextBlock);
                      } else {
                        currentTextBlock.content = (currentTextBlock.content || '') + data.delta;
                      }
                      
                      // Check if we have a complete JSON object for real-time formatting
                      let displayContent = streamingContent;
                      if (textFormat === 'json_object' || textFormat === 'json_schema') {
                        try {
                          JSON.parse(streamingContent);
                          displayContent = streamingContent;
                        } catch {
                          displayContent = streamingContent;
                        }
                      }
                      
                      // Remove any inline loading when text streaming starts
                      const blocksWithoutLoading = removeInlineLoading(contentBlocks);
                      updateMessage(blocksWithoutLoading, displayContent);
                    }
                  } else if (data.type === 'response.output_text.done') {
                    // Streaming completed for this output
                    isStreaming = false;
                    currentTextBlock = null;
                    if (data.text) {
                      streamingContent = data.text;
                      // Update the last text block with complete content
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'text') {
                          contentBlocks[i].content = data.text;
                          break;
                        }
                      }
                      updateMessage(contentBlocks, streamingContent);
                    }
                  } else if (data.type === 'response.get_weather_by_city.in_progress') {
                    // Handle get_weather_by_city tool in_progress
                    const toolExecution: ToolExecution = {
                      serverName: 'get_weather_by_city',
                      toolName: 'get_weather_by_city',
                      status: 'in_progress'
                    };
                    activeToolExecutions.set('get_weather_by_city', toolExecution);
                    
                    // Find or create tool progress block
                    let toolProgressBlock = contentBlocks.find(block => block.type === 'tool_progress');
                    if (!toolProgressBlock) {
                      toolProgressBlock = { type: 'tool_progress', toolExecutions: Array.from(activeToolExecutions.values()) };
                      contentBlocks.push(toolProgressBlock);
                    } else {
                      toolProgressBlock.toolExecutions = Array.from(activeToolExecutions.values());
                    }
                    currentTextBlock = null;
                    updateMessage(contentBlocks, streamingContent);
                  } else if (data.type === 'response.get_weather_by_city.completed') {
                    // Handle get_weather_by_city tool completed
                    const toolExecution = activeToolExecutions.get('get_weather_by_city');
                    if (toolExecution) {
                      toolExecution.status = 'completed';
                      toolCompleted = true;
                      
                      // If response already completed, update save model state to success
                      if (responseCompleted) {
                        setSaveModelState('success');
                      }
                      
                      // Update the tool progress block
                      for (let i = contentBlocks.length - 1; i >= 0; i--) {
                        if (contentBlocks[i].type === 'tool_progress') {
                          contentBlocks[i].toolExecutions = Array.from(activeToolExecutions.values());
                          break;
                        }
                      }
                      const blocksWithLoading = addInlineLoading(contentBlocks);
                      updateMessage(blocksWithLoading, streamingContent);
                    }
                  }
                } catch (parseError) {
                  console.error('Error parsing SSE data:', parseError);
                }
              }
            }
          }
        } catch (streamError) {
          console.error('Error reading stream:', streamError);
          setMessages(prev => prev.map(msg =>
            msg.id === assistantMessageId
              ? {
                  ...msg,
                  content: `Error: Failed to read streaming response`,
                  type: 'text',
                  hasThinkTags: false,
                  isLoading: false
                }
              : msg
          ));
          setIsTestingModel(false);
          setSaveModelState('error');
          setShowSaveModel(true);
        } finally {
          reader.releaseLock();
        }
      }

    } catch (error) {
      console.error('Error making model test API call:', error);
      setMessages(prev => prev.map(msg =>
        msg.id === assistantMessageId
          ? {
              ...msg,
              content: `Error: ${error instanceof Error ? error.message : 'Unknown error occurred'}`,
              type: 'text',
              hasThinkTags: false,
              isLoading: false
            }
          : msg
      ));
      setIsTestingModel(false);
      setSaveModelState('error');
      setShowSaveModel(true);
    }
  };

  const handleSaveModel = () => {
    if (!modelTestUrl.trim() || !modelTestName.trim() || !modelTestApiKey.trim()) {
      toast.error('Missing model information');
      return;
    }

    try {
      // 1. Update platform_own_model in localStorage
      const existingOwnModels = localStorage.getItem('platform_own_model');
      let ownModelsData;
      
      if (existingOwnModels) {
        ownModelsData = JSON.parse(existingOwnModels);
      } else {
        ownModelsData = {
          name: "own model",
          description: "My own models",
          supportedModels: []
        };
      }

      const newModelSyntax = `${modelTestUrl.trim()}@${modelTestName.trim()}`;
      
      // Check if model already exists by modelSyntax (to prevent duplicates)
      const existingModelIndex = ownModelsData.supportedModels.findIndex(
        (model: any) => model.modelSyntax === newModelSyntax
      );

      const newModel = {
        name: modelTestName.trim(),
        modelSyntax: newModelSyntax
      };

      if (existingModelIndex >= 0) {
        // Update existing model
        ownModelsData.supportedModels[existingModelIndex] = newModel;
      } else {
        // Add new model
        ownModelsData.supportedModels.push(newModel);
      }

      localStorage.setItem('platform_own_model', JSON.stringify(ownModelsData));

      // 2. Update platform_apiKeys in localStorage
      const existingApiKeys = localStorage.getItem('platform_apiKeys');
      let apiKeysData = [];
      
      if (existingApiKeys) {
        apiKeysData = JSON.parse(existingApiKeys);
      }

      // Check if API key for this model already exists
      const existingApiKeyIndex = apiKeysData.findIndex(
        (apiKey: any) => apiKey.name === modelTestUrl.trim()
      );

      const newApiKey = {
        name: modelTestUrl.trim(),
        apiKey: modelTestApiKey.trim()
      };

      if (existingApiKeyIndex >= 0) {
        // Update existing API key
        apiKeysData[existingApiKeyIndex] = newApiKey;
      } else {
        // Add new API key
        apiKeysData.push(newApiKey);
      }

      localStorage.setItem('platform_apiKeys', JSON.stringify(apiKeysData));

      toast.success(`Model "${modelTestName.trim()}" saved successfully!`);
      
      // Trigger a refresh of the models list by dispatching a storage event
      window.dispatchEvent(new Event('storage'));
      
      // Reset the form
      setModelTestUrl('');
      setModelTestName('');
      setModelTestApiKey('');
      setShowSaveModel(false);
      setSaveModelState(null);
      
    } catch (error) {
      console.error('Error saving model:', error);
      toast.error('Failed to save model');
    }
  };

  return (
    <>
    <Drawer>
      {/* Mobile Hamburger */}
      <DrawerTrigger asChild>
        <button className="md:hidden fixed top-3 left-3 z-50 p-2 rounded-md bg-background/80 border border-border shadow-sm" aria-label="Open menu">
          <Menu className="h-5 w-5 text-foreground" />
        </button>
      </DrawerTrigger>

      {/* Drawer Content */}
      <DrawerContent className="h-[85vh] overflow-y-auto p-4 space-y-4">
        {/* Sidebar */}
        <PlaygroundSidebar 
          activeTab={activeTab}
          onTabChange={handleTabChange}
          className="flex flex-col w-full"
        />
        {/* Configuration Panel */}
        <ConfigurationPanel
          modelProvider={modelProvider}
          setModelProvider={setModelProvider}
          modelName={modelName}
          setModelName={setModelName}
          imageModelProvider={imageModelProvider}
          setImageModelProvider={setImageModelProvider}
          imageModelName={imageModelName}
          setImageModelName={setImageModelName}
          imageProviderKey={imageProviderKey}
          setImageProviderKey={setImageProviderKey}
          apiKey={apiKey}
          setApiKey={setApiKey}
          baseUrl={baseUrl}
          setBaseUrl={setBaseUrl}
          temperature={temperature}
          setTemperature={setTemperature}
          maxTokens={maxTokens}
          setMaxTokens={setMaxTokens}
          topP={topP}
          setTopP={setTopP}
          storeLogs={storeLogs}
          setStoreLogs={setStoreLogs}
          textFormat={textFormat}
          setTextFormat={setTextFormat}
          toolChoice={toolChoice}
          setToolChoice={setToolChoice}
          instructions={instructions}
          setInstructions={setInstructions}
          promptMessages={promptMessages}
          onAddPromptMessage={handleAddPromptMessage}
          onRemovePromptMessage={handleRemovePromptMessage}
          selectedTools={selectedTools}
          onSelectedToolsChange={setSelectedTools}
          getMCPToolByLabel={getMCPToolByLabel}
          selectedVectorStore={selectedVectorStore}
          onVectorStoreSelect={handleVectorStoreSelect}
          onResetConversation={resetConversation}
          openApiKeysModal={apiKeysModalOpen}
          onApiKeysModalChange={setApiKeysModalOpen}
          jsonSchemaContent={jsonSchemaContent}
          setJsonSchemaContent={setJsonSchemaContent}
          jsonSchemaName={jsonSchemaName}
          setJsonSchemaName={setJsonSchemaName}
          className="w-full"
          mockyMode={mockyMode}
          modelTestMode={modelTestMode}
          modelTestUrl={modelTestUrl}
          setModelTestUrl={setModelTestUrl}
          modelTestName={modelTestName}
          setModelTestName={setModelTestName}
          modelTestApiKey={modelTestApiKey}
          setModelTestApiKey={setModelTestApiKey}
          onTestModelConnectivity={handleTestModelConnectivity}
          isTestingModel={isTestingModel}
        />
      </DrawerContent>
    </Drawer>

    <div className="flex h-full bg-background">
      {/* Left Sidebar - 10% (desktop only) */}
      <PlaygroundSidebar 
        activeTab={activeTab}
        onTabChange={handleTabChange}
        className="hidden md:flex md:flex-col md:w-[10%] md:min-w-[160px]"
      />

      {/* Configuration Panel - 30% (desktop only) */}
      <ConfigurationPanel
        modelProvider={modelProvider}
        setModelProvider={setModelProvider}
        modelName={modelName}
        setModelName={setModelName}
        imageModelProvider={imageModelProvider}
        setImageModelProvider={setImageModelProvider}
        imageModelName={imageModelName}
        setImageModelName={setImageModelName}
        imageProviderKey={imageProviderKey}
        setImageProviderKey={setImageProviderKey}
        apiKey={apiKey}
        setApiKey={setApiKey}
        baseUrl={baseUrl}
        setBaseUrl={setBaseUrl}
        temperature={temperature}
        setTemperature={setTemperature}
        maxTokens={maxTokens}
        setMaxTokens={setMaxTokens}
        topP={topP}
        setTopP={setTopP}
        storeLogs={storeLogs}
        setStoreLogs={setStoreLogs}
        textFormat={textFormat}
        setTextFormat={setTextFormat}
        toolChoice={toolChoice}
        setToolChoice={setToolChoice}
        instructions={instructions}
        setInstructions={setInstructions}
        promptMessages={promptMessages}
        onAddPromptMessage={handleAddPromptMessage}
        onRemovePromptMessage={handleRemovePromptMessage}
        selectedTools={selectedTools}
        onSelectedToolsChange={setSelectedTools}
        getMCPToolByLabel={getMCPToolByLabel}
        selectedVectorStore={selectedVectorStore}
        onVectorStoreSelect={handleVectorStoreSelect}
        onResetConversation={resetConversation}
        openApiKeysModal={apiKeysModalOpen}
        onApiKeysModalChange={setApiKeysModalOpen}
        jsonSchemaContent={jsonSchemaContent}
        setJsonSchemaContent={setJsonSchemaContent}
        jsonSchemaName={jsonSchemaName}
        setJsonSchemaName={setJsonSchemaName}
        className="hidden md:block md:w-[30%]"
        mockyMode={mockyMode}
        modelTestMode={modelTestMode}
        modelTestUrl={modelTestUrl}
        setModelTestUrl={setModelTestUrl}
        modelTestName={modelTestName}
        setModelTestName={setModelTestName}
        modelTestApiKey={modelTestApiKey}
        setModelTestApiKey={setModelTestApiKey}
        onTestModelConnectivity={handleTestModelConnectivity}
        isTestingModel={isTestingModel}
      />

      {/* Chat Area */}
      <div className="flex-1 md:w-[60%] flex flex-col">
        {/* Sticky Header */}
        <div className="sticky top-0 z-10 bg-background/95 backdrop-blur-sm border-b border-border px-6 py-3">
          <div className="flex items-center justify-between w-full">
            {/* Action Buttons - Moved to extreme left */}
            <div className="flex items-center space-x-2">
              <Button
                variant="ghost"
                size="sm"
                onClick={resetConversation}
                className="flex items-center space-x-2 text-muted-foreground hover:text-foreground hover:bg-muted/50"
                title="Reset conversation"
              >
                <RotateCcw className="w-4 h-4" />
                <span className="text-sm">Reset Chat</span>
              </Button>
              
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setCodeModalOpen(true)}
                disabled={!lastRequest}
                className="flex items-center space-x-2 text-muted-foreground hover:text-foreground hover:bg-muted/50 disabled:opacity-50 disabled:cursor-not-allowed"
                title={lastRequest ? "View code snippets for last request" : "Send a message to generate code snippets"}
              >
                <Code className="w-4 h-4" />
                <span className="text-sm">View Code</span>
              </Button>
            </div>

            {mockyMode && mockyAgentData?.description && (
              <span className="absolute left-1/2 transform -translate-x-1/2 text-xs text-muted-foreground truncate max-w-[60%] text-center">
                {mockyAgentData.description}
              </span>
            )}

            {modelTestMode && (
              <span className="absolute left-1/2 transform -translate-x-1/2 text-xs text-muted-foreground truncate max-w-[60%] text-center">
                Test model connectivity and validate API integration
              </span>
            )}

            {/* Response ID Display - Moved to right */}
            {previousResponseId && (
              <div className="flex items-center space-x-2">
                <span className="text-xs text-muted-foreground">Response ID:</span>
                <div className="flex items-center space-x-1 bg-muted/50 rounded px-2 py-1">
                  <code className="text-xs font-mono text-foreground">
                    {previousResponseId.length > 20 
                      ? `${previousResponseId.substring(0, 20)}...` 
                      : previousResponseId
                    }
                  </code>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={handleCopyResponseId}
                    className="h-6 w-6 p-0 text-muted-foreground hover:text-foreground"
                    title="Copy response ID"
                  >
                    {copiedResponseId ? (
                      <Check className="w-3 h-3 text-positive-trend" />
                    ) : (
                      <Copy className="w-3 h-3" />
                    )}
                  </Button>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Messages Area */}
      <div className="flex-1 overflow-y-auto px-6 py-8">
        {messages.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <div className="text-center text-muted-foreground">
              <p className="text-sm">Start a conversation...</p>
            </div>
          </div>
        ) : (
          <div className="max-w-4xl mx-auto">
            {messages.map((message) => (
              <ChatMessage
                key={message.id}
                id={message.id}
                role={message.role}
                content={message.content}
                contentBlocks={message.contentBlocks}
                type={message.type}
                timestamp={message.timestamp}
                hasThinkTags={message.hasThinkTags}
                formatType={message.role === 'assistant' ? textFormat : 'text'}
                apiKey={apiKey}
                baseUrl={baseUrl}
                modelProvider={modelProvider}
                modelName={modelName}
                imageModelProvider={imageModelProvider}
                imageModelName={imageModelName}
                imageProviderKey={imageProviderKey}
                selectedVectorStore={selectedVectorStore}
                instructions={instructions}
                isLoading={message.isLoading}
                onRetry={modelTestMode ? undefined : handleRetry}
              />
            ))}
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

        {/* Input Area */}
        <div className="bg-background px-6 py-4">
          {modelTestMode && showSaveModel ? (
            <div className="max-w-4xl mx-auto space-y-2">
              {saveModelState === 'success' && (
                <Button 
                  onClick={handleSaveModel}
                  className="w-full h-12 bg-positive-trend hover:bg-positive-trend/90 text-white rounded-xl font-medium"
                >
                  <Save className="h-4 w-4 mr-2" />
                  Save Model
                </Button>
              )}
              
              {saveModelState === 'tool_issue' && (
                <>
                  <p className="text-xs text-yellow-600 text-center">Model has problem with tool calling</p>
                  <Button 
                    onClick={handleSaveModel}
                    className="w-full h-12 bg-yellow-500 hover:bg-yellow-600 text-white rounded-xl font-medium"
                  >
                    <Save className="h-4 w-4 mr-2" />
                    Save Model
                  </Button>
                </>
              )}
              
              {saveModelState === 'error' && (
                <>
                  <p className="text-xs text-red-600 text-center">Model connectivity test was not complete</p>
                  <Button 
                    onClick={handleSaveModel}
                    className="w-full h-12 bg-red-500 hover:bg-red-600 text-white rounded-xl font-medium"
                  >
                    <Save className="h-4 w-4 mr-2" />
                    Save Model
                  </Button>
                </>
              )}
            </div>
          ) : !modelTestMode ? (
            <form onSubmit={handleSubmit} className="max-w-4xl mx-auto">
              <div className="relative">
                <Textarea
                  value={inputValue}
                  onChange={handleTextareaChange}
                  onKeyPress={handleKeyPress}
                  placeholder="Chat with your prompt..."
                  className="chat-input-textarea w-full min-h-[96px] max-h-[40vh] resize-none rounded-xl border border-border bg-muted/50 px-4 py-4 pr-12 text-sm placeholder:text-muted-foreground focus:outline-none focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
                  disabled={isLoading}
                  rows={1}
                  style={{ 
                    lineHeight: '1.5',
                    paddingTop: '18px',
                    paddingBottom: '18px',
                    boxShadow: 'none !important',
                    outline: 'none !important'
                  }}
                />
                <div className="absolute bottom-3 right-3">
                  <Button 
                    type="submit" 
                    disabled={!inputValue.trim() || isLoading}
                    className="h-8 w-8 p-0 bg-positive-trend hover:bg-positive-trend/90 text-white rounded-lg transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                    title={inputValue.trim() ? "Send message" : "Type a message to send"}
                  >
                    {isLoading ? (
                      <Loader2 className="h-3 w-3 animate-spin" />
                    ) : (
                      <Send className="h-3 w-3" />
                    )}
                  </Button>
                </div>
              </div>
            </form>
          ) : null}
        </div>
      </div>
    </div>
    
    {/* Code Snippets Modal */}
    <CodeTabs
      open={codeModalOpen}
      onOpenChange={setCodeModalOpen}
      lastRequest={lastRequest}
      baseUrl={apiUrl}
    />
    </>
  );
};

export default AiPlayground; 