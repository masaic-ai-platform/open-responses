import React, { useState, useEffect } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Settings, Check, AlertCircle } from 'lucide-react';
import { toast } from 'sonner';

interface SettingsModalProps {
  apiKey: string;
  setApiKey: (key: string) => void;
  baseUrl: string;
  setBaseUrl: (url: string) => void;
  modelProvider?: string;
  setModelProvider?: (provider: string) => void;
  modelName?: string;
  setModelName?: (model: string) => void;
  imageModelProvider?: string;
  setImageModelProvider?: (provider: string) => void;
  imageModelName?: string;
  setImageModelName?: (model: string) => void;
  imageProviderKey?: string;
  setImageProviderKey?: (key: string) => void;
  instructions?: string;
  setInstructions?: (instructions: string) => void;
}

const SettingsModal: React.FC<SettingsModalProps> = ({ 
  apiKey, 
  setApiKey, 
  baseUrl, 
  setBaseUrl,
  modelProvider = 'openai',
  setModelProvider = () => {},
  modelName = 'gpt-4.1-mini',
  setModelName = () => {},
  imageModelProvider = 'gemini',
  setImageModelProvider = () => {},
  imageModelName = 'imagen-3.0-generate-002',
  setImageModelName = () => {},
  imageProviderKey = '',
  setImageProviderKey = () => {},
  instructions = 'You help with answer generation using file search when available, and you can generate images when requested. You should use provided documents and search results to give accurate answers, and create images based on file content and user descriptions.',
  setInstructions = () => {}
}) => {
  const [tempApiKey, setTempApiKey] = useState(apiKey);
  const [tempBaseUrl, setTempBaseUrl] = useState(baseUrl);
  const [tempModelProvider, setTempModelProvider] = useState(modelProvider);
  const [tempModelName, setTempModelName] = useState(modelName);
  const [tempImageModelProvider, setTempImageModelProvider] = useState(imageModelProvider);
  const [tempImageModelName, setTempImageModelName] = useState(imageModelName);
  const [tempImageProviderKey, setTempImageProviderKey] = useState(imageProviderKey);
  const [tempInstructions, setTempInstructions] = useState(instructions);
  const [isOpen, setIsOpen] = useState(false);
  const [customChatProvider, setCustomChatProvider] = useState('');
  const [customImageProvider, setCustomImageProvider] = useState('');

  // Sync temporary state with props when they change (after localStorage loading)
  useEffect(() => {
    setTempApiKey(apiKey);
  }, [apiKey]);

  useEffect(() => {
    setTempBaseUrl(baseUrl);
  }, [baseUrl]);

  useEffect(() => {
    setTempModelProvider(modelProvider);
  }, [modelProvider]);

  useEffect(() => {
    setTempModelName(modelName);
  }, [modelName]);

  useEffect(() => {
    setTempImageModelProvider(imageModelProvider);
  }, [imageModelProvider]);

  useEffect(() => {
    setTempImageModelName(imageModelName);
  }, [imageModelName]);

  useEffect(() => {
    setTempImageProviderKey(imageProviderKey);
  }, [imageProviderKey]);

  useEffect(() => {
    setTempInstructions(instructions);
  }, [instructions]);

  // Set custom provider values when a custom provider is detected
  useEffect(() => {
    const knownChatProviders = ['openai', 'claude', 'anthropic', 'groq', 'togetherai', 'gemini', 'google', 'deepseek', 'ollama', 'xai'];
    if (!knownChatProviders.includes(modelProvider)) {
      setCustomChatProvider(modelProvider);
      setTempModelProvider('custom');
    }
  }, [modelProvider]);

  useEffect(() => {
    const knownImageProviders = ['openai', 'togetherai', 'gemini', 'google'];
    if (!knownImageProviders.includes(imageModelProvider)) {
      setCustomImageProvider(imageModelProvider);
      setTempImageModelProvider('custom');
    }
  }, [imageModelProvider]);

  const handleSave = () => {
    const finalChatProvider = tempModelProvider === 'custom' ? customChatProvider : tempModelProvider;
    const finalImageProvider = tempImageModelProvider === 'custom' ? customImageProvider : tempImageModelProvider;
    
    setApiKey(tempApiKey);
    setBaseUrl(tempBaseUrl);
    setModelProvider(finalChatProvider);
    setModelName(tempModelName);
    setImageModelProvider(finalImageProvider);
    setImageModelName(tempImageModelName);
    setImageProviderKey(tempImageProviderKey);
    setInstructions(tempInstructions);
    
          localStorage.setItem('aiPlayground_apiKey', tempApiKey);
      localStorage.setItem('aiPlayground_baseUrl', tempBaseUrl);
              localStorage.setItem('platform_modelProvider', finalChatProvider);
        localStorage.setItem('platform_modelName', tempModelName);
      localStorage.setItem('aiPlayground_imageModelProvider', finalImageProvider);
      localStorage.setItem('aiPlayground_imageModelName', tempImageModelName);
      localStorage.setItem('aiPlayground_imageProviderKey', tempImageProviderKey);
      localStorage.setItem('platform_instructions', tempInstructions);
    
    toast.success('Settings saved successfully!');
    setIsOpen(false);
  };

  const handleCancel = () => {
    setTempApiKey(apiKey);
    setTempBaseUrl(baseUrl);
    setTempModelProvider(modelProvider);
    setTempModelName(modelName);
    setTempImageModelProvider(imageModelProvider);
    setTempImageModelName(imageModelName);
    setTempImageProviderKey(imageProviderKey);
    setTempInstructions(instructions);
    
    // Reset custom provider values
    const knownChatProviders = ['openai', 'claude', 'anthropic', 'groq', 'togetherai', 'gemini', 'google', 'deepseek', 'ollama', 'xai'];
    const knownImageProviders = ['openai', 'togetherai', 'gemini', 'google'];
    
    if (!knownChatProviders.includes(modelProvider)) {
      setCustomChatProvider(modelProvider);
    } else {
      setCustomChatProvider('');
    }
    
    if (!knownImageProviders.includes(imageModelProvider)) {
      setCustomImageProvider(imageModelProvider);
    } else {
      setCustomImageProvider('');
    }
    
    setIsOpen(false);
  };

  return (
    <Dialog open={isOpen} onOpenChange={setIsOpen}>
      <DialogTrigger asChild>
        <Button 
          variant="outline" 
          size="icon" 
          className="h-12 w-12 rounded-lg shadow-md bg-card hover:bg-accent border border-border hover:border-ring transition-all duration-200" 
          title="Settings"
        >
          <Settings className="h-4 w-4 text-muted-foreground" />
        </Button>
      </DialogTrigger>
              <DialogContent className="sm:max-w-2xl max-h-[85vh] flex flex-col bg-card/80 backdrop-blur-md border border-border">
        <DialogHeader className="shrink-0 pb-6 border-b border-border">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-primary/10 dark:bg-primary/20 rounded-lg flex items-center justify-center">
              <Settings className="h-5 w-5 text-primary dark:text-primary-light" />
            </div>
            <div>
              <DialogTitle className="text-xl font-semibold text-foreground">API Settings</DialogTitle>
              <DialogDescription className="text-sm text-muted-foreground mt-1">
                Configure your Masaic Dev Platform API settings
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto pr-2 space-y-8 py-6">
          {/* Base URL Section - Redesigned with Geist UI */}
          <div className="space-y-3">
            <Label htmlFor="baseUrl" className="text-base font-medium text-foreground">Base URL</Label>
            <div className="flex items-center border border-border rounded-lg focus-within:ring-2 focus-within:ring-primary/20 dark:focus-within:ring-primary/30 focus-within:border-primary dark:focus-within:border-primary-light transition-colors duration-200">
              <Input
                id="baseUrl"
                type="text"
                placeholder="http://localhost:8080"
                value={tempBaseUrl}
                onChange={(e) => setTempBaseUrl(e.target.value)}
                className="border-0 rounded-r-none focus-visible:ring-0 focus-visible:ring-offset-0 bg-transparent text-foreground placeholder:text-muted-foreground"
              />
              <div className="bg-muted px-4 py-2 text-sm text-muted-foreground font-mono border-l border-border rounded-r-lg">
                /v1
              </div>
            </div>
            <p className="text-xs text-muted-foreground">The /v1 path will be automatically appended</p>
          </div>
          
          {/* Chat Model Provider Section - Redesigned with Geist UI */}
          <div className="space-y-6 border-t border-border pt-6">
            <div className="flex items-center space-x-3">
              <div className="w-3 h-3 bg-primary rounded-full"></div>
              <h3 className="text-lg font-semibold text-foreground">Chat Model Configuration</h3>
            </div>
            
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-3">
                <Label htmlFor="modelProvider" className="text-sm font-medium text-foreground">Provider</Label>
                <Select value={tempModelProvider} onValueChange={setTempModelProvider}>
                  <SelectTrigger className="bg-card border border-border text-foreground hover:border-primary/50 dark:hover:border-primary/50 transition-colors">
                    <SelectValue placeholder="Select provider" />
                  </SelectTrigger>
                  <SelectContent className="bg-card border border-border">
                    <SelectItem value="openai" className="text-foreground hover:bg-accent">OpenAI</SelectItem>
                    <SelectItem value="claude" className="text-foreground hover:bg-accent">Anthropic (Claude)</SelectItem>
                    <SelectItem value="groq" className="text-foreground hover:bg-accent">Groq</SelectItem>
                    <SelectItem value="gemini" className="text-foreground hover:bg-accent">Google (Gemini)</SelectItem>
                    <SelectItem value="deepseek" className="text-foreground hover:bg-accent">DeepSeek</SelectItem>
                    <SelectItem value="ollama" className="text-foreground hover:bg-accent">Ollama</SelectItem>
                    <SelectItem value="xai" className="text-foreground hover:bg-accent">xAI (Grok)</SelectItem>
                    <SelectItem value="custom" className="text-foreground hover:bg-accent">Custom</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-3">
                <Label htmlFor="modelName" className="text-sm font-medium text-foreground">Model Name</Label>
                <Input
                  id="modelName"
                  type="text"
                  placeholder="gpt-4o, claude-3-5-sonnet-20241022..."
                  value={tempModelName}
                  onChange={(e) => setTempModelName(e.target.value)}
                  className="bg-card border border-border text-foreground placeholder:text-muted-foreground font-mono text-sm focus:border-primary dark:focus:border-primary-light"
                />
              </div>
            </div>

            {tempModelProvider === 'custom' && (
              <div className="space-y-3 p-4 bg-warning/5 dark:bg-warning/10 border border-warning/20 dark:border-warning/30 rounded-lg">
                <Label htmlFor="customChatProvider" className="text-sm font-medium text-foreground">Custom Provider Name</Label>
                <Input
                  id="customChatProvider"
                  type="text"
                  placeholder="your-custom-provider"
                  value={customChatProvider}
                  onChange={(e) => setCustomChatProvider(e.target.value)}
                  className="bg-card border border-border text-foreground placeholder:text-muted-foreground"
                />
              </div>
            )}

            <div className="space-y-3">
              <Label htmlFor="apiKey" className="text-sm font-medium text-foreground">API Key</Label>
              <Input
                id="apiKey"
                type="password"
                placeholder="Enter your API key"
                value={tempApiKey}
                onChange={(e) => setTempApiKey(e.target.value)}
                className="bg-card border border-border text-foreground placeholder:text-muted-foreground font-mono focus:border-primary dark:focus:border-primary-light"
              />
            </div>
          </div>

          {/* Image Model Provider Section - Redesigned with Geist UI */}
          <div className="space-y-6 border-t border-border pt-6">
            <div className="flex items-center space-x-3">
              <div className="w-3 h-3 bg-success rounded-full"></div>
              <h3 className="text-lg font-semibold text-foreground">Image Generation Configuration</h3>
            </div>
            
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div className="space-y-3">
                <Label htmlFor="imageProvider" className="text-sm font-medium text-foreground">Provider</Label>
                <Select value={tempImageModelProvider} onValueChange={setTempImageModelProvider}>
                  <SelectTrigger className="bg-card border border-border text-foreground hover:border-success/50 dark:hover:border-success/50 transition-colors">
                    <SelectValue placeholder="Select provider" />
                  </SelectTrigger>
                  <SelectContent className="bg-card border border-border">
                    <SelectItem value="openai" className="text-foreground hover:bg-accent">OpenAI (DALL-E)</SelectItem>
                    <SelectItem value="gemini" className="text-foreground hover:bg-accent">Google (Imagen)</SelectItem>
                    <SelectItem value="togetherai" className="text-foreground hover:bg-accent">Together AI</SelectItem>
                    <SelectItem value="custom" className="text-foreground hover:bg-accent">Custom</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-3">
                <Label htmlFor="imageModelName" className="text-sm font-medium text-foreground">Model Name</Label>
                <Input
                  id="imageModelName"
                  type="text"
                  placeholder="dall-e-3, imagen-3.0-generate-002..."
                  value={tempImageModelName}
                  onChange={(e) => setTempImageModelName(e.target.value)}
                  className="bg-card border border-border text-foreground placeholder:text-muted-foreground font-mono text-sm focus:border-success dark:focus:border-success-light"
                />
              </div>
            </div>

            {tempImageModelProvider === 'custom' && (
              <div className="space-y-3 p-4 bg-warning/5 dark:bg-warning/10 border border-warning/20 dark:border-warning/30 rounded-lg">
                <Label htmlFor="customImageProvider" className="text-sm font-medium text-foreground">Custom Provider Name</Label>
                <Input
                  id="customImageProvider"
                  type="text"
                  placeholder="your-custom-image-provider"
                  value={customImageProvider}
                  onChange={(e) => setCustomImageProvider(e.target.value)}
                  className="bg-card border border-border text-foreground placeholder:text-muted-foreground"
                />
              </div>
            )}

            <div className="space-y-3">
              <Label htmlFor="imageProviderKey" className="text-sm font-medium text-foreground">Image Provider API Key</Label>
              <Input
                id="imageProviderKey"
                type="password"
                placeholder="Optional: Separate key for image generation"
                value={tempImageProviderKey}
                onChange={(e) => setTempImageProviderKey(e.target.value)}
                className="bg-card border border-border text-foreground placeholder:text-muted-foreground font-mono focus:border-success dark:focus:border-success-light"
              />
              <p className="text-xs text-muted-foreground">Leave empty to use the main API key</p>
            </div>
          </div>

          {/* System Instructions Section - Redesigned with Geist UI */}
          <div className="space-y-6 border-t border-border pt-6">
            <div className="flex items-center space-x-3">
              <div className="w-3 h-3 bg-warning rounded-full"></div>
              <h3 className="text-lg font-semibold text-foreground">System Instructions</h3>
            </div>
            
            <div className="space-y-3">
              <Label htmlFor="instructions" className="text-sm font-medium text-foreground">AI Instructions</Label>
              <Textarea
                id="instructions"
                placeholder="Define how the AI should behave..."
                value={tempInstructions}
                onChange={(e) => setTempInstructions(e.target.value)}
                rows={4}
                className="resize-none bg-card border border-border text-foreground placeholder:text-muted-foreground focus:border-warning dark:focus:border-warning-light"
              />
              <p className="text-xs text-muted-foreground">
                These instructions guide the AI's behavior throughout conversations
              </p>
            </div>
          </div>

          {/* Connection Status - New Geist UI section */}
          <div className="p-4 bg-primary/5 dark:bg-primary/10 border border-primary/20 dark:border-primary/30 rounded-lg">
            <div className="flex items-center space-x-3">
              {tempApiKey && tempBaseUrl ? (
                <>
                  <Check className="h-4 w-4 text-success" />
                  <span className="text-sm font-medium text-success dark:text-success-light">Ready to connect</span>
                </>
              ) : (
                <>
                  <AlertCircle className="h-4 w-4 text-warning" />
                  <span className="text-sm font-medium text-warning dark:text-warning-light">Configuration incomplete</span>
                </>
              )}
            </div>
            <p className="text-xs text-muted-foreground mt-2">
              {!tempApiKey && !tempBaseUrl 
                ? "Please configure both Base URL and API Key"
                : !tempApiKey 
                ? "API Key is required"
                : !tempBaseUrl 
                ? "Base URL is required"
                : "All required settings are configured"
              }
            </p>
          </div>
        </div>

        {/* Footer with Geist UI styling */}
        <div className="shrink-0 flex justify-end gap-3 pt-6 border-t border-border">
          <Button 
            variant="outline" 
            onClick={handleCancel}
            className="border-border text-foreground hover:bg-accent"
          >
            Cancel
          </Button>
          <Button 
            variant="outline"
            onClick={handleSave}
            className="border-border text-foreground hover:bg-accent px-6"
          >
            Save Settings
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default SettingsModal;
