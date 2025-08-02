import React, { useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Sparkles } from 'lucide-react';
import { API_URL } from '@/config';
import { usePlatformInfo } from '@/contexts/PlatformContext';

interface JsonSchemaModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  jsonSchema: string;
  onJsonSchemaChange: (schema: string) => void;
  onSave: () => void;
}

const JsonSchemaModal: React.FC<JsonSchemaModalProps> = ({
  open,
  onOpenChange,
  jsonSchema,
  onJsonSchemaChange,
  onSave
}) => {
  const [generateModalOpen, setGenerateModalOpen] = useState(false);
  const [generatePrompt, setGeneratePrompt] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  
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
    
    setIsGenerating(true);
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
        description: generatePrompt.trim()
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

      const response = await fetch(`${API_URL}/v1/dashboard/generate/schema`, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(requestBody),
      });
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      
      // Parse and beautify the generated schema
      const generatedSchema = JSON.parse(data.generatedSchema);
      onJsonSchemaChange(JSON.stringify(generatedSchema, null, 2));
      
    } catch (error) {
      console.error('Error generating schema:', error);
      // Fallback to default schema on error
      const defaultSchema = {
        "name": "response_format",
        "strict": true,
        "schema": {
          "type": "object",
          "properties": {
            "result": {
              "type": "string"
            }
          },
          "additionalProperties": false,
          "required": ["result"]
        }
      };
      onJsonSchemaChange(JSON.stringify(defaultSchema, null, 2));
    } finally {
      setIsGenerating(false);
      setGeneratePrompt('');
    }
  };



  const handleSave = () => {
    onSave();
    onOpenChange(false);
  };

  // JSON syntax highlighting function
  const highlightJson = (jsonString: string) => {
    if (!jsonString.trim()) return null;
    
    try {
      // Parse and re-stringify to ensure valid JSON
      const parsed = JSON.parse(jsonString);
      const formatted = JSON.stringify(parsed, null, 2);
      
      // Split into lines for rendering
      const lines = formatted.split('\n');
      
      return (
        <pre className="font-mono text-sm leading-relaxed whitespace-pre-wrap">
          {lines.map((line, index) => (
            <div key={index}>
              {highlightLine(line)}
            </div>
          ))}
        </pre>
      );
    } catch {
      // Return raw content if parsing fails
      return (
        <pre className="font-mono text-sm leading-relaxed whitespace-pre-wrap text-foreground">
          {jsonString}
        </pre>
      );
    }
  };

  // Highlight individual line
  const highlightLine = (line: string) => {
    const parts = [];
    let currentIndex = 0;
    
    // Regex patterns for different JSON elements
    const patterns = [
      { regex: /"([^"]+)"(\s*:)/g, type: 'key' },        // Object keys
      { regex: /"([^"]+)"(?!\s*:)/g, type: 'string' },   // String values
      { regex: /\b(true|false|null)\b/g, type: 'literal' }, // Literals
      { regex: /\b(-?\d+\.?\d*)\b/g, type: 'number' },   // Numbers
      { regex: /([{}[\],])/g, type: 'punctuation' },     // Punctuation
    ];
    
    // Simple approach: apply colors to the whole line based on content
    if (line.includes(':') && line.includes('"')) {
      // This is likely a key-value pair
      const keyMatch = line.match(/"([^"]+)"(\s*:)/);
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
      // This is likely a string value
      return <span className="text-positive-trend">{line}</span>;
    } else if (line.match(/\b(true|false|null)\b/)) {
      // This contains literals
      return <span className="text-positive-trend">{line}</span>;
    } else if (line.match(/\b\d+\b/)) {
      // This contains numbers
      return <span className="text-foreground">{line}</span>;
    }
    
    return <span className="text-muted-foreground">{line}</span>;
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl w-[90vw] h-[80vh] p-0">
        <div className="flex flex-col h-full">
          <DialogHeader className="p-6 pb-0">
            <DialogTitle className="text-xl font-semibold">Add response format</DialogTitle>
            <DialogDescription className="text-muted-foreground">
              Use a JSON schema to define the structure of the model's response format.{' '}
              <a 
                href="https://platform.openai.com/docs/guides/structured-outputs?api-mode=chat" 
                target="_blank" 
                rel="noopener noreferrer"
                className="text-positive-trend cursor-pointer hover:underline"
              >
                Learn more.
              </a>
            </DialogDescription>
          </DialogHeader>

          <div className="flex-1 p-6 pt-4 flex flex-col min-h-0">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-sm font-medium">Definition</h3>
              <div className="flex items-center space-x-2">
                <Popover open={generateModalOpen} onOpenChange={setGenerateModalOpen}>
                  <PopoverTrigger asChild>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={handleGenerate}
                      className="flex items-center space-x-2 hover:bg-positive-trend/10 hover:text-positive-trend focus:bg-positive-trend/10 focus:text-positive-trend"
                    >
                      <Sparkles className="h-4 w-4" />
                      <span>Generate</span>
                    </Button>
                  </PopoverTrigger>
                          <PopoverContent 
          className="w-80 p-4"
          side="right"
          align="start"
        >
                    <div className="space-y-4">
                      <div>
                        <h4 className="text-sm font-medium mb-2">Generate JSON Schema</h4>
                        <p className="text-xs text-muted-foreground mb-3">
                          Describe how you want the model to respond, and we'll generate a JSON schema.
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
                          placeholder="Describe how you want the model to respond, and we'll generate a JSON schema."
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
                           disabled={!generatePrompt.trim() || isGenerating}
                         >
                           {isGenerating ? (
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

              </div>
            </div>

            <div className="flex-1 min-h-0 relative">
              {isGenerating ? (
                <div className="w-full h-full bg-muted/50 border border-border rounded-md flex items-center justify-center min-h-[400px]">
                  <div className="flex flex-col items-center space-y-3">
                    <div className="w-8 h-8 border-2 border-positive-trend border-t-transparent rounded-full animate-spin"></div>
                    <p className="text-sm text-muted-foreground">Generating schema...</p>
                  </div>
                </div>
              ) : (
                <div className="relative w-full h-full min-h-[400px]">
                  {jsonSchema.trim() ? (
                    // Show syntax highlighted JSON
                    <div className="w-full h-full bg-muted/50 border border-border rounded-md p-4 overflow-auto min-h-[400px]">
                      {highlightJson(jsonSchema)}
                      {/* Hidden textarea for editing */}
                      <Textarea
                        value={jsonSchema}
                        onChange={(e) => onJsonSchemaChange(e.target.value)}
                        className="absolute inset-0 w-full h-full resize-none font-mono text-sm bg-transparent border-none text-transparent caret-white focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200 p-4"
                        style={{ 
                          minHeight: '400px',
                          boxShadow: 'none !important',
                          outline: 'none !important',
                          caretColor: 'var(--positive-trend)'
                        }}
                      />
                    </div>
                  ) : (
                    // Show regular textarea with placeholder
                    <Textarea
                      value={jsonSchema}
                      onChange={(e) => onJsonSchemaChange(e.target.value)}
                      placeholder={`{
  "name": "math_response",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {
      "steps": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "explanation": {
              "type": "string"
            },
            "output": {
              "type": "string"
            }
          },
          "required": [
            "explanation",
            "output"
          ],
          "additionalProperties": false
        }
      },
      "final_answer": {
        "type": "string"
      }
    },
    "additionalProperties": false,
    "required": [
      "steps",
      "final_answer"
    ]
  }
}`}
                      className="w-full h-full resize-none font-mono text-sm bg-muted/50 border border-border focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
                      style={{ 
                        minHeight: '400px',
                        boxShadow: 'none !important',
                        outline: 'none !important'
                      }}
                    />
                  )}
                </div>
              )}
            </div>
          </div>

          <div className="flex items-center justify-end space-x-3 p-6 pt-4 border-t border-border">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              className="hover:bg-muted/50"
            >
              Cancel
            </Button>
            <Button
              onClick={handleSave}
              className="bg-positive-trend hover:bg-positive-trend/90 text-white"
            >
              Save
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default JsonSchemaModal; 