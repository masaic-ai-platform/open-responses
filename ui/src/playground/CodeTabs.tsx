import React, { useState, useEffect } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Copy, Check, Code } from 'lucide-react';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { atomDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { PlaygroundRequest } from './PlaygroundRequest';
import { generateSnippets, type CodeSnippets } from './snippets/generators';

interface CodeTabsProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lastRequest: PlaygroundRequest | null;
  baseUrl: string;
}



const CodeTabs: React.FC<CodeTabsProps> = ({
  open,
  onOpenChange,
  lastRequest,
  baseUrl
}) => {
  const [activeTab, setActiveTab] = useState<'curl' | 'python' | 'node'>('curl');
  const [snippets, setSnippets] = useState<CodeSnippets>({
    curl: '',
    python: '',
    node: ''
  });
  const [copiedStates, setCopiedStates] = useState<Record<string, boolean>>({
    curl: false,
    python: false,
    node: false
  });

  // Generate snippets when modal opens or request changes
  useEffect(() => {
    if (open && lastRequest) {
      try {
        const generatedSnippets = generateSnippets(lastRequest, baseUrl);
        setSnippets(generatedSnippets);
      } catch (error) {
        console.error('Error generating code snippets:', error);
        setSnippets({
          curl: 'Error generating cURL snippet',
          python: 'Error generating Python snippet',
          node: 'Error generating Node.js snippet'
        });
      }
    }
  }, [open, lastRequest, baseUrl]);

  const copyToClipboard = async (text: string, type: 'curl' | 'python' | 'node') => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedStates(prev => ({ ...prev, [type]: true }));
      setTimeout(() => {
        setCopiedStates(prev => ({ ...prev, [type]: false }));
      }, 2000);
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  };

  const getLanguageForSyntaxHighlighter = (tab: string) => {
    switch (tab) {
      case 'curl':
        return 'bash';
      case 'python':
        return 'python';
      case 'node':
        return 'javascript';
      default:
        return 'text';
    }
  };

  const customStyle = {
    background: 'transparent',
    padding: '16px',
    fontSize: '13px',
    fontFamily: '"Geist Mono", Menlo, Consolas, monospace',
    lineHeight: '1.5',
    margin: 0,
    minHeight: '100%'
  };

  // Custom syntax highlighting theme matching app colors
  const customSyntaxStyle = {
    'comment': { color: '#6b7280' },
    'string': { color: '#10b981' }, // positive-trend green
    'number': { color: '#ffffff' },
    'boolean': { color: '#10b981' },
    'keyword': { color: '#10b981' },
    'function': { color: '#ffffff' },
    'operator': { color: '#ffffff' },
    'punctuation': { color: '#d1d5db' },
    'property': { color: '#ffffff' },
    'builtin': { color: '#10b981' },
    'class-name': { color: '#ffffff' },
    'constant': { color: '#10b981' },
    'symbol': { color: '#10b981' },
    'deleted': { color: '#ef4444' },
    'inserted': { color: '#10b981' },
    'entity': { color: '#ffffff' },
    'url': { color: '#10b981' },
    'variable': { color: '#ffffff' },
    'atrule': { color: '#10b981' },
    'attr-value': { color: '#10b981' },
    'attr-name': { color: '#ffffff' },
    'tag': { color: '#10b981' },
    'prolog': { color: '#6b7280' },
    'doctype': { color: '#6b7280' },
    'cdata': { color: '#6b7280' },
    'namespace': { color: '#ffffff' },
    'selector': { color: '#10b981' },
    'important': { color: '#ef4444' },
    'bold': { fontWeight: 'bold' },
    'italic': { fontStyle: 'italic' }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-5xl w-[95vw] h-[90vh] flex flex-col bg-background border border-border shadow-xl p-0">
        {/* Header */}
        <DialogHeader className="shrink-0 px-6 py-3 border-b border-border">
          <div className="flex items-center space-x-3">
            <div className="w-8 h-8 bg-primary/10 rounded-md flex items-center justify-center">
              <Code className="h-4 w-4 text-primary" />
            </div>
            <DialogTitle className="text-lg font-semibold text-foreground">Code Snippets</DialogTitle>
          </div>
        </DialogHeader>

        {/* Code content with IDE styling */}
        <div className="flex-1 overflow-hidden px-6 pb-6 pt-2">
          <div className="h-full bg-card border border-border rounded-lg flex flex-col overflow-hidden shadow-sm">
            {/* IDE header with language selector and copy */}
            <div className="shrink-0 flex items-center justify-between px-4 py-2 bg-muted/50 border-b border-border">
              <Select value={activeTab} onValueChange={(value) => setActiveTab(value as 'curl' | 'python' | 'node')}>
                <SelectTrigger className="w-48 h-7 text-xs bg-background border-border hover:bg-accent focus:ring-1 focus:ring-positive-trend">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent className="bg-background border-border">
                  <SelectItem value="curl" className="text-xs hover:bg-accent focus:bg-positive-trend/10">cURL</SelectItem>
                  <SelectItem value="python" className="text-xs hover:bg-accent focus:bg-positive-trend/10">Python (OpenAI SDK)</SelectItem>
                  <SelectItem value="node" className="text-xs hover:bg-accent focus:bg-positive-trend/10">Node.js (OpenAI SDK)</SelectItem>
                </SelectContent>
              </Select>
              
              <Button
                variant="ghost"
                size="sm"
                onClick={() => copyToClipboard(snippets[activeTab], activeTab)}
                className="h-7 px-2 text-xs text-muted-foreground hover:text-foreground hover:bg-accent focus:bg-positive-trend/10 focus:text-positive-trend"
              >
                {copiedStates[activeTab] ? (
                  <>
                    <Check className="h-3 w-3 mr-1.5 text-positive-trend" />
                    Copied
                  </>
                ) : (
                  <>
                    <Copy className="h-3 w-3 mr-1.5" />
                    Copy
                  </>
                )}
              </Button>
            </div>
            
            {/* Code area */}
            <div className="flex-1 overflow-auto bg-gray-900">
              <SyntaxHighlighter
                language={getLanguageForSyntaxHighlighter(activeTab)}
                style={customSyntaxStyle}
                customStyle={customStyle}
                showLineNumbers={true}
                wrapLines={false}
                wrapLongLines={false}
                PreTag="div"
              >
                {snippets[activeTab]}
              </SyntaxHighlighter>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default CodeTabs; 