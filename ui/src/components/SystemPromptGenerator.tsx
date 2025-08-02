import React, { useState } from 'react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Sparkles } from 'lucide-react';
import { API_URL } from '@/config';
import { usePlatformInfo } from '@/contexts/PlatformContext';

interface SystemPromptGeneratorProps {
  onGenerate: (prompt: string) => void;
  existingPrompt?: string;
  isLoading?: boolean;
  onLoadingChange: (loading: boolean) => void;
  children?: React.ReactNode;
}

const SystemPromptGenerator: React.FC<SystemPromptGeneratorProps> = ({
  onGenerate,
  existingPrompt = '',
  isLoading = false,
  onLoadingChange,
  children
}) => {
  const [generateModalOpen, setGenerateModalOpen] = useState(false);
  const [generatePrompt, setGeneratePrompt] = useState('');
  
  const { platformInfo } = usePlatformInfo();

  const handleGenerate = () => {
    setGenerateModalOpen(true);
  };

  // Helper function to get API key for a provider from localStorage
  const getProviderApiKey = (provider: string): string | null => {
    try {
      const saved = localStorage.getItem('platform_apiKeys');
      if (!saved) return null;
      
      const apiKeys: { name: string; apiKey: string }[] = JSON.parse(saved);
      const providerKey = apiKeys.find(key => key.name === provider);
      return providerKey?.apiKey || null;
    } catch (error) {
      console.error('Error getting API key:', error);
      return null;
    }
  };

  const handleGenerateCreate = async () => {
    if (!generatePrompt.trim()) return;
    
    onLoadingChange(true);
    setGenerateModalOpen(false);
    
    try {
      // Get current model settings from localStorage
      const savedModelProvider = localStorage.getItem('platform_modelProvider') || 'openai';
      const savedModelName = localStorage.getItem('platform_modelName') || 'gpt-4o';
      
      // Prepare headers and payload based on platform settings
      let headers: Record<string, string> = {
        'Content-Type': 'application/json',
      };
      
      let requestBody: any = {
        description: generatePrompt.trim(),
        existingPrompt: existingPrompt
      };

      // Check if modelSettings.settingsType is RUNTIME
      if (platformInfo?.modelSettings?.settingsType === 'RUNTIME') {
        // Add Authorization header with API key of selected model
        const apiKey = getProviderApiKey(savedModelProvider);
        if (apiKey) {
          headers['Authorization'] = `Bearer ${apiKey}`;
        }
        
        // Add modelInfo to payload with modelSyntax
        const modelSyntax = `${savedModelProvider}@${savedModelName}`;
        requestBody.modelInfo = {
          model: modelSyntax
        };
      }

      const response = await fetch(`${API_URL}/v1/dashboard/generate/prompt`, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(requestBody),
      });
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      onGenerate(data.generatedPrompt);
      
    } catch (error) {
      console.error('Error generating prompt:', error);
    } finally {
      onLoadingChange(false);
      setGeneratePrompt('');
    }
  };

  return (
    <Popover open={generateModalOpen} onOpenChange={setGenerateModalOpen}>
      <PopoverTrigger asChild>
        {children || (
          <Button
            variant="outline"
            size="sm"
            onClick={handleGenerate}
            className="flex items-center space-x-2 hover:bg-positive-trend/10 hover:text-positive-trend focus:bg-positive-trend/10 focus:text-positive-trend"
            disabled={isLoading}
          >
            {isLoading ? (
              <div className="w-4 h-4 border-2 border-positive-trend border-t-transparent rounded-full animate-spin" />
            ) : (
              <Sparkles className="h-4 w-4" />
            )}
            <span>Generate</span>
          </Button>
        )}
      </PopoverTrigger>
      <PopoverContent 
        className="w-80 p-4"
        side="right"
        align="start"
      >
        <div className="space-y-4">
          <div>
            <h4 className="text-sm font-medium mb-2">Generate System Prompt</h4>
            <p className="text-xs text-muted-foreground mb-3">
              Describe how you want the AI to behave, and we'll generate a system prompt.
            </p>
          </div>
          
          <div className="space-y-2">
            <Label htmlFor="generate-prompt" className="text-xs font-medium">
              Description
            </Label>
            <Textarea
              id="generate-prompt"
              value={generatePrompt}
              onChange={(e) => setGeneratePrompt(e.target.value)}
              placeholder="Describe how you want the AI to behave, and we'll generate a system prompt."
              className="w-full h-20 text-xs resize-none bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
              style={{ 
                boxShadow: 'none !important',
                outline: 'none !important'
              }}
            />
          </div>
          
          <div className="flex items-center justify-end space-x-2 pt-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setGenerateModalOpen(false)}
              className="text-xs hover:bg-muted/50"
            >
              Cancel
            </Button>
            <Button
              size="sm"
              onClick={handleGenerateCreate}
              className="text-xs bg-positive-trend hover:bg-positive-trend/90 text-white"
              disabled={!generatePrompt.trim() || isLoading}
            >
              {isLoading ? (
                <div className="flex items-center space-x-2">
                  <div className="w-3 h-3 border border-white border-t-transparent rounded-full animate-spin"></div>
                  <span>Creating...</span>
                </div>
              ) : (
                'Create'
              )}
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default SystemPromptGenerator;