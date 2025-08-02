import React, { useState, useEffect, useRef } from 'react';
import { Loader2, Search, FileSearch, ChevronDown, ChevronRight, Puzzle, Code, Save, Layers } from 'lucide-react';
import { MCP } from '@lobehub/icons';

interface AgenticSearchLog {
  iteration: number;
  query: string;
  reasoning: string;
  citations: string[];
  remaining_iterations: number;
}

interface ToolExecution {
  serverName: string;
  toolName: string;
  status: 'in_progress' | 'completed';
  agenticSearchLogs?: AgenticSearchLog[];
}

interface ToolExecutionProgressProps {
  toolExecutions: ToolExecution[];
}

interface AgenticSearchLogsViewProps {
  logs: AgenticSearchLog[];
}

const AgenticSearchLogsView: React.FC<AgenticSearchLogsViewProps> = ({ logs }) => {
  const logsContainerRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new logs are added
  useEffect(() => {
    if (logsContainerRef.current) {
      const container = logsContainerRef.current;
      // Use setTimeout to ensure DOM is updated before scrolling
      setTimeout(() => {
        container.scrollTop = container.scrollHeight;
      }, 0);
    }
  }, [logs.length]); // Watch logs.length instead of logs array

  return (
    <div className="ml-6 mt-2">
      <div 
        ref={logsContainerRef}
        className="bg-muted/30 border-2 border-positive-trend/30 rounded-md p-3 overflow-y-auto text-xs md:text-sm font-mono max-h-[4.5rem] leading-[1.5rem]"
        style={{ 
          scrollbarWidth: 'thin'
        }}
      >
        {logs.map((log, logIndex) => (
          <div key={logIndex} className={`${logIndex > 0 ? 'border-t border-border/40 pt-1' : ''}`}>
            <div className="text-muted-foreground leading-relaxed">
              <span className="text-positive-trend font-medium">Iteration {log.iteration}:</span>{' '}
              <span className="text-foreground">{log.query}</span>
              {log.citations.length > 0 && (
                <span className="text-muted-foreground/70"> [{log.citations.join(', ')}]</span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

const ToolExecutionProgress: React.FC<ToolExecutionProgressProps> = ({ toolExecutions }) => {
  const [expandedTools, setExpandedTools] = useState<Set<string>>(new Set());

  // Auto-expand agentic search tools that have logs
  useEffect(() => {
    const newExpanded = new Set(expandedTools);
    let hasChanges = false;

    toolExecutions.forEach((tool, toolIndex) => {
      if (tool.serverName === 'agentic_search' && tool.agenticSearchLogs && tool.agenticSearchLogs.length > 0) {
        const toolKey = `agentic_search_${toolIndex}`;
        if (!newExpanded.has(toolKey)) {
          newExpanded.add(toolKey);
          hasChanges = true;
        }
      }
    });

    if (hasChanges) {
      setExpandedTools(newExpanded);
    }
  }, [toolExecutions]);

  // Group tools by server name
  const groupedByServer = toolExecutions.reduce((acc, tool) => {
    if (!acc[tool.serverName]) {
      acc[tool.serverName] = [];
    }
    acc[tool.serverName].push(tool);
    return acc;
  }, {} as Record<string, ToolExecution[]>);

  const serverNames = Object.keys(groupedByServer);

  if (serverNames.length === 0) {
    return null;
  }

  const toggleToolExpansion = (toolKey: string) => {
    const newExpanded = new Set(expandedTools);
    if (newExpanded.has(toolKey)) {
      newExpanded.delete(toolKey);
    } else {
      newExpanded.add(toolKey);
    }
    setExpandedTools(newExpanded);
  };

  const getServerDisplayName = (serverName: string) => {
    switch (serverName) {
      case 'file_search':
        return 'File Search';
      case 'agentic_search':
        return 'Agentic Search';
      case 'fun_req_gathering_tool':
        return 'Fun Req Assembler';
      case 'fun_def_generation_tool':
        return 'Fun Def Generator';
      case 'mock_fun_save_tool':
        return 'Mock Function Save';
      case 'mock_generation_tool':
        return 'Mock Generator';
      case 'mock_save_tool':
        return 'Mock Save';
      case 'get_weather_by_city':
        return 'get_weather_by_city';
      default:
        return serverName;
    }
  };

  return (
    <div className="mb-4 space-y-3">
      {serverNames.map((serverName, serverIndex) => (
        <div key={serverName} className="relative">
          {/* Vertical connecting line for overall progress (except for last server) */}
          {serverIndex < serverNames.length - 1 && (
            <div className="absolute left-3 top-8 w-0.5 h-full bg-border"></div>
          )}
          
          {/* Server header - not clickable anymore */}
          <div className="flex items-center space-x-2 mb-2">
            <div className="flex items-center justify-center w-6 h-6 rounded-full bg-positive-trend/10 border border-positive-trend/20">
              {serverName === 'file_search' ? (
                <Search className="w-3 h-3 text-positive-trend" />
              ) : serverName === 'agentic_search' ? (
                <FileSearch className="w-3 h-3 text-positive-trend" />
              ) : serverName === 'fun_req_gathering_tool' ? (
                <Puzzle className="w-3 h-3 text-positive-trend" />
              ) : serverName === 'fun_def_generation_tool' ? (
                <Code className="w-3 h-3 text-positive-trend" />
              ) : serverName === 'mock_fun_save_tool' ? (
                <Save className="w-3 h-3 text-positive-trend" />
              ) : serverName === 'mock_generation_tool' ? (
                <Layers className="w-3 h-3 text-positive-trend" />
              ) : serverName === 'mock_save_tool' ? (
                <Save className="w-3 h-3 text-positive-trend" />
              ) : (
                <MCP className="w-3 h-3 text-positive-trend" />
              )}
            </div>
            <span className="text-sm font-medium text-positive-trend">{getServerDisplayName(serverName)}</span>
          </div>
          
          {/* Tools under this server */}
          <div className="ml-8 space-y-2">
            {groupedByServer[serverName].map((tool, toolIndex) => {
              const toolKey = `${serverName}_${toolIndex}`;
              const isExpanded = expandedTools.has(toolKey);
              const hasLogs = tool.agenticSearchLogs && tool.agenticSearchLogs.length > 0;
              
              // Get display text for different tool types
              const getToolDisplayText = (tool: ToolExecution) => {
                if (tool.serverName === 'file_search') {
                  return tool.status === 'in_progress' ? 'Search in progress' : 'Search completed';
                } else if (tool.serverName === 'agentic_search') {
                  return tool.status === 'in_progress' ? 'Agentic Search in progress' : 'Agentic Search completed';
                } else if (tool.serverName === 'fun_req_gathering_tool') {
                  return tool.status === 'in_progress' ? 'Understanding requirement' : 'Completed';
                } else if (tool.serverName === 'fun_def_generation_tool') {
                  return tool.status === 'in_progress' ? 'Function definition generating' : 'Function definition generated';
                } else if (tool.serverName === 'mock_fun_save_tool') {
                  return tool.status === 'in_progress' ? 'Saving mock function' : 'Mock function saved';
                } else if (tool.serverName === 'mock_generation_tool') {
                  return tool.status === 'in_progress' ? 'Understanding mock requirements' : 'Completed';
                } else if (tool.serverName === 'mock_save_tool') {
                  return tool.status === 'in_progress' ? 'Saving mock' : 'Mock saved';
                } else if (tool.serverName === 'get_weather_by_city') {
                  return tool.status === 'in_progress' ? 'Tool called' : 'Tool call completed';
                }
                return tool.toolName;
              };

              return (
                <div key={toolKey} className="space-y-2">
                  {/* Main tool status - clickable only for agentic search with logs */}
                  <div className="flex items-center space-x-2">
                    <div className="flex items-center justify-center w-4 h-4">
                      {tool.status === 'in_progress' ? (
                        <Loader2 className="w-3 h-3 animate-spin text-muted-foreground" />
                      ) : (
                        <div className="w-2 h-2 rounded-full bg-positive-trend/30"></div>
                      )}
                    </div>
                    <span 
                      className={`text-sm text-muted-foreground ${
                        tool.serverName === 'agentic_search' && hasLogs 
                          ? 'cursor-pointer hover:underline' 
                          : ''
                      }`}
                      onClick={
                        tool.serverName === 'agentic_search' && hasLogs 
                          ? () => toggleToolExpansion(toolKey) 
                          : undefined
                      }
                    >
                      {getToolDisplayText(tool)}
                    </span>
                    {tool.serverName === 'agentic_search' && hasLogs && (
                      <div className="flex items-center">
                        {isExpanded ? (
                          <ChevronDown className="w-3 h-3 text-muted-foreground" />
                        ) : (
                          <ChevronRight className="w-3 h-3 text-muted-foreground" />
                        )}
                      </div>
                    )}
                  </div>

                  {/* Agentic search logs (show when expanded and logs exist) */}
                  {tool.serverName === 'agentic_search' && 
                   isExpanded && 
                   hasLogs && (
                    <AgenticSearchLogsView logs={tool.agenticSearchLogs!} />
                  )}
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
};

export default ToolExecutionProgress; 