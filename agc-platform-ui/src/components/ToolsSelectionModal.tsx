import React, { useState } from 'react';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { Button } from '@/components/ui/button';
import { 
  Code, 
  Search, 
  FileSearch,
  Image,
  Brain,
  Puzzle,
  Save,
  Layers
} from 'lucide-react';
import { MCP } from '@lobehub/icons';
import { usePlatformInfo } from '@/contexts/PlatformContext';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import FunctionModal from './FunctionModal';
import MCPModal from './MCPModal';
import FileSearchModal from './FileSearchModal';
import AgenticFileSearchModal from './AgenticFileSearchModal';

interface Tool {
  id: string;
  name: string;
  icon: React.ComponentType<{ className?: string }>;
  functionDefinition?: string; // For function tools
  mcpConfig?: any; // For MCP server tools
  fileSearchConfig?: { selectedFiles: string[]; selectedVectorStores: string[]; vectorStoreNames: string[] }; // For file search tools
  agenticFileSearchConfig?: { selectedFiles: string[]; selectedVectorStores: string[]; vectorStoreNames: string[]; iterations: number; maxResults: number }; // For agentic file search tools
}

interface ToolsSelectionModalProps {
  selectedTools: Tool[];
  onToolSelect: (tool: Tool) => void;
  editingFunction?: Tool | null;
  onEditingFunctionChange?: (editingFunction: Tool | null) => void;
  editingMCP?: Tool | null;
  onEditingMCPChange?: (editingMCP: Tool | null) => void;
  editingFileSearch?: Tool | null;
  onEditingFileSearchChange?: (editingFileSearch: Tool | null) => void;
  editingAgenticFileSearch?: Tool | null;
  onEditingAgenticFileSearchChange?: (editingAgenticFileSearch: Tool | null) => void;
  getMCPToolByLabel?: (label: string) => any;
  children?: React.ReactNode;
}

const availableTools: Tool[] = [
  { id: 'mcp_server', name: 'MCP Server', icon: MCP },
  { id: 'file_search', name: 'File Search', icon: Search },
  { id: 'agentic_file_search', name: 'Agentic File Search', icon: FileSearch },
  { id: 'function', name: 'Function', icon: Code },
  { id: 'image_generation', name: 'Image Generation', icon: Image },
  { id: 'think', name: 'Think', icon: Brain }
];

const ToolsSelectionModal: React.FC<ToolsSelectionModalProps> = ({
  selectedTools,
  onToolSelect,
  editingFunction,
  onEditingFunctionChange,
  editingMCP,
  onEditingMCPChange,
  editingFileSearch,
  onEditingFileSearchChange,
  editingAgenticFileSearch,
  onEditingAgenticFileSearchChange,
  getMCPToolByLabel,
  children
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [functionModalOpen, setFunctionModalOpen] = useState(false);
  const [functionDefinition, setFunctionDefinition] = useState('');
  const [mcpModalOpen, setMcpModalOpen] = useState(false);
  const [fileSearchModalOpen, setFileSearchModalOpen] = useState(false);
  const [agenticFileSearchModalOpen, setAgenticFileSearchModalOpen] = useState(false);
  
  const { platformInfo } = usePlatformInfo();
  const isVectorStoreEnabled = platformInfo?.vectorStoreInfo?.isEnabled ?? true;

  // Handle editing existing function
  React.useEffect(() => {
    if (editingFunction && editingFunction.id === 'function') {
      setFunctionDefinition(editingFunction.functionDefinition || '');
      setFunctionModalOpen(true);
    }
  }, [editingFunction]);

  // Handle editing existing MCP server
  React.useEffect(() => {
    if (editingMCP && editingMCP.id === 'mcp_server') {
      setMcpModalOpen(true);
    }
  }, [editingMCP]);

  // Handle editing existing file search
  React.useEffect(() => {
    if (editingFileSearch && editingFileSearch.id === 'file_search') {
      setFileSearchModalOpen(true);
    }
  }, [editingFileSearch]);

  // Handle editing existing agentic file search
  React.useEffect(() => {
    if (editingAgenticFileSearch && editingAgenticFileSearch.id === 'agentic_file_search') {
      setAgenticFileSearchModalOpen(true);
    }
  }, [editingAgenticFileSearch]);

  const handleToolSelect = (tool: Tool) => {
    if (tool.id === 'function') {
      setIsOpen(false);
      setFunctionModalOpen(true);
    } else if (tool.id === 'mcp_server') {
      setIsOpen(false);
      setMcpModalOpen(true);
    } else if (tool.id === 'file_search') {
      setIsOpen(false);
      setFileSearchModalOpen(true);
    } else if (tool.id === 'agentic_file_search') {
      setIsOpen(false);
      setAgenticFileSearchModalOpen(true);
    } else {
      onToolSelect(tool);
      setIsOpen(false);
    }
  };

  const handleFunctionSave = () => {
    // Get function name from definition for better display
    let functionName = 'Function';
    try {
      const parsed = JSON.parse(functionDefinition);
      functionName = parsed.name || 'Function';
    } catch {
      // Keep default name if parsing fails
    }

    // Create function tool with definition
    const functionTool: Tool = {
      id: 'function',
      name: functionName,
      icon: Code,
      functionDefinition: functionDefinition
    };

    // If editing, remove the old function first
    if (editingFunction && onEditingFunctionChange) {
      // The parent component will handle removing the old function and adding the new one
      onEditingFunctionChange(null);
    }

    onToolSelect(functionTool);
    setFunctionModalOpen(false);
    setFunctionDefinition('');
    
    // Clear editing state
    if (onEditingFunctionChange) {
      onEditingFunctionChange(null);
    }
  };

  const handleMCPConnect = (config: any) => {
    // Create MCP server tool with config
    const mcpTool: Tool = {
      id: 'mcp_server',
      name: config.label || 'MCP Server',
      icon: MCP,
      mcpConfig: config // Store the full config including selectedTools
    };

    // If editing, remove the old MCP server first
    if (editingMCP && onEditingMCPChange) {
      // The parent component will handle removing the old MCP server and adding the new one
      onEditingMCPChange(null);
    }

    onToolSelect(mcpTool);
    setMcpModalOpen(false);
    
    // Clear editing state
    if (onEditingMCPChange) {
      onEditingMCPChange(null);
    }
  };

  const handleFileSearchSave = (config: { selectedFiles: string[]; selectedVectorStores: string[]; vectorStoreNames: string[] }) => {
    // Create file search tool with config
    const displayName = config.vectorStoreNames.length > 0 ? config.vectorStoreNames.join(', ') : 'File Search';
    const fileSearchTool: Tool = {
      id: 'file_search',
      name: displayName,
      icon: Search,
      fileSearchConfig: config
    };

    // If editing, remove the old file search first
    if (editingFileSearch && onEditingFileSearchChange) {
      // The parent component will handle removing the old file search and adding the new one
      onEditingFileSearchChange(null);
    }

    onToolSelect(fileSearchTool);
    setFileSearchModalOpen(false);
    
    // Clear editing state
    if (onEditingFileSearchChange) {
      onEditingFileSearchChange(null);
    }
  };

  const handleAgenticFileSearchSave = (config: { selectedFiles: string[]; selectedVectorStores: string[]; vectorStoreNames: string[]; iterations: number; maxResults: number }) => {
    // Create agentic file search tool with config
    const displayName = config.vectorStoreNames.length > 0 ? config.vectorStoreNames.join(', ') : 'Agentic File Search';
    const agenticFileSearchTool: Tool = {
      id: 'agentic_file_search',
      name: displayName,
      icon: FileSearch,
      agenticFileSearchConfig: config
    };

    // If editing, remove the old agentic file search first
    if (editingAgenticFileSearch && onEditingAgenticFileSearchChange) {
      // The parent component will handle removing the old agentic file search and adding the new one
      onEditingAgenticFileSearchChange(null);
    }

    onToolSelect(agenticFileSearchTool);
    setAgenticFileSearchModalOpen(false);
    
    // Clear editing state
    if (onEditingAgenticFileSearchChange) {
      onEditingAgenticFileSearchChange(null);
    }
  };

  const isToolSelected = (toolId: string) => {
    // Allow multiple functions and MCP servers to be added
    if (toolId === 'function' || toolId === 'mcp_server') {
      return false;
    }
    return selectedTools.some(tool => tool.id === toolId);
  };

  const getToolDisabledState = (tool: Tool) => {
    // Disable file search tools if vector store is not enabled
    if ((tool.id === 'file_search' || tool.id === 'agentic_file_search') && !isVectorStoreEnabled) {
      return {
        isDisabled: true,
        tooltipMessage: 'To enable, boot up platform with Qdrant vector store'
      };
    }
    
    // Keep existing disabled state for other tools
    if (['function', 'image_generation', 'think'].includes(tool.id)) {
      return {
        isDisabled: true,
        tooltipMessage: 'Coming Soon'
      };
    }

    return {
      isDisabled: false,
      tooltipMessage: null
    };
  };

  const renderToolButton = (tool: Tool) => {
    const IconComponent = tool.icon;
    const { isDisabled, tooltipMessage } = getToolDisabledState(tool);
    
    const toolButton = (
      <Button
        key={tool.id}
        variant="ghost"
        className={`w-full justify-start h-auto p-3 hover:bg-positive-trend/10 hover:text-positive-trend focus:bg-positive-trend/10 focus:text-positive-trend rounded-md ${
          isDisabled ? 'opacity-50 cursor-not-allowed' : ''
        }`}
        onClick={() => !isDisabled && handleToolSelect(tool)}
        disabled={isDisabled && !tooltipMessage} // Only disable if no tooltip message
      >
        <div className="flex items-center space-x-3 w-full">
          <IconComponent className="h-4 w-4 shrink-0" />
          <span className="text-sm font-medium">{tool.name}</span>
        </div>
      </Button>
    );

    // Show tooltip if there's a specific tooltip message, regardless of disabled state
    if (tooltipMessage) {
      return (
        <TooltipProvider key={tool.id}>
          <Tooltip>
            <TooltipTrigger asChild>
              <div 
                className={`${isDisabled ? 'cursor-not-allowed' : ''}`}
                onClick={(e) => {
                  if (isDisabled) {
                    e.preventDefault();
                    e.stopPropagation();
                  } else {
                    handleToolSelect(tool);
                  }
                }}
              >
                <Button
                  variant="ghost"
                  className={`w-full justify-start h-auto p-3 hover:bg-positive-trend/10 hover:text-positive-trend focus:bg-positive-trend/10 focus:text-positive-trend rounded-md ${
                    isDisabled ? 'opacity-50 cursor-not-allowed pointer-events-none' : ''
                  }`}
                  disabled={false} // Never disable to allow hover events
                >
                  <div className="flex items-center space-x-3 w-full">
                    <IconComponent className="h-4 w-4 shrink-0" />
                    <span className="text-sm font-medium">{tool.name}</span>
                  </div>
                </Button>
              </div>
            </TooltipTrigger>
            <TooltipContent>
              <p>{tooltipMessage}</p>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      );
    }

    return toolButton;
  };

  return (
    <>
      <Popover open={isOpen} onOpenChange={setIsOpen}>
        <PopoverTrigger asChild>
          {children || (
            <Button
              variant="ghost"
              size="sm"
              className="h-6 text-xs text-muted-foreground hover:bg-muted/50"
            >
              +
            </Button>
          )}
        </PopoverTrigger>
        <PopoverContent 
          className="w-56 p-2 border border-border/50 shadow-lg"
          style={{
            backgroundColor: 'rgba(0, 0, 0, 0.1)',
            backdropFilter: 'blur(12px)',
            WebkitBackdropFilter: 'blur(12px)',
            border: '1px solid rgba(255, 255, 255, 0.1)'
          }}
          side="right"
          align="start"
        >
          <div className="space-y-1">
            {availableTools.map((tool) => renderToolButton(tool))}
          </div>
        </PopoverContent>
      </Popover>
      
      <FunctionModal
        open={functionModalOpen}
        onOpenChange={(open) => {
          setFunctionModalOpen(open);
          if (!open) {
            // Clear editing state when modal closes
            if (onEditingFunctionChange) {
              onEditingFunctionChange(null);
            }
            setFunctionDefinition('');
          }
        }}
        functionDefinition={functionDefinition}
        onFunctionDefinitionChange={setFunctionDefinition}
        onSave={handleFunctionSave}
      />
    
      <MCPModal
        open={mcpModalOpen}
        onOpenChange={(open) => {
          setMcpModalOpen(open);
          if (!open) {
            // Clear editing state when modal closes
            if (onEditingMCPChange) {
              onEditingMCPChange(null);
            }
          }
        }}
        onConnect={handleMCPConnect}
        initialConfig={editingMCP?.mcpConfig || (editingMCP?.mcpConfig?.label && getMCPToolByLabel ? getMCPToolByLabel(editingMCP.mcpConfig.label) : undefined)}
      />
    
      <FileSearchModal
        open={fileSearchModalOpen}
        onOpenChange={(open) => {
          setFileSearchModalOpen(open);
          if (!open) {
            // Clear editing state when modal closes
            if (onEditingFileSearchChange) {
              onEditingFileSearchChange(null);
            }
          }
        }}
        onSave={handleFileSearchSave}
        initialVectorStores={editingFileSearch?.fileSearchConfig?.selectedVectorStores}
        initialSelectedFiles={editingFileSearch?.fileSearchConfig?.selectedFiles}
        initialVectorStoreNames={editingFileSearch?.fileSearchConfig?.vectorStoreNames}
      />
    
      <AgenticFileSearchModal
        open={agenticFileSearchModalOpen}
        onOpenChange={(open) => {
          setAgenticFileSearchModalOpen(open);
          if (!open) {
            // Clear editing state when modal closes
            if (onEditingAgenticFileSearchChange) {
              onEditingAgenticFileSearchChange(null);
            }
          }
        }}
        onSave={handleAgenticFileSearchSave}
        initialVectorStores={editingAgenticFileSearch?.agenticFileSearchConfig?.selectedVectorStores}
        initialIterations={editingAgenticFileSearch?.agenticFileSearchConfig?.iterations}
        initialMaxResults={editingAgenticFileSearch?.agenticFileSearchConfig?.maxResults}
        initialSelectedFiles={editingAgenticFileSearch?.agenticFileSearchConfig?.selectedFiles}
        initialVectorStoreNames={editingAgenticFileSearch?.agenticFileSearchConfig?.vectorStoreNames}
      />
    </>
  );
};

export default ToolsSelectionModal; 