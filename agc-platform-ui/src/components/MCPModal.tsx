import React, { useState, useEffect } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { ChevronLeft, ChevronRight, Eye, EyeOff, Plus, X, Loader2, Check, Edit } from 'lucide-react';
import { MCP } from '@lobehub/icons';
import { API_URL } from '@/config';
import { apiClient } from '@/lib/api';

interface MCPModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConnect: (config: MCPServerConfig & { selectedTools: string[] }) => void;
  initialConfig?: MCPServerConfig & { selectedTools: string[] };
  readOnly?: boolean;
  preloadedTools?: MCPTool[];
}

interface MCPServerConfig {
  url: string;
  label: string;
  authentication: 'none' | 'access_token' | 'custom_headers';
  accessToken?: string;
  customHeaders?: { key: string; value: string }[];
}

interface MCPTool {
  type: string;
  description: string;
  name: string;
  parameters: {
    type: string;
    properties: Record<string, any>;
    required?: string[];
    additionalProperties?: boolean;
  };
  strict?: boolean;
}

type ModalView = 'connect' | 'connected' | 'tool-detail';

const MCPModal: React.FC<MCPModalProps> = ({
  open,
  onOpenChange,
  onConnect,
  initialConfig,
  readOnly = false,
  preloadedTools = []
}) => {
  const [url, setUrl] = useState('');
  const [label, setLabel] = useState('');
  const [authentication, setAuthentication] = useState<'none' | 'access_token' | 'custom_headers'>('access_token');
  const [accessToken, setAccessToken] = useState('');
  const [showToken, setShowToken] = useState(false);
  const [customHeaders, setCustomHeaders] = useState<{ key: string; value: string }[]>([{ key: '', value: '' }]);
  const [isConnecting, setIsConnecting] = useState(false);
  const [currentView, setCurrentView] = useState<ModalView>(readOnly ? 'connected' : 'connect');
  const [tools, setTools] = useState<MCPTool[]>(preloadedTools);
  const [selectedTools, setSelectedTools] = useState<string[]>([]);
  const [selectedTool, setSelectedTool] = useState<MCPTool | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [serverSuggestions, setServerSuggestions] = useState<{url:string,label:string}[]>([]);
  const [showUrlSuggestions, setShowUrlSuggestions] = useState(false);
  const filteredUrlSuggestions = serverSuggestions.filter(sug => {
    if (!url.trim()) return false;
    const query = url.toLowerCase();
    const urlStr = (sug.url || '').toLowerCase();
    const labelStr = (sug.label || '').toLowerCase();
    return urlStr.includes(query) || labelStr.includes(query);
  });

  const apiUrl = API_URL;

  useEffect(()=>{
    if(readOnly) return;
    // fetch suggestions once when modal opens
    if(open){
      apiClient.jsonRequest<any[]>('/v1/dashboard/mcp/mock/servers')
        .then((data:any[])=>{
          const mapped = data.map(item=>({ url:item.url, label:item.serverLabel || item.label || item.url }));
          setServerSuggestions(mapped);
        })
        .catch(()=>{});
    }
  },[open, readOnly]);

  useEffect(() => {
    const handler = () => setShowUrlSuggestions(false);
    if(showUrlSuggestions){
      window.addEventListener('click', handler);
    }
    return () => window.removeEventListener('click', handler);
  }, [showUrlSuggestions]);

  const handleConnect = async () => {
    setIsConnecting(true);
    
    try {
      // Prepare headers based on authentication type
      let headers: Record<string, string> = {
        'Content-Type': 'application/json'
      };

      if (authentication === 'access_token' && accessToken.trim()) {
        headers['Authorization'] = `Bearer ${accessToken.trim()}`;
      } else if (authentication === 'custom_headers') {
        const validHeaders = customHeaders.filter(h => h.key.trim() && h.value.trim());
        validHeaders.forEach(header => {
          headers[header.key.trim()] = header.value.trim();
        });
      }

      // Prepare request body
      const requestBody = {
        serverLabel: label.trim(),
        serverUrl: url.trim(),
        headers: authentication === 'none' ? {} : headers
      };

      // Make API call
      const data: MCPTool[] = await apiClient.jsonRequest<MCPTool[]>('/v1/dashboard/mcp/list_actions', {
        method: 'POST',
        body: JSON.stringify(requestBody)
      });
      
      // Simulate connection animation delay
      setTimeout(() => {
        setTools(data);
        
        // If editing (initialConfig exists), preserve previously selected tools
        // Otherwise, select all tools by default for new connections
        if (isEditing && initialConfig && initialConfig.selectedTools) {
          // Only select tools that still exist in the new API response
          const availableToolNames = data.map(tool => tool.name);
          const validSelectedTools = initialConfig.selectedTools.filter(toolName => 
            availableToolNames.includes(toolName)
          );
          setSelectedTools(validSelectedTools);
        } else {
          setSelectedTools(data.map(tool => tool.name)); // Select all tools by default
        }
        
        setCurrentView('connected');
        setIsConnecting(false);
      }, 1500);

    } catch (error) {
      console.error('Failed to connect to MCP server:', error);
      setIsConnecting(false);
      // You might want to show an error message here
    }
  };

  const fetchToolsForEditing = async () => {
    if (!initialConfig) return;
    
    setIsConnecting(true);
    
    try {
      // Use initialConfig values directly instead of state that might not be set yet
      let headers: Record<string, string> = {
        'Content-Type': 'application/json'
      };

      const auth = initialConfig.authentication || 'access_token';
      const token = initialConfig.accessToken || '';
      const customHeaders = initialConfig.customHeaders || [];

      if (auth === 'access_token' && token.trim()) {
        headers['Authorization'] = `Bearer ${token.trim()}`;
      } else if (auth === 'custom_headers') {
        const validHeaders = customHeaders.filter(h => h.key.trim() && h.value.trim());
        validHeaders.forEach(header => {
          headers[header.key.trim()] = header.value.trim();
        });
      }

      // Prepare request body using initialConfig values
      const requestBody = {
        serverLabel: initialConfig.label?.trim() || '',
        serverUrl: initialConfig.url?.trim() || '',
        headers: auth === 'none' ? {} : headers
      };

      // Make API call
      const data: MCPTool[] = await apiClient.jsonRequest<MCPTool[]>('/v1/dashboard/mcp/list_actions', {
        method: 'POST',
        body: JSON.stringify(requestBody)
      });
      
      // Set tools and preserve previously selected tools
      setTools(data);
      
      // Only select tools that still exist in the new API response
      if (initialConfig.selectedTools) {
        const availableToolNames = data.map(tool => tool.name);
        const validSelectedTools = initialConfig.selectedTools.filter(toolName => 
          availableToolNames.includes(toolName)
        );
        setSelectedTools(validSelectedTools);
      }
      
      setIsConnecting(false);

    } catch (error) {
      console.error('Failed to fetch tools for editing:', error);
      setIsConnecting(false);
      // You might want to show an error message here
    }
  };

  const addCustomHeader = () => {
    setCustomHeaders([...customHeaders, { key: '', value: '' }]);
  };

  const removeCustomHeader = (index: number) => {
    setCustomHeaders(customHeaders.filter((_, i) => i !== index));
  };

  const updateCustomHeader = (index: number, field: 'key' | 'value', value: string) => {
    const updated = [...customHeaders];
    updated[index][field] = value;
    setCustomHeaders(updated);
  };

  const handleBack = () => {
    if (currentView === 'tool-detail') {
      setCurrentView('connected');
      setSelectedTool(null);
    } else if (currentView === 'connected') {
      setCurrentView('connect');
      setTools([]);
      setSelectedTools([]);
    }
  };

  const handleEditConnection = () => {
    setCurrentView('connect');
  };

  const handleToolToggle = (toolName: string) => {
    setSelectedTools(prev => 
      prev.includes(toolName)
        ? prev.filter(name => name !== toolName)
        : [...prev, toolName]
    );
  };

  const handleToolClick = (tool: MCPTool) => {
    setSelectedTool(tool);
    setCurrentView('tool-detail');
  };

  const handleAddToTools = () => {
    const config = {
      url,
      label,
      authentication,
      accessToken: authentication === 'access_token' ? accessToken : undefined,
      customHeaders: authentication === 'custom_headers' ? customHeaders.filter(h => h.key && h.value) : undefined,
      selectedTools
    };
    onConnect(config);
    onOpenChange(false);
    resetForm();
  };

  const resetForm = () => {
    setUrl('');
    setLabel('');
    setAuthentication('access_token');
    setAccessToken('');
    setShowToken(false);
    setCustomHeaders([{ key: '', value: '' }]);
    setIsConnecting(false);
    setCurrentView('connect');
    setTools([]);
    setSelectedTools([]);
    setSelectedTool(null);
    setIsEditing(false);
  };

  // Reset form when modal closes
  React.useEffect(() => {
    if (!open) {
      resetForm();
    }
  }, [open]);

  // Populate form with initial config when editing
  React.useEffect(() => {
    if (initialConfig && open) {
      setUrl(initialConfig.url || '');
      setLabel(initialConfig.label || '');
      setAuthentication(initialConfig.authentication || 'access_token');
      setAccessToken(initialConfig.accessToken || '');
      setSelectedTools(initialConfig.selectedTools || []);
      setIsEditing(true); // Set editing mode
      
      if (initialConfig.customHeaders) {
        setCustomHeaders(initialConfig.customHeaders);
      }
      
      // When editing, go directly to connected view and fetch tools
      if (initialConfig.selectedTools && initialConfig.selectedTools.length > 0) {
        setCurrentView('connected');
        fetchToolsForEditing();
      }
    } else if (open) {
      // If no initialConfig, this is a new configuration
      setIsEditing(false);
    }
  }, [initialConfig, open]);

  // If readOnly and preloadedTools provided, preselect all tools
  useEffect(()=>{
    if(readOnly && preloadedTools.length>0){
      setSelectedTools(preloadedTools.map(t=>t.name));
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  },[]);

  const getAuthenticationDisplayValue = () => {
    switch (authentication) {
      case 'none': return 'None';
      case 'access_token': return 'Access token / API key';
      case 'custom_headers': return 'Custom headers';
      default: return 'None';
    }
  };

  const renderParameterType = (param: any, paramName: string, isRequired: boolean = false): React.ReactNode => {
    return (
      <div key={paramName} className="mb-4">
        <div className="flex items-center space-x-2 mb-1">
          <span className="text-sm font-medium">{paramName}</span>
          <span className="text-xs text-muted-foreground/80">{param.type}</span>
          {isRequired && (
            <span className="text-xs text-positive-trend">Required</span>
          )}
        </div>
        {param.description && (
          <p className="text-xs text-muted-foreground leading-relaxed">
            {param.description}
          </p>
        )}
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent 
        className={`w-full h-screen p-0 md:w-[90vw] md:max-h-[90vh] md:rounded-lg ${currentView === 'tool-detail' ? 'md:max-w-2xl [&>button]:hidden' : 'md:max-w-md'}`}
      >
        <div className="flex flex-col h-full">
          <DialogHeader className="p-6 pb-4 text-center">
            <div className="flex flex-col items-center space-y-4">
              <div className="w-16 h-16 bg-muted/50 rounded-lg flex items-center justify-center relative">
                {isConnecting && (
                  <>
                    <div className="absolute inset-0 rounded-lg border-2 border-positive-trend/30 animate-pulse"></div>
                    <div className="absolute inset-0 rounded-lg border-t-2 border-positive-trend animate-spin"></div>
                  </>
                )}
                {currentView === 'connected' || currentView === 'tool-detail' ? (
                  <Check className="h-8 w-8 text-positive-trend" />
                ) : (
                  <MCP className="h-8 w-8 text-foreground" />
                )}
              </div>
              <DialogTitle className="text-xl font-semibold">
                {currentView === 'tool-detail' && selectedTool ? selectedTool.name :
                 currentView === 'connected' ? label :
                 isEditing ? 'Edit MCP Server' : 'Connect to MCP Server'}
              </DialogTitle>
            </div>
          </DialogHeader>

          <div className="flex-1 p-6 pt-0">
            {currentView === 'connect' && (
              <div className="space-y-4">
                {/* URL Field */}
                <div className="space-y-2 relative">
                  <Label htmlFor="url" className="text-sm font-medium">
                    URL
                  </Label>
                  <Input
                    id="url"
                    onFocus={()=> setShowUrlSuggestions(true)}
                    onChange={(e) => {
                      setUrl(e.target.value);
                      setShowUrlSuggestions(true);
                    }}
                    placeholder="https://mcp.example.com"
                    disabled={isConnecting}
                    className="bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
                    style={{ 
                      boxShadow: 'none !important',
                      outline: 'none !important'
                    }}
                  />
                   {showUrlSuggestions && filteredUrlSuggestions.length>0 && (
                     <div className="absolute z-50 mt-1 left-0 max-h-48 overflow-y-auto bg-background border border-border rounded-md shadow-lg w-auto min-w-full max-w-xs" style={{scrollbarWidth:'thin'}}>
                       {filteredUrlSuggestions.map(sug => (
                         <button
                           key={sug.url}
                           type="button"
                           className="w-full text-left px-3 py-2 text-sm hover:bg-positive-trend/10 focus:bg-positive-trend/10"
                           onClick={()=>{
                             setUrl(sug.url);
                             setLabel(sug.label);
                             setAuthentication('none');
                             setShowUrlSuggestions(false);
                           }}
                         >
                           <span className="font-medium">{sug.label}</span>
                           <br/>
                           <span className="text-xs text-muted-foreground">{sug.url}</span>
                         </button>
                       ))}
                     </div>
                   )}
                </div>

                {/* Label Field */}
                <div className="space-y-2">
                  <Label htmlFor="label" className="text-sm font-medium">
                    Label
                  </Label>
                  <Input
                    id="label"
                    value={label}
                    onChange={(e) => {
                      const formatted = e.target.value.replace(/[_\s]+/g, '-');
                      setLabel(formatted);
                    }}
                    placeholder="my-mcp-server"
                    disabled={isConnecting}
                    className="bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
                    style={{ 
                      boxShadow: 'none !important',
                      outline: 'none !important'
                    }}
                  />
                </div>

                {/* Authentication Field */}
                <div className="space-y-2">
                  <Label className="text-sm font-medium">
                    Authentication
                  </Label>
                  <Select 
                    value={authentication} 
                    onValueChange={(value: 'none' | 'access_token' | 'custom_headers') => setAuthentication(value)}
                    disabled={isConnecting}
                  >
                    <SelectTrigger className="bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="none">None</SelectItem>
                      <SelectItem value="access_token">Access token / API key</SelectItem>
                      <SelectItem value="custom_headers">Custom headers</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                {/* Access Token Field (conditional) */}
                {authentication === 'access_token' && (
                  <div className="space-y-2">
                    <div className="relative">
                      <Input
                        type={showToken ? 'text' : 'password'}
                        value={accessToken}
                        onChange={(e) => setAccessToken(e.target.value)}
                        placeholder="Add your access token"
                        autoComplete="off"
                        disabled={isConnecting}
                        className="bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200 pr-10"
                        style={{ 
                          boxShadow: 'none !important',
                          outline: 'none !important'
                        }}
                      />
                      <Button
                        variant="ghost"
                        size="sm"
                        className="absolute right-2 top-1/2 transform -translate-y-1/2 h-6 w-6 p-0 hover:bg-muted/50"
                        onClick={() => setShowToken(!showToken)}
                        disabled={isConnecting}
                      >
                        {showToken ? (
                          <EyeOff className="h-4 w-4 text-muted-foreground" />
                        ) : (
                          <Eye className="h-4 w-4 text-muted-foreground" />
                        )}
                      </Button>
                    </div>
                  </div>
                )}

                {/* Custom Headers Field (conditional) */}
                {authentication === 'custom_headers' && (
                  <div className="space-y-3">
                    {customHeaders.map((header, index) => (
                      <div key={index} className="flex items-center space-x-2">
                        <Input
                          value={header.key}
                          onChange={(e) => updateCustomHeader(index, 'key', e.target.value)}
                          placeholder="header"
                          disabled={isConnecting}
                          className="flex-1 bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
                          style={{ 
                            boxShadow: 'none !important',
                            outline: 'none !important'
                          }}
                        />
                        <span className="text-muted-foreground">:</span>
                        <Input
                          value={header.value}
                          onChange={(e) => updateCustomHeader(index, 'value', e.target.value)}
                          placeholder="value"
                          disabled={isConnecting}
                          className="flex-1 bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
                          style={{ 
                            boxShadow: 'none !important',
                            outline: 'none !important'
                          }}
                        />
                        {customHeaders.length > 1 && (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-8 w-8 p-0 hover:bg-muted/50"
                            onClick={() => removeCustomHeader(index)}
                            disabled={isConnecting}
                          >
                            <X className="h-4 w-4 text-muted-foreground" />
                          </Button>
                        )}
                      </div>
                    ))}
                    
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={addCustomHeader}
                      disabled={isConnecting}
                      className="w-full justify-center hover:bg-muted/50 text-muted-foreground"
                    >
                      <Plus className="h-4 w-4 mr-2" />
                      Header
                    </Button>
                  </div>
                )}
              </div>
            )}

            {currentView === 'connected' && (
              <div className="space-y-4">
                {/* Connection Details */}
                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <Label className="text-sm font-medium">URL</Label>
                    {!readOnly && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={handleEditConnection}
                      className="h-6 w-6 p-0 hover:bg-muted/50"
                    >
                      <Edit className="h-3 w-3 text-muted-foreground" />
                    </Button>) }
                  </div>
                  <div className="text-sm text-muted-foreground bg-muted/30 p-2 rounded">
                    {url}
                  </div>
                </div>

                <div className="space-y-2">
                  <Label className="text-sm font-medium">Authentication</Label>
                  <div className="text-sm text-muted-foreground">
                    {getAuthenticationDisplayValue()}
                  </div>
                </div>

                {/* Tools Section */}
                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <Label className="text-sm font-medium">Tools</Label>
                    {!readOnly && (
                      <div className="flex items-center space-x-3">
                        <Checkbox
                          checked={selectedTools.length === tools.length && tools.length > 0}
                          onCheckedChange={(checked) => {
                            if (checked) {
                              setSelectedTools(tools.map(tool => tool.name));
                            } else {
                              setSelectedTools([]);
                            }
                          }}
                          disabled={isConnecting || tools.length === 0}
                          className="data-[state=checked]:bg-positive-trend data-[state=checked]:border-positive-trend"
                        />
                        <div className="flex items-center space-x-1">
                          <Check className="h-3 w-3 text-positive-trend" />
                          <span className="text-xs text-muted-foreground">
                            {selectedTools.length} selected
                          </span>
                        </div>
                      </div>
                    )}
                  </div>
                  
                  {isConnecting ? (
                    <div className="flex items-center justify-center py-8">
                      <Loader2 className="h-6 w-6 animate-spin text-positive-trend" />
                      <span className="ml-2 text-sm text-muted-foreground">Loading tools...</span>
                    </div>
                  ) : (
                    <div className="space-y-2 max-h-64 overflow-y-auto scrollbar-thin scrollbar-thumb-muted scrollbar-track-transparent pr-2">
                      {tools.map((tool) => (
                        <div key={tool.name} className="flex items-center space-x-3 p-2 hover:bg-muted/30 rounded transition-colors">
                          {!readOnly && (
                          <Checkbox
                            checked={selectedTools.includes(tool.name)}
                            onCheckedChange={() => handleToolToggle(tool.name)}
                            className="data-[state=checked]:bg-positive-trend data-[state=checked]:border-positive-trend"
                          />
                          )}
                          <button
                            onClick={() => handleToolClick(tool)}
                            className="flex-1 text-left flex items-center justify-between hover:text-positive-trend transition-colors"
                          >
                            <span className="text-sm font-normal">{tool.name}</span>
                            <ChevronRight className="h-3 w-3 text-muted-foreground" />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )}

            {currentView === 'tool-detail' && selectedTool && (
              <div className="space-y-4">
                <div className="max-h-[calc(80vh-200px)] overflow-y-auto space-y-4 pr-2">
                  <div>
                    <h3 className="text-lg font-bold mb-2">{selectedTool.name}</h3>
                    <p className="text-sm text-muted-foreground leading-relaxed">
                      {selectedTool.description}
                    </p>
                  </div>
                  
                  {selectedTool.parameters && selectedTool.parameters.properties && (
                    <div>
                      <h4 className="text-base font-semibold mb-3">Parameters</h4>
                      <div className="space-y-1">
                        {Object.entries(selectedTool.parameters.properties).map(([paramName, param]: [string, any]) => 
                          renderParameterType(
                            param, 
                            paramName, 
                            selectedTool.parameters.required?.includes(paramName) || false
                          )
                        )}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="flex items-center justify-between p-6 pt-4 border-t border-border">
            {!(readOnly && currentView === 'connected') && (
            <Button
              variant="ghost"
              onClick={handleBack}
              disabled={isConnecting}
              className="flex items-center space-x-2 hover:bg-muted/50 disabled:opacity-50"
            >
              <ChevronLeft className="h-4 w-4" />
              <span>Back</span>
            </Button>
            )}
            
            {currentView === 'tool-detail' ? (
              <div></div>
            ) : currentView === 'connected' ? (
              readOnly ? <div></div> : (
              <Button
                onClick={handleAddToTools}
                disabled={isConnecting || selectedTools.length === 0}
                className="bg-positive-trend hover:bg-positive-trend/90 text-white flex items-center space-x-2 disabled:opacity-50"
              >
                <span>{isEditing ? 'Update' : 'Add'}</span>
              </Button>
            ) ) : !readOnly && currentView === 'connect' ? (
              <Button
                onClick={handleConnect}
                disabled={
                  isConnecting ||
                  !url.trim() || 
                  !label.trim() || 
                  (authentication === 'access_token' && !accessToken.trim()) ||
                  (authentication === 'custom_headers' && !customHeaders.some(h => h.key.trim() && h.value.trim()))
                }
                className="bg-positive-trend hover:bg-positive-trend/90 text-white flex items-center space-x-2 disabled:opacity-50"
              >
                {isConnecting ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" />
                    <span>Connecting...</span>
                  </>
                ) : (
                  <>
                    <span>⚡</span>
                    <span>Connect</span>
                  </>
                )}
              </Button>
            ) : null}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default MCPModal;