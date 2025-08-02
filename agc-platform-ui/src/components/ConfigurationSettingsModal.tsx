import React, { useState, useEffect } from 'react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Slider } from '@/components/ui/slider';
import { Button } from '@/components/ui/button';
import { Settings, RotateCcw, Edit2 } from 'lucide-react';
import JsonSchemaModal from './JsonSchemaModal';

interface ConfigurationSettingsModalProps {
  // Text format settings
  textFormat: 'text' | 'json_object' | 'json_schema';
  setTextFormat: (format: 'text' | 'json_object' | 'json_schema') => void;
  
  // Tool choice settings
  toolChoice: 'auto' | 'none';
  setToolChoice: (choice: 'auto' | 'none') => void;
  
  // Generation settings
  temperature: number;
  setTemperature: (temp: number) => void;
  maxTokens: number;
  setMaxTokens: (tokens: number) => void;
  topP: number;
  setTopP: (topP: number) => void;
  
  // JSON Schema settings
  jsonSchemaContent: string;
  setJsonSchemaContent: (content: string) => void;
  jsonSchemaName: string | null;
  setJsonSchemaName: (name: string | null) => void;
  
  children?: React.ReactNode;
}

const ConfigurationSettingsModal: React.FC<ConfigurationSettingsModalProps> = ({
  textFormat,
  setTextFormat,
  toolChoice,
  setToolChoice,
  temperature,
  setTemperature,
  maxTokens,
  setMaxTokens,
  topP,
  setTopP,
  children,
  jsonSchemaContent,
  setJsonSchemaContent,
  jsonSchemaName,
  setJsonSchemaName
}) => {
  const [showTempReset, setShowTempReset] = useState(false);
  const [showTokensReset, setShowTokensReset] = useState(false);
  const [showTopPReset, setShowTopPReset] = useState(false);
  const [jsonSchemaModalOpen, setJsonSchemaModalOpen] = useState(false);
  // Remove local state for jsonSchemaContent and jsonSchemaName

  // Clean up any existing localStorage entries for JSON schema (one-time cleanup)
  useEffect(() => {
    localStorage.removeItem('aiPlayground_jsonSchemaContent');
    localStorage.removeItem('aiPlayground_savedSchemaName');
  }, []);

  const resetTemperature = () => {
    setTemperature(1.0);
    setShowTempReset(false);
  };

  const resetMaxTokens = () => {
    setMaxTokens(2048);
    setShowTokensReset(false);
  };

  const resetTopP = () => {
    setTopP(1.0);
    setShowTopPReset(false);
  };

  const handleTextFormatChange = (format: 'text' | 'json_object' | 'json_schema') => {
    if (format === 'json_schema') {
      setJsonSchemaModalOpen(true);
    } else {
      setTextFormat(format);
      // Reset JSON schema state when switching away from json_schema
      setJsonSchemaContent('');
      setJsonSchemaName(null);
    }
  };

  const handleJsonSchemaSave = () => {
    setTextFormat('json_schema');
    
    // Extract schema name from JSON content
    try {
      const parsedSchema = JSON.parse(jsonSchemaContent);
      const schemaName = parsedSchema.name || 'unnamed_schema';
      setJsonSchemaName(schemaName);
    } catch (error) {
      console.error('Error parsing JSON schema:', error);
      setJsonSchemaName('invalid_schema');
    }
  };

  const handleEditSchema = () => {
    setJsonSchemaModalOpen(true);
  };

  const handleJsonSchemaContentChange = (content: string) => {
    setJsonSchemaContent(content);
    
    // Update schema name when content changes
    if (content.trim()) {
      try {
        const parsedSchema = JSON.parse(content);
        const schemaName = parsedSchema.name || 'unnamed_schema';
        setJsonSchemaName(schemaName);
      } catch (error) {
        // If parsing fails, keep the previous name or set to null if no content
        if (!content.trim()) {
          setJsonSchemaName(null);
        }
      }
    } else {
      setJsonSchemaName(null);
    }
  };

  return (
    <Popover>
      <PopoverTrigger asChild>
        {children || (
          <Button
            variant="outline"
            size="icon"
            className="h-8 w-8 hover:bg-muted/50 focus:bg-muted/70 border-border/50 transition-colors duration-200"
            title="Configuration settings"
          >
            <Settings className="h-4 w-4 text-muted-foreground" />
          </Button>
        )}
      </PopoverTrigger>
      <PopoverContent 
        className="w-80 p-4 border border-border/50 rounded-lg shadow-lg focus:outline-none focus:ring-2 focus:ring-positive-trend/20"
        style={{
          backgroundColor: 'rgba(0, 0, 0, 0.1)',
          backdropFilter: 'blur(12px)',
          WebkitBackdropFilter: 'blur(12px)',
          border: '1px solid rgba(255, 255, 255, 0.1)'
        }}
      >
        <div className="space-y-4">
          <div className="pb-2 border-b border-border">
            <h3 className="text-sm font-semibold">Configuration Settings</h3>
          </div>
          
          {/* Text format */}
          <div className="space-y-2">
            <div className="flex items-center justify-between space-x-3">
              <Label className="text-sm font-medium whitespace-nowrap">Text format</Label>
              <Select
                value={textFormat}
                onValueChange={handleTextFormatChange}
              >
                <SelectTrigger className="w-32 focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus-visible:ring-2 focus-visible:ring-positive-trend/20">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="text">text</SelectItem>
                  <SelectItem value="json_object">json_object</SelectItem>
                  <SelectItem value="json_schema">json_schema</SelectItem>
                </SelectContent>
              </Select>
            </div>
            
            {/* Schema name display when json_schema is selected */}
            {textFormat === 'json_schema' && jsonSchemaName && (
              <div className="flex items-center justify-between space-x-2 pl-2">
                <div className="flex items-center space-x-2 text-sm text-muted-foreground">
                  <span className="text-positive-trend">{'{ }'}</span>
                  <span>{jsonSchemaName}</span>
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-4 w-4 p-0 hover:bg-muted/50"
                  onClick={handleEditSchema}
                  title="Edit schema"
                >
                  <Edit2 className="h-3 w-3" />
                </Button>
              </div>
            )}
          </div>

          {/* Tool choice */}
          <div className="flex items-center justify-between space-x-3">
            <Label className="text-sm font-medium whitespace-nowrap">Tool choice</Label>
            <Select
              value={toolChoice}
              onValueChange={(value: 'auto' | 'none') => setToolChoice(value)}
            >
              <SelectTrigger className="w-20 focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus-visible:ring-2 focus-visible:ring-positive-trend/20">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="auto">auto</SelectItem>
                <SelectItem value="none">none</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Temperature */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <Label className="text-sm font-medium">Temperature</Label>
              <div className="flex items-center space-x-2">
                <span className="text-sm text-positive-trend font-medium">{temperature}</span>
                {showTempReset && (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-4 w-4 p-0 hover:bg-muted/50"
                    onClick={resetTemperature}
                    title="Reset to default (1.0)"
                  >
                    <RotateCcw className="h-3 w-3" />
                  </Button>
                )}
              </div>
            </div>
            <div className="px-1">
              <Slider
                value={[temperature]}
                onValueChange={(value) => setTemperature(value[0])}
                onPointerDown={() => setShowTempReset(true)}
                max={2}
                min={0}
                step={0.01}
                className="w-full [&_[role=slider]]:h-3 [&_[role=slider]]:w-3 [&_[role=slider]]:border-2 [&_[role=slider]]:border-positive-trend [&_[role=slider]]:bg-background [&_[role=slider]]:focus:ring-2 [&_[role=slider]]:focus:ring-positive-trend/20 [&_.range]:h-1 [&_.range]:bg-positive-trend"
              />
            </div>
          </div>

          {/* Max tokens */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <Label className="text-sm font-medium">Max tokens</Label>
              <div className="flex items-center space-x-2">
                <span className="text-sm text-positive-trend font-medium">{maxTokens}</span>
                {showTokensReset && (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-4 w-4 p-0 hover:bg-muted/50"
                    onClick={resetMaxTokens}
                    title="Reset to default (2048)"
                  >
                    <RotateCcw className="h-3 w-3" />
                  </Button>
                )}
              </div>
            </div>
            <div className="px-1">
              <Slider
                value={[maxTokens]}
                onValueChange={(value) => setMaxTokens(value[0])}
                onPointerDown={() => setShowTokensReset(true)}
                max={32768}
                min={0}
                step={1}
                className="w-full [&_[role=slider]]:h-3 [&_[role=slider]]:w-3 [&_[role=slider]]:border-2 [&_[role=slider]]:border-positive-trend [&_[role=slider]]:bg-background [&_[role=slider]]:focus:ring-2 [&_[role=slider]]:focus:ring-positive-trend/20 [&_.range]:h-1 [&_.range]:bg-positive-trend"
              />
            </div>
          </div>

          {/* Top P */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <Label className="text-sm font-medium">Top P</Label>
              <div className="flex items-center space-x-2">
                <span className="text-sm text-positive-trend font-medium">{topP}</span>
                {showTopPReset && (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-4 w-4 p-0 hover:bg-muted/50"
                    onClick={resetTopP}
                    title="Reset to default (1.0)"
                  >
                    <RotateCcw className="h-3 w-3" />
                  </Button>
                )}
              </div>
            </div>
            <div className="px-1">
              <Slider
                value={[topP]}
                onValueChange={(value) => setTopP(value[0])}
                onPointerDown={() => setShowTopPReset(true)}
                max={1}
                min={0}
                step={0.01}
                className="w-full [&_[role=slider]]:h-3 [&_[role=slider]]:w-3 [&_[role=slider]]:border-2 [&_[role=slider]]:border-positive-trend [&_[role=slider]]:bg-background [&_[role=slider]]:focus:ring-2 [&_[role=slider]]:focus:ring-positive-trend/20 [&_.range]:h-1 [&_.range]:bg-positive-trend"
              />
            </div>
          </div>
        </div>
      </PopoverContent>
      
      <JsonSchemaModal
        open={jsonSchemaModalOpen}
        onOpenChange={(open) => {
          setJsonSchemaModalOpen(open);
          // Reset state when modal closes if no schema was saved
          if (!open && !jsonSchemaName) {
            setJsonSchemaContent('');
          }
        }}
        jsonSchema={jsonSchemaContent}
        onJsonSchemaChange={handleJsonSchemaContentChange}
        onSave={handleJsonSchemaSave}
      />
    </Popover>
  );
};

export default ConfigurationSettingsModal; 