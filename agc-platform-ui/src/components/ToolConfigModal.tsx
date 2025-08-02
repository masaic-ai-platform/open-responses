import React, { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Badge } from '@/components/ui/badge';
import { Search, Image, X } from 'lucide-react';

interface ToolConfigModalProps {
  children: React.ReactNode;
  selectedVectorStore: string;
  onVectorStoreSelect: (vectorStoreId: string | null) => void;
  imageModelProvider: string;
  setImageModelProvider: (provider: string) => void;
  imageModelName: string;
  setImageModelName: (name: string) => void;
  imageProviderKey: string;
  setImageProviderKey: (key: string) => void;
}

const ToolConfigModal: React.FC<ToolConfigModalProps> = ({
  children,
  selectedVectorStore,
  onVectorStoreSelect,
  imageModelProvider,
  setImageModelProvider,
  imageModelName,
  setImageModelName,
  imageProviderKey,
  setImageProviderKey
}) => {
  const [open, setOpen] = useState(false);
  const [activeToolTab, setActiveToolTab] = useState<'file_search' | 'image_generation'>('file_search');

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

  const handleAddFileSearch = () => {
    // Already handled by the input field
    setOpen(false);
  };

  const handleAddImageGeneration = () => {
    if (imageProviderKey.trim()) {
      setOpen(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        {children}
      </DialogTrigger>
              <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Add Tool</DialogTitle>
        </DialogHeader>
        
        <div className="space-y-4">
          {/* Tool Selection Tabs */}
          <div className="flex space-x-2">
            <Button
              variant={activeToolTab === 'file_search' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setActiveToolTab('file_search')}
              className="flex-1"
            >
              <Search className="h-3 w-3 mr-2" />
              File Search
            </Button>
            <Button
              variant={activeToolTab === 'image_generation' ? 'default' : 'outline'}
              size="sm"
              onClick={() => setActiveToolTab('image_generation')}
              className="flex-1"
            >
              <Image className="h-3 w-3 mr-2" />
              Image Generation
            </Button>
          </div>

          {/* File Search Configuration */}
          {activeToolTab === 'file_search' && (
            <div className="space-y-3">
              <div className="p-3 bg-positive-trend/5 rounded-lg border border-positive-trend/20">
                <div className="flex items-center space-x-2 mb-2">
                  <Search className="h-4 w-4 text-positive-trend" />
                  <span className="text-sm font-medium">File Search Tool</span>
                </div>
                <p className="text-xs text-muted-foreground mb-3">
                  Enable AI to search through your documents and files
                </p>
                <div className="space-y-2">
                  <Label className="text-sm">Vector Store ID</Label>
                  <Input
                    placeholder="Enter vector store ID..."
                    value={selectedVectorStore}
                    onChange={(e) => onVectorStoreSelect(e.target.value || null)}
                    className="text-sm"
                  />
                  <p className="text-xs text-muted-foreground">
                    Provide the ID of your vector store containing indexed documents
                  </p>
                </div>
              </div>
              <Button 
                onClick={handleAddFileSearch} 
                className="w-full"
                disabled={!selectedVectorStore.trim()}
              >
                {selectedVectorStore ? 'Update File Search' : 'Add File Search'}
              </Button>
            </div>
          )}

          {/* Image Generation Configuration */}
          {activeToolTab === 'image_generation' && (
            <div className="space-y-3">
              <div className="p-3 bg-opportunity/5 rounded-lg border border-opportunity/20">
                <div className="flex items-center space-x-2 mb-2">
                  <Image className="h-4 w-4 text-opportunity" />
                  <span className="text-sm font-medium">Image Generation Tool</span>
                </div>
                <p className="text-xs text-muted-foreground mb-3">
                  Generate images from text descriptions
                </p>
                <div className="space-y-3">
                  <div className="space-y-2">
                    <Label className="text-sm">Image Model</Label>
                    <Select value={`${imageModelProvider}@${imageModelName}`} onValueChange={handleImageModelSelect}>
                      <SelectTrigger className="w-full">
                        <SelectValue placeholder="Select image model..." />
                      </SelectTrigger>
                      <SelectContent>
                        {imageModels.map((model) => (
                          <SelectItem key={`${model.provider}@${model.name}`} value={`${model.provider}@${model.name}`}>
                            <div className="flex items-center space-x-2">
                              <Badge variant="outline" className="text-xs">
                                {model.provider}
                              </Badge>
                              <span className="text-sm">{model.name}</span>
                            </div>
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label className="text-sm">API Key</Label>
                    <Input
                      placeholder="Enter image provider API key..."
                      type="password"
                      value={imageProviderKey}
                      onChange={(e) => setImageProviderKey(e.target.value)}
                      className="text-sm"
                    />
                    <p className="text-xs text-muted-foreground">
                      API key for the selected image generation provider
                    </p>
                  </div>
                </div>
              </div>
              <Button 
                onClick={handleAddImageGeneration} 
                className="w-full"
                disabled={!imageProviderKey.trim()}
              >
                {imageProviderKey ? 'Update Image Generation' : 'Add Image Generation'}
              </Button>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default ToolConfigModal; 