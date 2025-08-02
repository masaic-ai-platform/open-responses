import React, { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Code, Copy, Check, X, User, Bot, AlertTriangle, RotateCcw } from 'lucide-react';
import ToolExecutionProgress from './ToolExecutionProgress';

interface ToolExecution {
  serverName: string;
  toolName: string;
  status: 'in_progress' | 'completed';
}

interface ContentBlock {
  type: 'text' | 'tool_progress' | 'inline_loading';
  content?: string;
  toolExecutions?: ToolExecution[];
}

// Simple chat bubble component
const ChatBubble: React.FC<{ 
  children: React.ReactNode; 
  role: 'user' | 'assistant'; 
  isError?: boolean;
  content?: string;
}> = ({ children, role, isError = false, content }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    if (!content) return;
    
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy message:', err);
    }
  };

  return (
    <div
      className={`
        relative group p-4 rounded-lg shadow-sm border transition-all duration-200 break-words overflow-hidden
        ${role === 'user' 
          ? 'bg-card border-border hover:border-positive-trend/50 hover:shadow-md' 
          : 'bg-muted border-border hover:border-positive-trend/50 hover:shadow-md'
        }
        ${isError ? 'border-red-500 bg-red-50 dark:bg-red-950/20 hover:border-red-400' : ''}
        hover:scale-[1.01] hover:ring-1 hover:ring-positive-trend/20
      `}
    >
      {children}
      
      {/* Copy button - only show on hover and when content exists */}
      {content && (
        <button
          onClick={handleCopy}
          className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity duration-200 p-1.5 rounded-md bg-background/80 hover:bg-background border border-border hover:border-positive-trend/50 shadow-sm"
          title="Copy message"
          aria-label="Copy message"
        >
          {copied ? (
            <Check className="w-3 h-3 text-positive-trend" />
          ) : (
            <Copy className="w-3 h-3 text-muted-foreground hover:text-foreground" />
          )}
        </button>
      )}
    </div>
  );
};

// JSON syntax highlighting functions (adapted from JsonSchemaModal)
const highlightJson = (jsonString: string, isPartial: boolean = false) => {
  if (!jsonString.trim()) return null;
  
  if (!isPartial) {
    try {
      const parsed = JSON.parse(jsonString);
      const formatted = JSON.stringify(parsed, null, 2);
      const lines = formatted.split('\n');
      return (
        <pre className="font-mono text-sm leading-relaxed whitespace-pre-wrap">
          {lines.map((line, index) => (
            <div key={index}>{highlightLine(line)}</div>
          ))}
        </pre>
      );
    } catch {
      // Fall through to partial highlighting
    }
  }
  
  // Handle partial or invalid JSON with line-by-line highlighting
  const lines = jsonString.split('\n');
  return (
    <pre className="font-mono text-sm leading-relaxed whitespace-pre-wrap">
      {lines.map((line, index) => (
        <div key={index}>{highlightLine(line)}</div>
      ))}
    </pre>
  );
};

const highlightLine = (line: string) => {
  if (line.includes(':') && line.includes('"')) {
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
    return <span className="text-positive-trend">{line}</span>;
  } else if (line.match(/\b(true|false|null)\b/)) {
    return <span className="text-positive-trend">{line}</span>;
  } else if (line.match(/\b\d+\b/)) {
    return <span className="text-foreground">{line}</span>;
  }
  return <span className="text-muted-foreground">{line}</span>;
};

interface ContentRendererProps {
  content: string;
  formatType?: 'text' | 'json_object' | 'json_schema';
}

const ContentRenderer: React.FC<ContentRendererProps> = ({ content, formatType = 'text' }) => {
  // Simple logic: if formatType is json_object or json_schema, render as JSON
  // Otherwise, render as markdown
  if (formatType === 'json_object' || formatType === 'json_schema') {
    // Try to parse and format JSON
    try {
      const parsed = JSON.parse(content);
      const formatted = JSON.stringify(parsed, null, 2);
      return highlightJson(formatted, false);
    } catch {
      // If parsing fails, it's partial JSON - still highlight as JSON
      return highlightJson(content, true);
    }
  }

  // For text format, render as markdown
  return (
    <div className="prose dark:prose-invert prose-sm max-w-none prose-p:my-2 prose-pre:my-2 prose-ul:my-2 prose-ol:my-2 prose-h1:my-2 prose-h2:my-2 prose-h3:my-2 prose-h4:my-2 prose-h5:my-2 prose-h6:my-2 break-words">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children, ...props }) => (
            <a 
              href={href} 
              target="_blank" 
              rel="noopener noreferrer"
              className="text-positive-trend hover:underline break-all inline-block max-w-full"
              style={{ wordBreak: 'break-all', overflowWrap: 'anywhere' }}
              {...props}
            >
              {children}
            </a>
          ),
          img: ({ src, alt, ...props }) => (
            <img 
              src={src} 
              alt={alt || ''} 
              className="max-w-full h-auto rounded-md my-2"
              loading="lazy"
              {...props}
            />
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
};

interface ChatMessageProps {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  contentBlocks?: ContentBlock[];
  type?: 'text' | 'image';
  timestamp: Date;
  hasThinkTags?: boolean;
  isLoading?: boolean;
  formatType?: 'text' | 'json_object' | 'json_schema';
  // New props for code generation
  apiKey?: string;
  baseUrl?: string;
  modelProvider?: string;
  modelName?: string;
  imageModelProvider?: string;
  imageModelName?: string;
  imageProviderKey?: string;
  selectedVectorStore?: string;
  instructions?: string;
  onRetry?: (errorMessageId: string) => void;
}

const ChatMessage: React.FC<ChatMessageProps> = React.memo(({ 
  id,
  role, 
  content, 
  contentBlocks,
  type = 'text', 
  timestamp,
  hasThinkTags = false,
  formatType = 'text',
  apiKey = '',
  baseUrl = 'http://localhost:8080',
  modelProvider = 'openai',
  modelName = 'gpt-4.1-mini',
  imageModelProvider = 'gemini',
  imageModelName = 'imagen-3.0-generate-002',
  imageProviderKey = '',
  selectedVectorStore = '',
  instructions = '',
  isLoading = false,
  onRetry
}) => {
  const [showCodeModal, setShowCodeModal] = useState(false);
  const [activeTab, setActiveTab] = useState<'curl' | 'python'>('curl');
  const [copiedCurl, setCopiedCurl] = useState(false);
  const [copiedPython, setCopiedPython] = useState(false);

  const parseAssistantContent = (jsonContent: string) => {
    try {
      const parsed = JSON.parse(jsonContent);
      if (parsed.output && parsed.output[0] && parsed.output[0].content && parsed.output[0].content[0] && parsed.output[0].content[0].text) {
        return parsed.output[0].content[0].text;
      }
       if (parsed.error && parsed.error.message) {
        return `Error: ${parsed.error.message}`;
      }
    } catch (error) {
      // It's not a JSON, so it might be a simple string message or an error text
      return jsonContent;
    }
    return jsonContent;
  };

  // Function to mask API keys
  const maskApiKey = (key: string): string => {
    if (!key || key === 'YOUR_API_KEY' || key === 'YOUR_IMAGE_PROVIDER_KEY') {
      return key;
    }
    if (key.length <= 8) {
      return '*'.repeat(key.length);
    }
    return key.substring(0, 4) + '*'.repeat(key.length - 8) + key.substring(key.length - 4);
  };

  // Function to parse content with think tags - redesigned with Geist UI
  const parseContentWithThinkTags = (content: string) => {
    if (!hasThinkTags || !content.includes('<think>')) {
      return <p className="whitespace-pre-wrap leading-relaxed text-foreground break-words">{content}</p>;
    }

    const parts = [];
    let currentIndex = 0;
    let partKey = 0;
    let thinkBlockNumber = 1;

    while (currentIndex < content.length) {
      const thinkStart = content.indexOf('<think>', currentIndex);
      
      if (thinkStart === -1) {
        // No more think tags, add remaining content
        const remainingContent = content.substring(currentIndex);
        if (remainingContent.trim()) {
          parts.push(
            <p key={partKey++} className="whitespace-pre-wrap leading-relaxed text-foreground break-words">
              {remainingContent}
            </p>
          );
        }
        break;
      }

      // Add content before think tag
      if (thinkStart > currentIndex) {
        const beforeThink = content.substring(currentIndex, thinkStart);
        if (beforeThink.trim()) {
          parts.push(
            <p key={partKey++} className="whitespace-pre-wrap leading-relaxed text-foreground break-words">
              {beforeThink}
            </p>
          );
        }
      }

      // Find the closing think tag
      const thinkEnd = content.indexOf('</think>', thinkStart);
      if (thinkEnd === -1) {
        // No closing tag found, treat rest as think content
        const thinkContent = content.substring(thinkStart + 7);
        if (thinkContent.trim()) {
          parts.push(
            <div key={partKey++} className="my-4 p-4 bg-warning/5 dark:bg-warning/10 border-l-4 border-warning rounded-r-lg shadow-xs">
              <div className="flex items-center mb-2">
                <span className="text-xs font-medium text-warning dark:text-warning-light uppercase tracking-wide">
                  🤔 AI Thinking #{thinkBlockNumber}
                </span>
              </div>
              <p className="text-sm text-warning-dark dark:text-warning-light italic whitespace-pre-wrap leading-relaxed break-words">
                {thinkContent}
              </p>
            </div>
          );
        }
        break;
      }

      // Add think content
      const thinkContent = content.substring(thinkStart + 7, thinkEnd);
      if (thinkContent.trim()) {
        parts.push(
          <div key={partKey++} className="my-4 p-4 bg-warning/5 dark:bg-warning/10 border-l-4 border-warning rounded-r-lg shadow-xs">
            <div className="flex items-center mb-2">
              <span className="text-xs font-medium text-warning dark:text-warning-light uppercase tracking-wide">
                🤔 AI Thinking #{thinkBlockNumber}
              </span>
            </div>
            <p className="text-sm text-warning-dark dark:text-warning-light italic whitespace-pre-wrap leading-relaxed break-words">
              {thinkContent}
            </p>
          </div>
        );
        thinkBlockNumber++;
      }

      currentIndex = thinkEnd + 8;
    }

    return <div className="space-y-3">{parts}</div>;
  };

  // Function to check if content looks like a base64 image
  const isImageContent = (content: string): boolean => {
    // Remove any data URL prefix if present
    const cleanContent = content.replace(/^data:image\/[^;]+;base64,/, '').trim();
    
    // Check if it looks like base64 and has image signatures
    const base64Regex = /^[A-Za-z0-9+/]*={0,2}$/;
    const isValidBase64 = base64Regex.test(cleanContent);
    
    // Check for common image format signatures
    const hasImageSignature = 
      cleanContent.startsWith('/9j/') ||     // JPEG
      cleanContent.startsWith('FFD8') ||     // JPEG hex
      cleanContent.startsWith('/9j') ||      // JPEG variant
      cleanContent.startsWith('iVBORw0KGgo') || // PNG
      cleanContent.startsWith('89504E47') ||    // PNG hex
      cleanContent.startsWith('iVBORw') ||      // PNG variant
      cleanContent.startsWith('UklGR') ||       // WebP (RIFF)
      cleanContent.startsWith('UklGRg') ||      // WebP variant
      cleanContent.startsWith('R0lGODlh') ||    // GIF
      cleanContent.startsWith('R0lGODdh') ||    // GIF variant
      cleanContent.startsWith('R0lGOD');        // GIF variant

    // Must be valid base64, have image signature, and be reasonably long
    return isValidBase64 && hasImageSignature && cleanContent.length > 100;
  };

  // Function to validate and clean base64 image data
  const validateAndCleanBase64 = (base64Content: string): { isValid: boolean; cleanBase64: string; issues: string[] } => {
    const issues: string[] = [];
    let cleanBase64 = base64Content.replace(/^data:image\/[^;]+;base64,/, '');
    
    // Remove any whitespace or newlines
    cleanBase64 = cleanBase64.replace(/\s/g, '');
    
    // Check if base64 is properly padded (should be multiple of 4)
    const paddingNeeded = cleanBase64.length % 4;
    if (paddingNeeded > 0) {
      const padding = '='.repeat(4 - paddingNeeded);
      cleanBase64 += padding;
      issues.push(`Added ${4 - paddingNeeded} padding characters`);
    }
    
    // Check if it's valid base64
    const base64Regex = /^[A-Za-z0-9+/]*={0,2}$/;
    const isValidBase64 = base64Regex.test(cleanBase64);
    
    if (!isValidBase64) {
      issues.push('Contains invalid base64 characters');
    }
    
    // Check minimum length (a valid image should be at least a few KB)
    if (cleanBase64.length < 100) {
      issues.push('Base64 data too short for valid image');
    }
    
    // Log for debugging
    console.log('Base64 validation:', {
      originalLength: base64Content.length,
      cleanLength: cleanBase64.length,
      paddingNeeded,
      isValidBase64,
      issues
    });
    
    return {
      isValid: isValidBase64 && issues.length <= 1, // Allow padding issues only
      cleanBase64,
      issues
    };
  };

  // Function to detect image format from base64 data
  const detectImageFormat = (base64Content: string): string => {
    const cleanContent = base64Content.replace(/^data:image\/[^;]+;base64,/, '').trim();
    
    if (cleanContent.startsWith('/9j/') || cleanContent.startsWith('/9j') || cleanContent.startsWith('FFD8')) {
      return 'jpeg';
    } else if (cleanContent.startsWith('iVBORw0KGgo') || cleanContent.startsWith('iVBORw') || cleanContent.startsWith('89504E47')) {
      return 'png';
    } else if (cleanContent.startsWith('UklGR') || cleanContent.startsWith('UklGRg')) {
      return 'webp';
    } else if (cleanContent.startsWith('R0lGODlh') || cleanContent.startsWith('R0lGODdh') || cleanContent.startsWith('R0lGOD')) {
      return 'gif';
    }
    
    return 'png'; // Default to PNG
  };

  const generateCurlCode = () => {
    const requestBody: any = {
      model: `${modelProvider}@${modelName}`,
      input: content,
      store: true,
      tools: [{
        type: "image_generation",
        model: `${imageModelProvider}@${imageModelName}`,
        model_provider_key: imageProviderKey || "YOUR_IMAGE_PROVIDER_KEY"
      }]
    };

    if (selectedVectorStore) {
      requestBody.tools.push({
        type: "file_search",
        vector_store_ids: [selectedVectorStore],
        max_num_results: 5,
        filters: {
          type: "eq",
          key: "language",
          value: "en"
        }
      });
    }

    return `curl --location '${baseUrl}/v1/responses' \\
--header 'Content-Type: application/json' \\
--header 'Authorization: Bearer ${maskApiKey(apiKey || 'YOUR_API_KEY')}' \\
--data '${JSON.stringify(requestBody, null, 2).replace(/'/g, "'\"'\"'")}'`;
  };

  const generatePythonCode = () => {
    const requestBody: any = {
      model: `${modelProvider}@${modelName}`,
      input: content,
      store: true,
      tools: [{
      type: "image_generation",
      model: `${imageModelProvider}@${imageModelName}`,
      model_provider_key: imageProviderKey || "YOUR_IMAGE_PROVIDER_KEY"
      }]
    };

    if (selectedVectorStore) {
      requestBody.tools.push({
        type: "file_search",
        vector_store_ids: [selectedVectorStore],
        max_num_results: 5,
        filters: {
          type: "eq",
          key: "language",
          value: "en"
        }
      });
    }

    return `import requests
import json

url = "${baseUrl}/v1/responses"
headers = {
    "Content-Type": "application/json",
    "Authorization": "Bearer ${maskApiKey(apiKey || 'YOUR_API_KEY')}"
}

data = ${JSON.stringify(requestBody, null, 4)}

response = requests.post(url, headers=headers, json=data)
print(response.json())`;
  };

  const copyToClipboard = async (text: string, type: 'curl' | 'python') => {
    try {
      await navigator.clipboard.writeText(text);
      if (type === 'curl') {
        setCopiedCurl(true);
        setTimeout(() => setCopiedCurl(false), 2000);
      } else {
        setCopiedPython(true);
        setTimeout(() => setCopiedPython(false), 2000);
      }
    } catch (err) {
      console.error('Failed to copy: ', err);
    }
  };

  const renderImage = () => {
    // Check if this is a text message that contains <image> tags
    if (type === 'text' && content.includes('<image>')) {
      const parts = content.split(/<image>|<\/image>/);
      const elements = [];
      
      for (let i = 0; i < parts.length; i++) {
        if (i % 2 === 0) {
          // Text part
          if (parts[i].trim()) {
            elements.push(
              <div key={i} className="mb-4">
                {parseContentWithThinkTags(parts[i])}
              </div>
            );
          }
        } else {
          // Image part
          const imageData = parts[i].trim();
          if (imageData && isImageContent(imageData)) {
            const validation = validateAndCleanBase64(imageData);
            const imageFormat = detectImageFormat(imageData);
            const dataUrl = validation.cleanBase64.startsWith('data:') 
              ? validation.cleanBase64 
              : `data:image/${imageFormat};base64,${validation.cleanBase64}`;
            
            elements.push(
              <div key={i} className="mb-6">
                <div className="relative inline-block bg-muted p-2 rounded-lg shadow-sm">
                  <img 
                    src={dataUrl}
                    alt="Generated"
                    className="max-w-full h-auto rounded-md shadow-sm max-h-[60vh] md:max-h-[512px]"
                    onError={(e) => {
                      console.error('Image failed to load');
                      console.log('Validation issues:', validation.issues);
                      e.currentTarget.style.display = 'none';
                    }}
                  />
                  {validation.issues.length > 0 && (
                    <div className="mt-2 text-xs text-warning dark:text-warning-light">
                      Issues: {validation.issues.join(', ')}
                    </div>
                  )}
                </div>
              </div>
            );
          }
        }
      }
      
      return <div className="space-y-4">{elements}</div>;
    }
    
    // Handle pure image content
    if (type === 'image' || isImageContent(content)) {
    const validation = validateAndCleanBase64(content);
    
    if (!validation.isValid) {
      return (
          <div className="p-4 bg-error/5 dark:bg-error/10 border border-error/20 dark:border-error/30 rounded-lg">
            <div className="flex items-center space-x-2 mb-2">
              <AlertTriangle className="h-4 w-4 text-error" />
              <span className="text-sm font-medium text-error">Image Error</span>
            </div>
            <p className="text-sm text-error-dark dark:text-error-light">Invalid image data detected.</p>
            {validation.issues.length > 0 && (
              <ul className="mt-2 text-xs text-error-dark dark:text-error-light">
                {validation.issues.map((issue, index) => (
                  <li key={index}>• {issue}</li>
                ))}
              </ul>
            )}
        </div>
      );
    }
    
    const imageFormat = detectImageFormat(content);
      const dataUrl = validation.cleanBase64.startsWith('data:') 
        ? validation.cleanBase64 
        : `data:image/${imageFormat};base64,${validation.cleanBase64}`;
    
    return (
        <div className="relative inline-block bg-muted p-2 rounded-lg shadow-sm">
        <img 
            src={dataUrl}
            alt="Generated"
            className="max-w-full h-auto rounded-md shadow-sm max-h-[60vh] md:max-h-[512px]"
          onError={(e) => {
              console.error('Image failed to load');
              console.log('Validation issues:', validation.issues);
              e.currentTarget.style.display = 'none';
            }}
          />
          {validation.issues.length > 0 && (
            <div className="mt-2 text-xs text-warning dark:text-warning-light">
              Issues: {validation.issues.join(', ')}
            </div>
          )}
      </div>
    );
    }

    // Default text rendering
    return parseContentWithThinkTags(content);
  };

  // Render content blocks or fallback to regular content
  const renderContent = () => {
    if (isLoading) {
  return (
              <div className="flex items-center space-x-2">
          <div className="w-2 h-2 bg-foreground rounded-full animate-pulse"></div>
          <div className="w-2 h-2 bg-foreground rounded-full animate-pulse" style={{ animationDelay: '200ms' }}></div>
          <div className="w-2 h-2 bg-foreground rounded-full animate-pulse" style={{ animationDelay: '400ms' }}></div>
        </div>
      );
    }

    // If contentBlocks exist, render them sequentially
    if (contentBlocks && contentBlocks.length > 0) {
      return (
        <div className="space-y-2">
          {contentBlocks.map((block, index) => {
            if (block.type === 'tool_progress' && block.toolExecutions) {
              return (
                <ToolExecutionProgress 
                  key={`tool-${index}`}
                  toolExecutions={block.toolExecutions}
                />
              );
            } else if (block.type === 'text' && block.content) {
              const contentToDisplay = role === 'assistant' ? parseAssistantContent(block.content) : block.content;
              return (
                <div key={`text-${index}`}>
                  <ContentRenderer content={contentToDisplay} formatType={formatType} />
                </div>
              );
            } else if (block.type === 'inline_loading') {
              return (
                <div key={`loading-${index}`} className="flex items-center space-x-2 mt-2">
                  <div className="w-2 h-2 bg-foreground rounded-full animate-pulse"></div>
                  <div className="w-2 h-2 bg-foreground rounded-full animate-pulse" style={{ animationDelay: '200ms' }}></div>
                  <div className="w-2 h-2 bg-foreground rounded-full animate-pulse" style={{ animationDelay: '400ms' }}></div>
                </div>
              );
            }
            return null;
          })}
          </div>
      );
    }

    // Fallback to regular content rendering
    const contentToDisplay = role === 'assistant' ? parseAssistantContent(content) : content;
    return <ContentRenderer content={contentToDisplay} formatType={formatType} />;
  };

  const contentToDisplay = role === 'assistant' ? parseAssistantContent(content) : content;
  const isError = role === 'assistant' && contentToDisplay.startsWith('Error:');

  return (
    <div className={`flex items-start gap-4 mb-4 ${role === 'user' ? 'justify-end' : ''}`}>
      {role === 'assistant' && (
        <div className="flex-shrink-0 w-8 h-8 rounded-full bg-muted flex items-center justify-center">
          {isError ? <AlertTriangle className="w-5 h-5 text-red-500" /> : <Bot className="w-5 h-5 text-foreground" />}
            </div>
      )}
      <div className={`flex-grow max-w-full sm:max-w-[80%] ${role === 'user' ? 'order-1' : ''}`}>
        <div className="flex items-center justify-between mb-1">
          <span className="font-bold text-sm text-foreground">
            {role === 'assistant' ? 'Assistant' : 'User'}
          </span>
          <span className="text-xs text-muted-foreground">
            {timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
          </span>
        </div>
        <ChatBubble role={role} isError={isError} content={content}>
          {renderContent()}
        </ChatBubble>
        {isError && role === 'assistant' && onRetry && (
          <div className="mt-2 flex">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onRetry(id)}
              className="flex items-center space-x-1 text-muted-foreground hover:text-foreground hover:bg-muted/50"
              title="Retry"
            >
              <RotateCcw className="w-4 h-4" />
              <span className="text-xs">Retry</span>
            </Button>
          </div>
        )}
      </div>
      {role === 'user' && (
        <div className="flex-shrink-0 w-8 h-8 rounded-full bg-card flex items-center justify-center">
          <User className="w-5 h-5 text-foreground" />
        </div>
      )}

      {/* Code Generation Modal */}
      {showCodeModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/80 backdrop-blur-sm">
          <Card className="w-full max-w-2xl border-2 border-primary/20 shadow-lg relative">
            <div className="p-6">
              <div className="flex justify-between items-center mb-4">
                <h3 className="text-lg font-semibold text-foreground">Generated Code</h3>
                <Button variant="ghost" size="icon" onClick={() => setShowCodeModal(false)} className="absolute top-4 right-4" aria-label="Close">
                  <X className="w-5 h-5" />
              </Button>
            </div>
              <div className="bg-muted rounded-lg p-1 flex w-full sm:w-auto mb-4">
                <Button 
                  variant={activeTab === 'curl' ? 'default' : 'ghost'} 
                onClick={() => setActiveTab('curl')}
                  className="flex-1"
              >
                cURL
                </Button>
                <Button 
                  variant={activeTab === 'python' ? 'default' : 'ghost'} 
                onClick={() => setActiveTab('python')}
                  className="flex-1"
              >
                Python
                </Button>
            </div>
              <div className="relative bg-background p-4 rounded-md h-80 overflow-auto">
                {activeTab === 'curl' && (
                  <>
                    <pre><code className="text-sm font-mono">{generateCurlCode()}</code></pre>
                    <Button
                      variant="ghost" 
                      size="icon" 
                      onClick={() => copyToClipboard(generateCurlCode(), 'curl')}
                      className="absolute top-2 right-2"
                >
                      {copiedCurl ? <Check className="w-5 h-5 text-positive-trend" /> : <Copy className="w-5 h-5" />}
                    </Button>
                  </>
                )}
                {activeTab === 'python' && (
                  <>
                    <pre><code className="text-sm font-mono">{generatePythonCode()}</code></pre>
                    <Button
                      variant="ghost" 
                      size="icon" 
                      onClick={() => copyToClipboard(generatePythonCode(), 'python')}
                      className="absolute top-2 right-2"
                    >
                      {copiedPython ? <Check className="w-5 h-5 text-positive-trend" /> : <Copy className="w-5 h-5" />}
                    </Button>
                  </>
                )}
              </div>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}, (prevProps, nextProps) => {
  // Only compare props that actually affect the visual rendering
  // Exclude props like 'instructions' that are only used for code generation modal
  return (
    prevProps.id === nextProps.id &&
    prevProps.role === nextProps.role &&
    prevProps.content === nextProps.content &&
    prevProps.contentBlocks === nextProps.contentBlocks &&
    prevProps.type === nextProps.type &&
    prevProps.timestamp === nextProps.timestamp &&
    prevProps.hasThinkTags === nextProps.hasThinkTags &&
    prevProps.formatType === nextProps.formatType &&
    prevProps.isLoading === nextProps.isLoading
  );
});

export default ChatMessage;
