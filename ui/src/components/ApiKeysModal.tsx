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
import { useToast } from '@/hooks/use-toast';
import { API_URL } from '@/config';
import { apiClient } from '@/lib/api';
import { Key, Loader2, Eye, EyeOff } from 'lucide-react';

const apiUrl = API_URL;

interface ApiKeysModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaveSuccess?: () => void; // Callback when keys are successfully saved
  requiredProvider?: string; // Optional: if opened for a specific provider
}

interface Provider {
  name: string;
  description: string;
  supportedModels: Array<{
    name: string;
    modelSyntax: string;
  }>;
}

interface SavedApiKey {
  name: string;
  apiKey: string;
}

const ApiKeysModal: React.FC<ApiKeysModalProps> = ({
  open,
  onOpenChange,
  onSaveSuccess,
  requiredProvider
}) => {
  const [providers, setProviders] = useState<Provider[]>([]);
  const [apiKeys, setApiKeys] = useState<Record<string, string>>({});
  const [showKeys, setShowKeys] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const { toast } = useToast();

  // Fetch providers from API
  const fetchProviders = async () => {
    setLoading(true);
    try {
      const data: Provider[] = await apiClient.jsonRequest<Provider[]>('/v1/dashboard/models');
      setProviders(data);
    } catch (error) {
      console.error('Error fetching providers:', error);
      
      // Check if it's an authentication error
      if (error instanceof Error && error.message === 'Authentication required') {
        // This will trigger the login screen via ApiClient's handleAuthError
        return;
      }
      
      toast({
        description: "Failed to load providers. Please try again.",
        variant: "destructive",
        duration: 3000,
      });
    } finally {
      setLoading(false);
    }
  };

  // Load saved API keys from localStorage
  const loadSavedApiKeys = () => {
    try {
      const saved = localStorage.getItem('platform_apiKeys');
      if (saved) {
        const savedKeys: SavedApiKey[] = JSON.parse(saved);
        const keysMap: Record<string, string> = {};
        savedKeys.forEach(item => {
          keysMap[item.name] = item.apiKey;
        });
        setApiKeys(keysMap);
      }
    } catch (error) {
      console.error('Error loading saved API keys:', error);
    }
  };

  // Save API keys to localStorage
  const saveApiKeys = () => {
    setSaving(true);
    try {
      const keysArray: SavedApiKey[] = [];
      Object.entries(apiKeys).forEach(([name, apiKey]) => {
        if (apiKey.trim()) {
          keysArray.push({ name, apiKey: apiKey.trim() });
        }
      });

      localStorage.setItem('platform_apiKeys', JSON.stringify(keysArray));
      
      toast({
        description: "API keys saved successfully!",
        duration: 2000,
      });

      // Call success callback first, then close modal
      onSaveSuccess?.();
      onOpenChange(false);
    } catch (error) {
      console.error('Error saving API keys:', error);
      toast({
        description: "Failed to save API keys. Please try again.",
        variant: "destructive",
        duration: 3000,
      });
    } finally {
      setSaving(false);
    }
  };

  // Initialize data when modal opens
  useEffect(() => {
    if (open) {
      fetchProviders();
      loadSavedApiKeys();
    }
  }, [open]);

  // Handle API key input change
  const handleApiKeyChange = (providerName: string, value: string) => {
    setApiKeys(prev => ({
      ...prev,
      [providerName]: value
    }));
  };

  // Toggle password visibility
  const toggleShowKey = (providerName: string) => {
    setShowKeys(prev => ({
      ...prev,
      [providerName]: !prev[providerName]
    }));
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="w-full h-screen flex flex-col overflow-hidden md:max-w-md md:h-auto md:rounded-lg md:w-[90vw] md:max-h-[80vh]">
        <DialogHeader className="pb-4 flex-shrink-0">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 bg-positive-trend/10 rounded-lg flex items-center justify-center">
              <Key className="h-5 w-5 text-positive-trend" />
            </div>
            <div>
              <DialogTitle className="text-lg font-semibold">
                {requiredProvider ? `API Key Required` : 'Manage API Keys'}
              </DialogTitle>
              {requiredProvider && (
                <p className="text-sm text-muted-foreground mt-1">
                  Please provide an API key for {requiredProvider}
                </p>
              )}
            </div>
          </div>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 className="h-6 w-6 animate-spin text-positive-trend" />
            </div>
          ) : providers.length === 0 ? (
            <div className="flex items-center justify-center py-12">
              <p className="text-sm text-muted-foreground">No providers found</p>
            </div>
          ) : (
            <div className="space-y-4">
              {providers
                .filter(provider => !requiredProvider || provider.name === requiredProvider)
                .map((provider) => (
                <div key={provider.name} className="space-y-2">
                  <Label className="text-sm font-medium capitalize">
                    {provider.name}
                  </Label>
                  <div className="relative">
                    <Input
                      autoComplete="new-password"
                      autoCorrect="off"
                      autoCapitalize="off"
                      spellCheck="false"
                      type={showKeys[provider.name] ? 'text' : 'password'}
                      value={apiKeys[provider.name] || ''}
                      onChange={(e) => handleApiKeyChange(provider.name, e.target.value)}
                      placeholder={`Enter ${provider.name} API key (optional)`}
                      className="pr-10 focus:outline-none focus:border-positive-trend focus:ring-0 focus-visible:ring-0 focus-visible:ring-offset-0"
                    />
                    <Button
                      variant="ghost"
                      size="sm"
                      className="absolute right-2 top-1/2 transform -translate-y-1/2 h-6 w-6 p-0 hover:bg-muted/50"
                      onClick={() => toggleShowKey(provider.name)}
                      type="button"
                    >
                      {showKeys[provider.name] ? (
                        <EyeOff className="h-3 w-3" />
                      ) : (
                        <Eye className="h-3 w-3" />
                      )}
                    </Button>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {provider.description}
                  </p>
                </div>
              ))}

              {!requiredProvider && (
                <div className="mt-6 p-3 bg-muted/30 rounded-lg">
                  <p className="text-xs text-muted-foreground">
                    API keys are stored locally in your browser and sent to our server with request towards model.
                  </p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-between pt-4 border-t border-border flex-shrink-0">
          <Button
            variant="ghost"
            onClick={() => onOpenChange(false)}
            disabled={saving}
          >
            {requiredProvider ? 'Skip' : 'Cancel'}
          </Button>
          <Button
            onClick={saveApiKeys}
            disabled={saving || loading}
            className="bg-positive-trend hover:bg-positive-trend/90 text-white"
          >
            {saving ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin mr-2" />
                Saving...
              </>
            ) : (
              'Save API Keys'
            )}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default ApiKeysModal; 