import React, { useState, useEffect, useMemo } from 'react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { usePlatformInfo } from '@/contexts/PlatformContext';
import { 
  ChevronDown, 
  ChevronRight, 
  Settings, 
  Trash2, 
  FileText,
  Zap,
  Database,
  Image,
  Search,
  Plus,
  X,
  Code,
  FileSearch,
  Brain,
  Globe,
  Terminal,
  Loader2,
  Copy,
  Check,
  RefreshCw
} from 'lucide-react';
import { MCP } from '@lobehub/icons';
import { API_URL } from '@/config';
import { apiClient } from '@/lib/api';
import ToolConfigModal from './ToolConfigModal';
import ToolsSelectionModal from './ToolsSelectionModal';
import PromptMessagesInline from './PromptMessagesInline';
import ApiKeysModal from './ApiKeysModal';
import ModelSelectionModal from './ModelSelectionModal';
import ConfigurationSettingsModal from './ConfigurationSettingsModal';
import SystemPromptGenerator from './SystemPromptGenerator';
import MCPModal from './MCPModal';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { toast } from 'sonner';

interface Model {
  name: string;
  modelSyntax: string;
  isEmbeddingModel?: boolean;
}

interface Provider {
  name: string;
  description: string;
  supportedModels: Model[];
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

interface ConfigurationPanelProps {
  // Model settings
  modelProvider: string;
  setModelProvider: (provider: string) => void;
  modelName: string;
  setModelName: (name: string) => void;
  
  // Image settings
  imageModelProvider: string;
  setImageModelProvider: (provider: string) => void;
  imageModelName: string;
  setImageModelName: (name: string) => void;
  imageProviderKey: string;
  setImageProviderKey: (key: string) => void;
  
  // API settings
  apiKey: string;
  setApiKey: (key: string) => void;
  baseUrl: string;
  setBaseUrl: (url: string) => void;
  
  // Generation settings
  temperature: number;
  setTemperature: (temp: number) => void;
  maxTokens: number;
  setMaxTokens: (tokens: number) => void;
  topP: number;
  setTopP: (topP: number) => void;
  storeLogs: boolean;
  setStoreLogs: (store: boolean) => void;
  textFormat: 'text' | 'json_object' | 'json_schema';
  setTextFormat: (format: 'text' | 'json_object' | 'json_schema') => void;
  toolChoice: 'auto' | 'none';
  setToolChoice: (choice: 'auto' | 'none') => void;
  
  // System message
  instructions: string;
  setInstructions: (instructions: string) => void;
  
  // Prompt messages
  promptMessages?: PromptMessage[];
  onAddPromptMessage?: (message: PromptMessage) => void;
  onRemovePromptMessage?: (id: string) => void;
  
  // Tools
  selectedTools?: Tool[];
  onSelectedToolsChange?: (tools: Tool[]) => void;
  getMCPToolByLabel?: (label: string) => any;
  
  // Vector store
  selectedVectorStore: string;
  onVectorStoreSelect: (vectorStoreId: string | null) => void;
  
  // Actions
  onResetConversation: () => void;
  
  // API Keys modal trigger
  openApiKeysModal?: boolean;
  onApiKeysModalChange?: (open: boolean) => void;
  jsonSchemaContent: string;
  setJsonSchemaContent: (content: string) => void;
  jsonSchemaName: string | null;
  setJsonSchemaName: (name: string | null) => void;
  // When true, advanced sections (settings, tools, system prompt) are hidden for Masaic Mocky mode
  mockyMode?: boolean;
  // When true, shows Model Test Agent specific UI
  modelTestMode?: boolean;
  modelTestUrl?: string;
  setModelTestUrl?: (url: string) => void;
  modelTestName?: string;
  setModelTestName?: (name: string) => void;
  modelTestApiKey?: string;
  setModelTestApiKey?: (key: string) => void;
  onTestModelConnectivity?: () => void;
  isTestingModel?: boolean;
  className?: string;
}

const ConfigurationPanel: React.FC<ConfigurationPanelProps> = ({
  modelProvider,
  setModelProvider,
  modelName,
  setModelName,
  imageModelProvider,
  setImageModelProvider,
  imageModelName,
  setImageModelName,
  imageProviderKey,
  setImageProviderKey,
  apiKey,
  setApiKey,
  baseUrl,
  setBaseUrl,
  temperature = 1.0,
  setTemperature,
  maxTokens = 2048,
  setMaxTokens,
  topP = 1.0,
  setTopP,
  storeLogs = true,
  setStoreLogs,
  textFormat = 'text',
  setTextFormat,
  toolChoice = 'auto',
  setToolChoice,
  instructions,
  setInstructions,
  promptMessages = [],
  onAddPromptMessage = () => {},
  onRemovePromptMessage = () => {},
  selectedTools = [],
  onSelectedToolsChange = (tools: Tool[]) => {},
  getMCPToolByLabel = () => null,
  selectedVectorStore,
  onVectorStoreSelect,
  onResetConversation,
  openApiKeysModal = false,
  onApiKeysModalChange = (open: boolean) => {},
  jsonSchemaContent,
  setJsonSchemaContent,
  jsonSchemaName,
  setJsonSchemaName,
  mockyMode = false,
  modelTestMode = false,
  modelTestUrl = '',
  setModelTestUrl = () => {},
  modelTestName = '',
  setModelTestName = () => {},
  modelTestApiKey = '',
  setModelTestApiKey = () => {},
  onTestModelConnectivity = () => {},
  isTestingModel = false,
  className = ''
}) => {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refreshTrigger, setRefreshTrigger] = useState(0);
  const [editingFunction, setEditingFunction] = useState<Tool | null>(null);
  const [editingMCP, setEditingMCP] = useState<Tool | null>(null);
  const [editingFileSearch, setEditingFileSearch] = useState<Tool | null>(null);
  const [editingAgenticFileSearch, setEditingAgenticFileSearch] = useState<Tool | null>(null);
  const [apiKeysModalOpen, setApiKeysModalOpen] = useState(false);
  const [requiredProvider, setRequiredProvider] = useState<string | undefined>(undefined);
  const [pendingModelSelection, setPendingModelSelection] = useState<string | null>(null);
  const [isGeneratingPrompt, setIsGeneratingPrompt] = useState(false);
  const [isCopied, setIsCopied] = useState(false);

  /* ---------------- Mocky Mode data ---------------- */
  const [mockServers, setMockServers] = useState<{id:string,url:string,serverLabel:string}[]>([]);
  const [mockFunctions, setMockFunctions] = useState<any[]>([]);
  const [loadingServers, setLoadingServers] = useState(false);
  const [loadingFunctions, setLoadingFunctions] = useState(false);
  const [addServerModalOpen, setAddServerModalOpen] = useState(false);
  const [newServerLabel, setNewServerLabel] = useState('');
  const [selectedFunctionIds, setSelectedFunctionIds] = useState<string[]>([]);
  const [creatingServer, setCreatingServer] = useState(false);
  const [viewingFunction, setViewingFunction] = useState<any|null>(null);
  const [viewingMocks, setViewingMocks] = useState<string[] | null>(null);
  const [mcpPreview, setMcpPreview] = useState<{tools:any[], serverLabel:string}|null>(null);
  interface MCPTool { name:string; description:string; parameters:any; strict?:boolean; type?:string; }

  useEffect(() => {
    if(mockyMode){
      loadMockServers();
      loadMockFunctions();
    }
  }, [mockyMode]);

  const loadMockServers = async () => {
    setLoadingServers(true);
    try{
      const data = await apiClient.jsonRequest<any[]>('/v1/dashboard/mcp/mock/servers');
      setMockServers(data);
    }catch(err){
      console.error(err);
      // Authentication errors are handled by ApiClient
    }finally{
      setLoadingServers(false);
    }
  };

  const loadMockFunctions = async () => {
    setLoadingFunctions(true);
    try{
      const data = await apiClient.jsonRequest<any[]>('/v1/dashboard/mcp/mock/functions');
      setMockFunctions(data);
    }catch(err){
      console.error(err);
      // Authentication errors are handled by ApiClient
    }finally{
      setLoadingFunctions(false);
    }
  };

  const loadFunctionMocks = async (funcId: string) => {
    try {
      const data = await apiClient.jsonRequest<any>(`/v1/dashboard/mcp/mock/functions/${funcId}`);
      const jsonArr: string[] = data?.mocks?.mockJsons || [];
      setViewingMocks(jsonArr);
    } catch (err) {
      console.error(err);
      // Authentication errors are handled by ApiClient
    }
  };

  // Handle copy to clipboard
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(instructions);
      setIsCopied(true);
      setTimeout(() => setIsCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy text:', err);
    }
  };

  // Fetch models from API
  useEffect(() => {
    const fetchModels = async () => {
      setLoading(true);
      setError(null);
      
      try {
        const data = await apiClient.jsonRequest<any[]>('/v1/dashboard/models');
        setProviders(data);
      } catch (err) {
        console.error('Error fetching models:', err);
        
        // Check if it's an authentication error
        if (err instanceof Error && err.message === 'Authentication required') {
          // This will trigger the login screen via ApiClient's handleAuthError
          return;
        }
        
        // For other errors, show appropriate message
        const errorMessage = err instanceof Error && err.message.includes('Network error')
          ? 'Cannot connect to API server.'
          : 'Failed to load models.';
        setError(errorMessage);
        setProviders([]);
      } finally {
        setLoading(false);
      }
    };

    fetchModels();
  }, []);

  // Get all available models from providers - memoized to prevent unnecessary recalculations
  const allModels = useMemo(() => {
    // Get models from API providers
    const apiModels = providers.flatMap(provider => 
      provider.supportedModels
        .filter(model => !model.isEmbeddingModel) // Filter out embedding models
        .map(model => ({
          ...model,
          providerName: provider.name,
          providerDescription: provider.description
        }))
    );

    // Get models from localStorage (own models)
    let ownModels: any[] = [];
    try {
      const savedOwnModels = localStorage.getItem('platform_own_model');
      if (savedOwnModels) {
        const ownModelsData = JSON.parse(savedOwnModels);
        ownModels = ownModelsData.supportedModels.map((model: any) => ({
          name: model.name,
          modelSyntax: model.modelSyntax,
          providerName: ownModelsData.name,
          providerDescription: ownModelsData.description,
          isEmbeddingModel: false
        }));
      }
    } catch (error) {
      console.error('Error loading own models from localStorage:', error);
    }

    // Return own models first, then API models
    return [...ownModels, ...apiModels];
  }, [providers, refreshTrigger]);

  const getModelString = () => `${modelProvider}@${modelName}`;

  const handleDeleteModel = (modelSyntax: string) => {
    try {
      const existingOwnModels = localStorage.getItem('platform_own_model');
      if (existingOwnModels) {
        const ownModelsData = JSON.parse(existingOwnModels);
        
        // Remove the model from supportedModels array
        ownModelsData.supportedModels = ownModelsData.supportedModels.filter(
          (model: any) => model.modelSyntax !== modelSyntax
        );
        
        localStorage.setItem('platform_own_model', JSON.stringify(ownModelsData));
        
        // Trigger refresh to update the dropdown
        setRefreshTrigger(prev => prev + 1);
        
        // Also dispatch storage event for other components
        window.dispatchEvent(new Event('storage'));
      }
    } catch (error) {
      console.error('Error deleting model:', error);
    }
  };

  // Listen for storage events to auto-refresh when models are saved
  useEffect(() => {
    const handleStorageChange = () => {
      setRefreshTrigger(prev => prev + 1);
    };

    window.addEventListener('storage', handleStorageChange);
    return () => window.removeEventListener('storage', handleStorageChange);
  }, []);

  // Check if API key exists for provider
  const checkApiKey = (provider: string): boolean => {
    try {
      const saved = localStorage.getItem('platform_apiKeys');
      if (!saved) return false;
      
      const savedKeys: { name: string; apiKey: string }[] = JSON.parse(saved);
      return savedKeys.some(item => item.name === provider && item.apiKey.trim());
    } catch (error) {
      console.error('Error checking API key:', error);
      return false;
    }
  };

  const handleModelSelect = (modelSyntax: string) => {
    const [provider, name] = modelSyntax.split('@');
    
    // Check if API key exists for this provider
    if (!checkApiKey(provider)) {
      setPendingModelSelection(modelSyntax);
      setRequiredProvider(provider);
      setApiKeysModalOpen(true);
      return; // Don't set the model until API key is provided
    }
    
    // API key exists, proceed with model selection
    setModelProvider(provider);
    setModelName(name);
    setPendingModelSelection(null);
  };

  const imageModels = [
    { provider: 'openai', name: 'dall-e-3', description: 'Advanced image generation' },
    { provider: 'openai', name: 'dall-e-2', description: 'Reliable image generation' },
    { provider: 'google', name: 'imagen-3.0-generate-002', description: 'Google\'s Imagen model' },
    { provider: 'gemini', name: 'imagen-3.0-generate-002', description: 'Gemini Imagen model' },
  ];

  const handleImageModelSelect = (modelString: string) => {
    const [provider, name] = modelString.split('@');
    setImageModelProvider(provider);
    setImageModelName(name);
  };

  // Tool management handlers
  const handleToolSelect = (tool: Tool) => {
    if (tool.id === 'function') {
      // If editing an existing function, remove the old one first
      if (editingFunction) {
        const updatedTools = selectedTools.filter(t => 
          !(t.id === 'function' && t.functionDefinition === editingFunction.functionDefinition)
        );
        onSelectedToolsChange([...updatedTools, tool]);
      } else {
        // Add the new function
        onSelectedToolsChange([...selectedTools, tool]);
      }
    } else if (tool.id === 'mcp_server') {
      // If editing an existing MCP server, remove the old one first
      if (editingMCP) {
        const updatedTools = selectedTools.filter(t => 
          !(t.id === 'mcp_server' && t.mcpConfig?.label === editingMCP.mcpConfig?.label)
        );
        onSelectedToolsChange([...updatedTools, tool]);
      } else {
        // Add the new MCP server
        onSelectedToolsChange([...selectedTools, tool]);
      }
    } else {
      // For other tools, check by id only
      if (!selectedTools.find(t => t.id === tool.id)) {
        onSelectedToolsChange([...selectedTools, tool]);
      }
    }
  };

  const handleToolRemove = (toolId: string, functionDefinition?: string, toolIndex?: number) => {
    if (toolId === 'function' && functionDefinition) {
      // Remove specific function by definition
      const updatedTools = selectedTools.filter(tool => 
        !(tool.id === 'function' && tool.functionDefinition === functionDefinition)
      );
      onSelectedToolsChange(updatedTools);
    } else if (toolId === 'mcp_server' && toolIndex !== undefined) {
      // For MCP servers, remove by index since we allow multiple
      const updatedTools = selectedTools.filter((_, index) => index !== toolIndex);
      onSelectedToolsChange(updatedTools);
    } else {
      // Remove by id for other tools
      const updatedTools = selectedTools.filter(tool => tool.id !== toolId);
      onSelectedToolsChange(updatedTools);
    }
  };

  const handleFunctionEdit = (tool: Tool) => {
    setEditingFunction(tool);
  };

  const handleMCPEdit = (tool: Tool) => {
    setEditingMCP(tool);
  };

  const handleFileSearchEdit = (tool: Tool) => {
    setEditingFileSearch(tool);
  };

  const handleAgenticFileSearchEdit = (tool: Tool) => {
    setEditingAgenticFileSearch(tool);
  };

  // Get tool color based on type
  const getToolColor = (toolId: string) => {
    switch (toolId) {
      case 'mcp_server':
        return 'positive-trend';
      case 'function':
        return 'opportunity';
      case 'file_search':
        return 'critical-alert';
      case 'agentic_file_search':
        return 'negative-trend';
      case 'image_generation':
        return 'neutral';
      case 'think':
        return 'positive-trend';
      case 'fun_req_gathering_tool':
      case 'fun_def_generation_tool':
        return 'positive-trend';
      case 'mock_fun_save_tool':
        return 'positive-trend';
      case 'mock_generation_tool':
      case 'mock_save_tool':
        return 'positive-trend';
      default:
        return 'positive-trend';
    }
  };

  // --- JSON highlighting helpers (copy from JsonSchemaModal) ---
  const highlightLine = (line: string) => {
    // Regex patterns
    if (line.includes(':') && line.includes('"')) {
      const keyMatch = line.match(/"([^\"]+)"(\s*:)/);
      if (keyMatch) {
        const beforeKey = line.substring(0, line.indexOf(keyMatch[0]));
        const key = keyMatch[1];
        const afterKey = line.substring(line.indexOf(keyMatch[0]) + keyMatch[0].length);
        return (
          <span>
            <span className="text-muted-foreground">{beforeKey}</span>
            <span className="text-positive-trend">"{key}"</span>
            <span className="text-muted-foreground">:</span>
            <span className="text-foreground">{afterKey}</span>
          </span>
        );
      }
    } else if (line.includes('"') && !line.includes(':')) {
      return <span className="text-positive-trend">{line}</span>;
    } else if (line.match(/\b(true|false|null)\b/)) {
      return <span className="text-positive-trend">{line}</span>;
    } else if (line.match(/\b\d+\b/)) {
      return <span className="text-foreground">{line}</span>;
    }
    return <span className="text-muted-foreground">{line}</span>;
  };

  const highlightJson = (jsonString: string) => {
    if (!jsonString.trim()) return null;
    try {
      const parsed = JSON.parse(jsonString);
      const formatted = JSON.stringify(parsed, null, 2);
      const lines = formatted.split('\n');
      return (
        <pre className="font-mono text-xs leading-relaxed whitespace-pre-wrap">
          {lines.map((line, idx) => (<div key={idx}>{highlightLine(line)}</div>))}
        </pre>
      );
    } catch {
      return (
        <pre className="font-mono text-xs leading-relaxed whitespace-pre-wrap text-foreground">
          {jsonString}
        </pre>
      );
    }
  };

  const handleServerClick = async (server:{id:string,url:string,serverLabel:string})=>{
    try{
      const tools: MCPTool[] = await apiClient.jsonRequest<MCPTool[]>('/v1/dashboard/mcp/list_actions', {
        method:'POST',
        body: JSON.stringify({ serverLabel: server.serverLabel, serverUrl: server.url, headers:{} })
      });
      setMcpPreview({tools, serverLabel: server.serverLabel});
    }catch(err){ 
      console.error(err); 
      // Authentication errors are handled by ApiClient
      if (!(err instanceof Error && err.message === 'Authentication required')) {
        toast.error('Error fetching tools'); 
      }
    }
  };

  const { platformInfo } = usePlatformInfo();
  const isVectorStoreEnabled = platformInfo?.vectorStoreInfo?.isEnabled ?? true;

  return (
    <div className={cn("bg-background border-r border-border h-full overflow-y-auto", className)}>
      <div className="p-4 h-full flex flex-col">
        
        {/* Header with Model Selection and Config Icons */}
        <div className="space-y-3 flex-shrink-0">
          {/* Error Banner for API Connection Issues */}
          {error && (
            <div className="p-3 bg-red-500/10 border border-red-500/20 rounded-lg">
              <div className="flex items-center space-x-2">
                <div className="w-2 h-2 bg-red-500 rounded-full animate-pulse"></div>
                <span className="text-sm text-red-600 font-medium">API Connection Error</span>
              </div>
              <p className="text-xs text-red-500/80 mt-1">{error}</p>
              <p className="text-xs text-red-500/60 mt-1">
                Please check your API server connection and try again.
              </p>
            </div>
          )}
          
          {/* Model Test Agent UI */}
          {modelTestMode ? (
            <div className="space-y-4">
              {/* URL Field */}
              <div className="space-y-2">
                <div className="space-y-1">
                  <Label className="text-sm font-medium">Model base url</Label>
                </div>
                <TooltipProvider>
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <Input
                        placeholder="https://api.example.com/v1"
                        value={modelTestUrl}
                        onChange={(e) => setModelTestUrl(e.target.value)}
                        className={`text-sm ${
                          modelTestUrl && !modelTestUrl.match(/^https?:\/\/.+/) 
                            ? 'border-red-500 focus:border-red-500' 
                            : ''
                        }`}
                      />
                    </TooltipTrigger>
                    <TooltipContent>
                      <p style={{ fontSize: '12px' }}>
                        Example: If absolute URL=https://example.com/v1/chat/completions then enter https://example.com/v1
                      </p>
                    </TooltipContent>
                  </Tooltip>
                </TooltipProvider>
                {modelTestUrl && !modelTestUrl.match(/^https?:\/\/.+/) && (
                  <p className="text-xs text-red-500">URL must start with http:// or https://</p>
                )}
              </div>
              
              {/* Model Name */}
              <div className="flex items-center space-x-3">
                <div className="flex-1 space-y-2">
                  <Label className="text-sm font-medium">Model Name</Label>
                  <Input
                    placeholder="gpt-4o"
                    value={modelTestName}
                    onChange={(e) => setModelTestName(e.target.value)}
                    className="text-sm"
                  />
                </div>
                <div className="flex items-center space-x-2 pt-6">
                  <ConfigurationSettingsModal
                    textFormat={textFormat}
                    setTextFormat={setTextFormat}
                    toolChoice={toolChoice}
                    setToolChoice={setToolChoice}
                    temperature={temperature}
                    setTemperature={setTemperature}
                    maxTokens={maxTokens}
                    setMaxTokens={setMaxTokens}
                    topP={topP}
                    setTopP={setTopP}
                    jsonSchemaContent={jsonSchemaContent}
                    setJsonSchemaContent={setJsonSchemaContent}
                    jsonSchemaName={jsonSchemaName}
                    setJsonSchemaName={setJsonSchemaName}
                  />
                </div>
              </div>

              {/* Model Settings Display */}
              <div className="flex flex-wrap gap-1 text-xs text-muted-foreground">
                <span>text_format:</span>
                <span className="text-positive-trend font-medium">{textFormat}</span>
                <span className="ml-2">tool_choice:</span>
                <span className="text-positive-trend font-medium">{toolChoice}</span>
                <span className="ml-2">temp:</span>
                <span className="text-positive-trend font-medium">{temperature}</span>
                <span className="ml-2">tokens:</span>
                <span className="text-positive-trend font-medium">{maxTokens}</span>
              </div>

              {/* API Key Field */}
              <div className="space-y-2">
                <Label className="text-sm font-medium">API Key</Label>
                <Input
                  type="password"
                  placeholder="sk-..."
                  value={modelTestApiKey}
                  onChange={(e) => setModelTestApiKey(e.target.value)}
                  className="text-sm"
                />
              </div>

              {/* Test Model Connectivity Button */}
              <Button
                onClick={onTestModelConnectivity}
                disabled={isTestingModel || !modelTestUrl.trim() || !modelTestName.trim() || !modelTestApiKey.trim() || (modelTestUrl && !modelTestUrl.match(/^https?:\/\/.+/))}
                className="w-full bg-positive-trend hover:bg-positive-trend/90 text-white disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {isTestingModel ? (
                  <>
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                    Testing Model Connectivity...
                  </>
                ) : (
                  'Test Model Connectivity'
                )}
              </Button>
            </div>
          ) : (
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-3 flex-1">
                <Label className="text-sm font-medium whitespace-nowrap">Model</Label>
                <ModelSelectionModal
                  models={allModels}
                  selectedModel={getModelString()}
                  onModelSelect={handleModelSelect}
                  loading={loading}
                  error={error}
                  onDeleteModel={handleDeleteModel}
                />
              </div>
              {/* Settings icon hidden in Masaic Mocky mode */}
              {!mockyMode && (
              <div className="flex items-center space-x-2">
                <ConfigurationSettingsModal
                  textFormat={textFormat}
                  setTextFormat={setTextFormat}
                  toolChoice={toolChoice}
                  setToolChoice={setToolChoice}
                  temperature={temperature}
                  setTemperature={setTemperature}
                  maxTokens={maxTokens}
                  setMaxTokens={setMaxTokens}
                  topP={topP}
                  setTopP={setTopP}
                  jsonSchemaContent={jsonSchemaContent}
                  setJsonSchemaContent={setJsonSchemaContent}
                  jsonSchemaName={jsonSchemaName}
                  setJsonSchemaName={setJsonSchemaName}
                />
              </div>
              )}
            </div>
          )}
          
          {/* Configuration Parameters summary hidden in Masaic Mocky mode and Model Test mode */}
          {!mockyMode && !modelTestMode && (
          <div className="flex flex-wrap gap-1 text-xs text-muted-foreground">
            <span>text_format:</span>
            <span className="text-positive-trend font-medium">{textFormat}</span>
            <span className="ml-2">tool_choice:</span>
            <span className="text-positive-trend font-medium">{toolChoice}</span>
            <span className="ml-2">temp:</span>
            <span className="text-positive-trend font-medium">{temperature}</span>
            <span className="ml-2">tokens:</span>
            <span className="text-positive-trend font-medium">{maxTokens}</span>
          </div>
          )}
        </div>

        {/* Tools Section (hidden in Masaic Mocky mode and Model Test mode) */}
        {!mockyMode && !modelTestMode && (
        <div className="mt-6 flex-shrink-0">
          <div className="flex items-center justify-between">
            <div className="flex items-center flex-wrap gap-2">
              <Label className="text-sm font-medium">Tools:</Label>
              
                          {/* Selected Tools */}
            {selectedTools.map((tool, index) => {
              const IconComponent = tool.icon;
              const colorClass = getToolColor(tool.id);
              
              // Get display name based on tool type
              const getDisplayName = (tool: Tool) => {
                if (tool.id === 'function' && tool.functionDefinition) {
                  try {
                    const parsed = JSON.parse(tool.functionDefinition);
                    return parsed.name || 'Function';
                  } catch {
                    return 'Function';
                  }
                }
                if (tool.id === 'file_search' && tool.fileSearchConfig) {
                  return tool.fileSearchConfig.vectorStoreNames.length > 0 ? tool.fileSearchConfig.vectorStoreNames.join(', ') : 'File Search';
                }
                if (tool.id === 'agentic_file_search' && tool.agenticFileSearchConfig) {
                  const displayName = tool.agenticFileSearchConfig.vectorStoreNames.length > 0 ? tool.agenticFileSearchConfig.vectorStoreNames.join(', ') : 'Agentic File Search';
                  return `${displayName} (${tool.agenticFileSearchConfig.iterations})`;
                }
                return tool.name;
              };
              
              const displayName = getDisplayName(tool);
              const isFunction = tool.id === 'function';
              const isMCP = tool.id === 'mcp_server';
              const isFileSearch = tool.id === 'file_search';
              const isAgenticFileSearch = tool.id === 'agentic_file_search';
              const isClickable = isFunction || isMCP || isFileSearch || isAgenticFileSearch;
              const toolKey = (isFunction || isMCP) ? `${tool.id}-${index}` : tool.id;
              
              // Check if file search tools should be disabled
              const isToolDisabled = (isFileSearch || isAgenticFileSearch) && !isVectorStoreEnabled;
              const tooltipMessage = isToolDisabled ? 'To enable, boot up platform with Qdrant vector store' : null;
              
              const toolElement = (
                <div 
                  key={toolKey}
                  className={`flex items-center space-x-1 rounded px-2 py-1 focus:ring-2 ${
                    isToolDisabled 
                      ? 'bg-gray-400/10 border border-gray-400/20 opacity-50' 
                      : 'bg-positive-trend/10 border border-positive-trend/20 focus:ring-positive-trend/30 focus:border-positive-trend'
                  } ${
                    isClickable && !isToolDisabled ? 'cursor-pointer hover:bg-positive-trend/20' : 
                    isToolDisabled ? 'cursor-not-allowed' : ''
                  }`}
                  tabIndex={0}
                  onClick={
                    !isToolDisabled ? (
                      isFunction ? () => handleFunctionEdit(tool) :
                      isMCP ? () => handleMCPEdit(tool) :
                      isFileSearch ? () => handleFileSearchEdit(tool) :
                      isAgenticFileSearch ? () => handleAgenticFileSearchEdit(tool) :
                      undefined
                    ) : (e) => {
                      e.preventDefault();
                      e.stopPropagation();
                    }
                  }
                >
                  <IconComponent className={`h-3 w-3 ${isToolDisabled ? 'text-gray-400' : 'text-positive-trend'}`} />
                  <span className={`text-xs font-medium ${isToolDisabled ? 'text-gray-400' : 'text-positive-trend'}`}>
                    {displayName}
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    className={`h-4 w-4 p-0 ${isToolDisabled ? 'hover:bg-gray-400/20' : 'hover:bg-positive-trend/20'}`}
                    onClick={(e) => {
                      e.stopPropagation(); // Prevent triggering the edit handler
                      if (tool.id === 'function') {
                        handleToolRemove(tool.id, tool.functionDefinition);
                      } else if (tool.id === 'mcp_server') {
                        handleToolRemove(tool.id, undefined, index);
                      } else {
                        handleToolRemove(tool.id);
                      }
                    }}
                  >
                    <X className={`h-3 w-3 ${isToolDisabled ? 'text-gray-400' : 'text-positive-trend'}`} />
                  </Button>
                </div>
              );
              
              // Return with tooltip if needed
              if (tooltipMessage) {
                return (
                  <TooltipProvider key={toolKey}>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <div>
                          {toolElement}
                        </div>
                      </TooltipTrigger>
                      <TooltipContent>
                        <p>{tooltipMessage}</p>
                      </TooltipContent>
                    </Tooltip>
                  </TooltipProvider>
                );
              }
              
              return toolElement;
            })}
            </div>
            
            {/* Add Tool Button */}
            <ToolsSelectionModal
              selectedTools={selectedTools}
              onToolSelect={handleToolSelect}
              editingFunction={editingFunction}
              onEditingFunctionChange={setEditingFunction}
              editingMCP={editingMCP}
              onEditingMCPChange={setEditingMCP}
              editingFileSearch={editingFileSearch}
              onEditingFileSearchChange={setEditingFileSearch}
              editingAgenticFileSearch={editingAgenticFileSearch}
              onEditingAgenticFileSearchChange={setEditingAgenticFileSearch}
              getMCPToolByLabel={getMCPToolByLabel}
            >
              <Button
                variant="ghost"
                size="sm"
                className="h-6 text-xs text-muted-foreground hover:bg-muted/50 focus:bg-positive-trend/10 focus:text-positive-trend"
              >
                +
              </Button>
            </ToolsSelectionModal>
          </div>
        </div>
        )}

        {/* Mocky Mode Lists */}
        {mockyMode && (
          <div className="mt-6 flex flex-col flex-grow min-h-0 space-y-6">
            {/* Mock Servers List */}
            <Card className="flex flex-col flex-1 min-h-0">
              <div className="p-4 border-b border-border flex items-center justify-between">
                <h4 className="text-sm font-medium">Mock Servers</h4>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setAddServerModalOpen(true)}
                  className="h-6 w-6 text-muted-foreground hover:text-foreground"
                >
                  <Plus className="h-4 w-4" />
                </Button>
              </div>
              <div className="p-4 space-y-2 flex-1 overflow-y-auto min-h-0" style={{ scrollbarWidth:'thin' }}>
                {loadingServers ? (
                  <div className="flex items-center justify-center py-6">
                    <Loader2 className="h-5 w-5 animate-spin" />
                  </div>
                ) : mockServers.length === 0 ? (
                  <p className="text-xs text-muted-foreground">No mock servers found.</p>
                ) : (
                  mockServers.map(server => (
                    <div key={server.id} className="flex items-start justify-between p-3 rounded-lg border transition-all duration-200 cursor-pointer hover:shadow-sm focus:outline-none focus:ring-2 focus:ring-positive-trend/20 border-border/20 hover:border-positive-trend/40 hover:bg-positive-trend/5" onClick={() => handleServerClick(server)}>
                      <div className="flex-1 min-w-0">
                        <h5 className="text-xs font-semibold text-foreground truncate">{server.serverLabel}</h5>
                        <p className="text-xs text-muted-foreground truncate">{server.url}</p>
                      </div>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={(e)=>{e.stopPropagation(); navigator.clipboard.writeText(server.url); toast.success('Server URL copied');}}
                        className="border-border text-foreground hover:bg-positive-trend/10 focus:outline-none focus:ring-2 focus:ring-positive-trend/50"
                      >
                        <Copy className="h-3 w-3" />
                      </Button>
                    </div>
                  ))
                )}
              </div>
            </Card>

            {/* Mock Functions List */}
            <Card className="flex flex-col flex-1 min-h-0">
              <div className="p-4 border-b border-border flex items-center justify-between">
                <h4 className="text-sm font-medium">Mock Functions</h4>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={loadMockFunctions}
                  className="h-6 w-6 p-0 hover:bg-muted/50"
                  title="Refresh functions"
                >
                  <RefreshCw className="h-4 w-4 text-muted-foreground" />
                </Button>
              </div>
              <div className="p-4 space-y-2 flex-1 overflow-y-auto min-h-0" style={{ scrollbarWidth:'thin' }}>
                {loadingFunctions ? (
                  <div className="flex items-center justify-center py-6">
                    <Loader2 className="h-5 w-5 animate-spin" />
                  </div>
                ) : mockFunctions.length === 0 ? (
                  <p className="text-xs text-muted-foreground">No mock functions found.</p>
                ) : (
                  mockFunctions.map(func => (
                    <div key={func.id} className="p-3 rounded-lg border transition-all duration-200 border-border/20 hover:border-positive-trend/40 hover:bg-positive-trend/5">
                      <div className="flex items-start justify-between">
                        <div className="flex-1 min-w-0 space-y-0.5">
                          <div className="flex items-center space-x-2">
                            <span className="text-xs font-semibold text-foreground truncate max-w-[160px]">{func.name}</span>
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => { navigator.clipboard.writeText(func.id); toast.success('Function ID copied'); }}
                              className="border-border text-foreground hover:bg-positive-trend/10 focus:outline-none focus:ring-2 focus:ring-positive-trend/50"
                              title="Copy ID"
                            >
                              <Copy className="h-3 w-3" />
                            </Button>
                          </div>
                          <p className="text-xs text-muted-foreground truncate">{func.description}</p>
                        </div>
                        <div className="flex-shrink-0 flex flex-row items-center space-x-2 pl-3">
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-6 px-2 text-xs bg-positive-trend/10 text-positive-trend hover:bg-positive-trend/20 focus:outline-none focus:ring-2 focus:ring-positive-trend/20 rounded-full"
                            onClick={() => setViewingFunction(func)}
                          >
                            View Function
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-6 px-2 text-xs bg-positive-trend/10 text-positive-trend hover:bg-positive-trend/20 focus:outline-none focus:ring-2 focus:ring-positive-trend/20 rounded-full"
                            onClick={() => loadFunctionMocks(func.id)}
                          >
                            View Mocks
                          </Button>
                        </div>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </Card>

            {/* Add Mock Server Modal (blank) */}
            <Dialog open={addServerModalOpen} onOpenChange={(open)=>{ if(!open){ setAddServerModalOpen(false); setNewServerLabel(''); setSelectedFunctionIds([]);} }}>
              <DialogContent className="max-w-lg w-full">
                <DialogHeader>
                  <DialogTitle>Add Mock Server</DialogTitle>
                  <DialogDescription className="text-sm text-muted-foreground">Provide a label and select mock functions to include.</DialogDescription>
                </DialogHeader>
                <div className="space-y-4 max-h-[70vh] overflow-y-auto" style={{scrollbarWidth:'thin'}}>
                  {/* Label input */}
                  <div className="space-y-2">
                    <Label className="text-sm font-medium">Server label</Label>
                    <Input
                      value={newServerLabel}
                      onChange={(e)=> setNewServerLabel(e.target.value)}
                      placeholder="my-mock-server"
                      disabled={creatingServer}
                    />
                  </div>

                  {/* Functions list */}
                  <div className="space-y-2">
                    <Label className="text-sm font-medium">Select Functions</Label>
                    {loadingFunctions ? (
                      <div className="flex items-center justify-center py-6"><Loader2 className="h-5 w-5 animate-spin" /></div>
                    ): (
                      <div className="max-h-60 overflow-y-auto space-y-1 border border-border rounded-lg p-2" style={{scrollbarWidth:'thin'}}>
                        {mockFunctions.map(func=>{
                          const selected = selectedFunctionIds.includes(func.id);
                          return (
                            <div
                              key={func.id}
                              className={`grid grid-cols-[1.25rem_1fr_auto] gap-3 p-3 rounded-lg border transition-all duration-200 cursor-pointer ${selected ? 'border-positive-trend bg-positive-trend/10' : 'border-border/20 hover:border-positive-trend/40 hover:bg-positive-trend/5'}`}
                              onClick={()=> setSelectedFunctionIds(prev=> prev.includes(func.id)? prev.filter(id=>id!==func.id):[...prev,func.id])}
                            >
                              {/* Icon column */}
                              <div className="pt-0.5">
                                {selected && <Check className="h-4 w-4 text-positive-trend"/>}
                              </div>

                              {/* Name + description */}
                              <div className="min-w-0">
                                <p className="text-sm font-medium truncate max-w-[160px]">{func.name}</p>
                                <p className="text-xs text-muted-foreground truncate">{func.description}</p>
                              </div>

                              {/* CTA column */}
                              <div className="flex space-x-2 items-start">
                                <Button variant="ghost" size="sm" className="h-6 px-2 text-xs bg-positive-trend/10 text-positive-trend hover:bg-positive-trend/20" onClick={(e)=>{e.stopPropagation(); setViewingFunction(func);}}>View Function</Button>
                                <Button variant="ghost" size="sm" className="h-6 px-2 text-xs bg-positive-trend/10 text-positive-trend hover:bg-positive-trend/20" onClick={(e)=>{e.stopPropagation(); loadFunctionMocks(func.id);}}>View Mocks</Button>
                              </div>
                            </div>
                          );
                        })}
                        {mockFunctions.length===0 && <p className="text-xs text-muted-foreground">No functions available.</p>}
                      </div>
                    )}
                  </div>

                  {/* Actions */}
                  <div className="flex items-center justify-end space-x-2 pt-4">
                    <Button variant="ghost" onClick={()=> setAddServerModalOpen(false)} disabled={creatingServer}>Cancel</Button>
                    <Button
                      onClick={async()=>{
                        if(!newServerLabel.trim() || selectedFunctionIds.length===0) return;
                        setCreatingServer(true);
                        try{
                          const res = await apiClient.rawRequest('/v1/dashboard/mcp/mock/servers',{
                            method:'POST',
                            body: JSON.stringify({ serverLabel:newServerLabel.trim(), toolIds:selectedFunctionIds })
                          });
                          if(res.ok){
                            toast.success('Mock server created');
                            setAddServerModalOpen(false);
                            setNewServerLabel('');
                            setSelectedFunctionIds([]);
                            loadMockServers();
                          }else{
                            toast.error('Failed to create mock server');
                          }
                        }catch(err){ toast.error('Error creating server'); } finally{ setCreatingServer(false);}                        
                      }}
                      disabled={creatingServer || !newServerLabel.trim() || selectedFunctionIds.length===0}
                      className="bg-positive-trend hover:bg-positive-trend/90 text-white"
                    >{creatingServer? <Loader2 className="h-4 w-4 animate-spin" /> : 'Create'}</Button>
                  </div>
                </div>
              </DialogContent>
            </Dialog>

            {/* Function Detail Modal */}
            <Dialog open={!!viewingFunction} onOpenChange={(open)=>{ if(!open) setViewingFunction(null); }}>
              <DialogContent className="max-w-2xl">
                <DialogHeader>
                  <DialogTitle>Function Details</DialogTitle>
                  <DialogDescription className="max-h-[70vh] overflow-y-auto">
                    <div className="bg-muted/50 border border-border rounded-lg p-4 max-h-[60vh] overflow-auto" style={{ scrollbarWidth:'thin' }}>
                      {highlightJson(viewingFunction ? JSON.stringify(viewingFunction, null, 2) : '')}
                    </div>
                  </DialogDescription>
                </DialogHeader>
              </DialogContent>
            </Dialog>

            {/* Function Mocks Modal */}
            <Dialog open={viewingMocks !== null} onOpenChange={(open)=>{ if(!open) setViewingMocks(null); }}>
              <DialogContent className="max-w-3xl">
                <DialogHeader>
                  <DialogTitle>Function Mocks</DialogTitle>
                  <DialogDescription className="space-y-4 max-h-[70vh] overflow-y-auto">
                    {viewingMocks?.length === 0 && (
                      <p className="text-sm text-muted-foreground">No mocks available.</p>
                    )}
                    {viewingMocks?.map((mockJson, idx)=>(
                      <div key={idx} className="bg-muted/50 border border-border rounded-lg p-4 overflow-auto" style={{ scrollbarWidth:'thin' }}>
                        {highlightJson(mockJson)}
                      </div>
                    ))}
                  </DialogDescription>
                </DialogHeader>
              </DialogContent>
            </Dialog>

            {/* MCP Server Preview Modal (read-only) */}
            {mcpPreview && (
              <MCPModal
                open={true}
                onOpenChange={(open)=>{ if(!open) setMcpPreview(null); }}
                onConnect={()=>{}}
                readOnly
                preloadedTools={mcpPreview.tools}
                initialConfig={{ url:'', label:mcpPreview.serverLabel, authentication:'none', selectedTools:[] }}
              />
            )}
          </div>
        )}

        {/* System Message - hidden in Masaic Mocky mode and Model Test mode */}
        {!mockyMode && !modelTestMode && (
        <div className="mt-6 flex flex-col flex-grow min-h-0">
          <div className="flex items-center justify-between mb-3 flex-shrink-0">
            <Label className="text-sm font-medium">System message</Label>
            <SystemPromptGenerator 
              onGenerate={setInstructions} 
              existingPrompt={instructions} 
              isLoading={isGeneratingPrompt}
              onLoadingChange={setIsGeneratingPrompt}
            />
          </div>
          <div className="relative flex-grow min-h-0">
            <Button
              variant="ghost"
              size="icon"
              className="absolute top-2 right-2 h-6 w-6 text-muted-foreground hover:text-foreground z-10"
              onClick={handleCopy}
            >
              {isCopied ? (
                <Check className="h-3.5 w-3.5" />
              ) : (
                <Copy className="h-3.5 w-3.5" />
              )}
            </Button>
            <Textarea
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
              placeholder="Describe desired model behavior (tone, tool, usage, response style)"
              className="h-full w-full text-sm resize-none bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
              style={{ 
                boxShadow: 'none !important',
                outline: 'none !important'
              }}
              disabled={isGeneratingPrompt}
            />
            {isGeneratingPrompt && (
              <div className="absolute inset-0 bg-muted/50 border border-border rounded-md flex items-center justify-center">
                <div className="flex flex-col items-center space-y-3">
                  <div className="w-8 h-8 border-2 border-positive-trend border-t-transparent rounded-full animate-spin"></div>
                  <p className="text-sm text-muted-foreground">Generating prompt...</p>
                </div>
              </div>
            )}
          </div>
        </div>
        )}

        {/* Remove or comment out the PromptMessagesInline component */}
        {/* <PromptMessagesInline
          promptMessages={promptMessages}
          onAddPromptMessage={onAddPromptMessage}
          onRemovePromptMessage={onRemovePromptMessage}
        /> */}

      </div>

      {/* API Keys Modal */}
      <ApiKeysModal
        open={apiKeysModalOpen || openApiKeysModal}
        onOpenChange={(open) => {
          setApiKeysModalOpen(open);
          onApiKeysModalChange(open);
          if (!open) {
            // Clear state when modal closes (regardless of reason)
            setRequiredProvider(undefined);
            setPendingModelSelection(null);
          }
        }}
        onSaveSuccess={() => {
          // Only complete model selection when keys are successfully saved
          if (pendingModelSelection && requiredProvider) {
            // Add a small delay to ensure localStorage is updated
            setTimeout(() => {
              if (checkApiKey(requiredProvider)) {
                const [provider, name] = pendingModelSelection.split('@');
                setModelProvider(provider);
                setModelName(name);
              }
            }, 100);
          }
        }}
        requiredProvider={requiredProvider}
      />
    </div>
  );
};

export default ConfigurationPanel; 