import React, { useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogDescription } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { FileText, Database } from 'lucide-react';
import DocumentManager from './DocumentManager';

interface DocumentModalProps {
  apiKey: string;
  baseUrl: string;
  selectedVectorStore?: string;
  onVectorStoreSelect?: (vectorStoreId: string) => void;
  variant?: 'floating' | 'inline';
}

const DocumentModal: React.FC<DocumentModalProps> = ({
  apiKey,
  baseUrl,
  selectedVectorStore,
  onVectorStoreSelect,
  variant = 'floating'
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const isFileSearchEnabled = selectedVectorStore && selectedVectorStore.length > 0;

  return (
    <Dialog open={isOpen} onOpenChange={setIsOpen}>
      <DialogTrigger asChild>
        {variant === 'floating' ? (
          <Button 
            variant="outline" 
            size="icon" 
            className={`h-12 w-12 rounded-lg shadow-md border transition-all duration-200 ${
              isFileSearchEnabled 
                ? 'border-success bg-success/5 dark:bg-success/10 hover:bg-success/10 dark:hover:bg-success/20 border-success/30 dark:border-success/40' 
                : 'border-border bg-card hover:bg-accent hover:border-ring'
            }`} 
            title={isFileSearchEnabled ? "File Search Enabled - Manage Documents" : "Manage Documents"}
          >
            <FileText className={`h-4 w-4 ${
              isFileSearchEnabled 
                ? 'text-success dark:text-success-light' 
                : 'text-muted-foreground'
            }`} />
          </Button>
        ) : (
          <Button 
            variant="outline" 
            size="sm" 
            className={`h-9 px-3 border transition-all duration-200 ${
              isFileSearchEnabled 
                ? 'border-success bg-success/5 dark:bg-success/10 hover:bg-success/10 dark:hover:bg-success/20 border-success/30 dark:border-success/40 text-success dark:text-success-light' 
                : 'border-border bg-card hover:bg-accent hover:border-ring text-muted-foreground'
            }`} 
            title={isFileSearchEnabled ? "File Search Enabled - Manage Documents" : "Manage Documents"}
          >
            <FileText className={`h-3 w-3 mr-2 ${
              isFileSearchEnabled 
                ? 'text-success dark:text-success-light' 
                : 'text-muted-foreground'
            }`} />
            {isFileSearchEnabled ? 'Manage Docs' : 'Select Store'}
          </Button>
        )}
      </DialogTrigger>
              <DialogContent className="sm:max-w-5xl max-h-[85vh] overflow-hidden flex flex-col bg-card/80 backdrop-blur-md border border-border">
        <DialogHeader className="shrink-0 pb-6 border-b border-border">
          <div className="flex items-center space-x-3">
            <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${
              isFileSearchEnabled 
                ? 'bg-success/10 dark:bg-success/20' 
                : 'bg-warning/10 dark:bg-warning/20'
            }`}>
              {isFileSearchEnabled ? (
                <Database className="h-5 w-5 text-success dark:text-success-light" />
              ) : (
                <FileText className="h-5 w-5 text-warning dark:text-warning-light" />
              )}
            </div>
            <div>
              <DialogTitle className="text-xl font-semibold text-foreground">
                Document Management
              </DialogTitle>
              <DialogDescription className="text-sm text-muted-foreground mt-1">
                {isFileSearchEnabled 
                  ? 'File search is active - manage your documents and vector stores'
                  : 'Upload documents and create vector stores for enhanced AI responses'
                }
              </DialogDescription>
            </div>
          </div>
          
          {/* Status Indicator */}
          {isFileSearchEnabled && (
            <div className="mt-4 p-3 bg-success/5 dark:bg-success/10 border border-success/20 dark:border-success/30 rounded-lg">
              <div className="flex items-center space-x-2">
                <div className="w-2 h-2 bg-success rounded-full animate-pulse"></div>
                <span className="text-sm font-medium text-success dark:text-success-light">
                  File search is currently enabled
                </span>
              </div>
              <p className="text-xs text-muted-foreground mt-1">
                The AI can search through your selected vector store to provide better answers
              </p>
            </div>
          )}
        </DialogHeader>

        <div className="flex-1 overflow-y-auto py-6">
          <DocumentManager
            apiKey={apiKey}
            baseUrl={baseUrl}
            selectedVectorStore={selectedVectorStore}
            onVectorStoreSelect={onVectorStoreSelect}
          />
        </div>

        {/* Footer with action summary */}
        <div className="shrink-0 pt-4 border-t border-border">
          <div className="flex items-center justify-between">
            <div className="text-xs text-muted-foreground">
              {isFileSearchEnabled ? (
                <span>Vector store selected: Documents will be searched during conversations</span>
              ) : (
                <span>No vector store selected: Create and select one to enable file search</span>
              )}
            </div>
            <Button 
              variant="outline" 
              size="sm" 
              onClick={() => setIsOpen(false)}
              className="border-border text-foreground hover:bg-accent"
            >
              Close
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default DocumentModal; 