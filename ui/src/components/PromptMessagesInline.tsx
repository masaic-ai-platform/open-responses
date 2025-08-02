import React, { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Plus, Check, X, ChevronDown, ChevronRight } from 'lucide-react';

interface PromptMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
}

interface PromptMessagesInlineProps {
  promptMessages: PromptMessage[];
  onAddPromptMessage: (message: PromptMessage) => void;
  onRemovePromptMessage: (id: string) => void;
}

const PromptMessagesInline: React.FC<PromptMessagesInlineProps> = ({
  promptMessages,
  onAddPromptMessage,
  onRemovePromptMessage
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [selectedRole, setSelectedRole] = useState<'user' | 'assistant'>('user');
  const [messageContent, setMessageContent] = useState('');

  const handleAddMessage = () => {
    if (messageContent.trim()) {
      const newMessage: PromptMessage = {
        id: Date.now().toString(),
        role: selectedRole,
        content: messageContent.trim()
      };
      
      onAddPromptMessage(newMessage);
      setMessageContent('');
      setSelectedRole('user');
      
      // Reset textarea height
      const textarea = document.querySelector('.prompt-message-textarea') as HTMLTextAreaElement;
      if (textarea) {
        textarea.style.height = 'auto';
        textarea.style.height = '32px';
      }
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleAddMessage();
    }
  };

  const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const textarea = e.target;
    setMessageContent(textarea.value);
    
    // Auto-resize functionality
    textarea.style.height = 'auto';
    textarea.style.height = Math.max(Math.min(textarea.scrollHeight, 150), 32) + 'px';
  };

  return (
    <Collapsible open={isOpen} onOpenChange={setIsOpen}>
      <CollapsibleTrigger asChild>
        <div className="w-full cursor-pointer">
          <div className="flex items-center justify-between py-2 hover:bg-muted/30 rounded transition-colors">
            <Label className="text-xs font-medium text-muted-foreground cursor-pointer">
              Add messages to prompt
            </Label>
            <div className="flex items-center space-x-2">
              {promptMessages.length > 0 && (
                <span className="text-xs bg-positive-trend/20 text-positive-trend px-2 py-0.5 rounded">
                  {promptMessages.length}
                </span>
              )}
              <Button
                variant="ghost"
                size="sm"
                className="h-4 w-4 p-0 text-xs text-muted-foreground hover:text-foreground"
              >
                {isOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
              </Button>
            </div>
          </div>
        </div>
      </CollapsibleTrigger>
      
      <CollapsibleContent className="mt-3">
        <div className="space-y-3">
          {/* Header */}
          <div className="text-sm font-medium text-foreground">
            Prompt messages
          </div>
          
          {/* Existing Messages */}
          {promptMessages.map((message) => (
            <div key={message.id} className="flex items-start space-x-3 p-3 bg-muted/50 rounded-lg border border-border/50">
              <div className="flex-1">
                <div className="flex items-center justify-between mb-2">
                  <div className="flex items-center space-x-2">
                    <span className={`px-2 py-1 rounded text-xs font-medium ${
                      message.role === 'user' 
                        ? 'bg-positive-trend/20 text-positive-trend' 
                        : 'bg-opportunity/20 text-opportunity'
                    }`}>
                      {message.role === 'user' ? 'User' : 'Assistant'}
                    </span>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 w-6 p-0 text-muted-foreground hover:text-critical-alert"
                    onClick={() => onRemovePromptMessage(message.id)}
                  >
                    <X className="h-3 w-3" />
                  </Button>
                </div>
                <p className="text-xs text-foreground leading-relaxed">{message.content}</p>
              </div>
            </div>
          ))}
          
          {/* Add New Message Form */}
          <div className="space-y-3">
            {/* Role Selection */}
            <div className="flex items-center space-x-2">
              <Select value={selectedRole} onValueChange={(value: 'user' | 'assistant') => setSelectedRole(value)}>
                <SelectTrigger className="w-20 h-8 text-xs focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem 
                    value="user"
                    className="focus:bg-positive-trend/10 focus:text-positive-trend"
                  >
                    <div className="flex items-center space-x-2">
                      <Check className="h-3 w-3 text-positive-trend" />
                      <span>User</span>
                    </div>
                  </SelectItem>
                  <SelectItem 
                    value="assistant"
                    className="focus:bg-positive-trend/10 focus:text-positive-trend"
                  >
                    <div className="flex items-center space-x-2">
                      <span>Assistant</span>
                    </div>
                  </SelectItem>
                </SelectContent>
              </Select>
              <Button
                variant="ghost"
                size="sm"
                className="h-8 w-8 p-0 text-muted-foreground hover:text-foreground"
                title="Remove selection"
              >
                <X className="h-3 w-3" />
              </Button>
            </div>
            
            {/* Message Input */}
            <div className="space-y-2">
              <Textarea
                value={messageContent}
                onChange={handleTextareaChange}
                onKeyPress={handleKeyPress}
                placeholder={`Enter ${selectedRole} message...`}
                className="prompt-message-textarea w-full text-xs resize-none focus:border-positive-trend/60 focus:ring-0 focus:ring-offset-0 focus:shadow-none focus-visible:outline-none focus-visible:ring-0 focus-visible:ring-offset-0 focus-visible:border-positive-trend/60 transition-all duration-200"
                style={{ 
                  minHeight: '32px',
                  maxHeight: '150px',
                  boxShadow: 'none !important',
                  outline: 'none !important',
                  lineHeight: '1.5'
                }}
                rows={1}
              />
            </div>
            
            {/* Add Button */}
            <div className="flex justify-start">
              <Button
                onClick={handleAddMessage}
                disabled={!messageContent.trim()}
                size="sm"
                className="h-8 px-3 text-xs bg-positive-trend hover:bg-positive-trend/90 text-white disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus className="h-3 w-3 mr-1" />
                Add message
              </Button>
            </div>
          </div>
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
};

export default PromptMessagesInline; 