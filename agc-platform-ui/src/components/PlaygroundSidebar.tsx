import React from 'react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/contexts/AuthContext';
import { 
  MessageSquare, 
  Zap, 
  Key,
  Github,
  MessageCircle,
  Database,
  BarChart3,
  Shield,
  Sparkles,
  Plus,
  LogOut
} from 'lucide-react';

interface PlaygroundSidebarProps {
  activeTab: string;
  onTabChange: (tab: string) => void;
  className?: string;
}

const PlaygroundSidebar: React.FC<PlaygroundSidebarProps> = ({ 
  activeTab, 
  onTabChange,
  className = ''
}) => {
  const { logout, authEnabled, isAuthenticated } = useAuth();
  const mainOptions = [
    { id: 'responses', label: 'AgC API', icon: MessageSquare },
    { id: 'completions', label: 'Completions', icon: Zap, disabled: true },
  ];

  // Static options that appear under Completions
  const staticCompletionOptions = [
    { id: 'vector-store', label: 'Vector Store', icon: Database },
    { id: 'observability', label: 'Observability', icon: BarChart3, link: 'https://communal-lionfish.in.signoz.cloud/home' },
    // New clickable option directly below Observability
    { id: 'masaic-mocky', label: 'Masaic Mocky', icon: Sparkles, clickable: true },
    { id: 'add-model', label: 'Add Model', icon: Plus, clickable: true },
    { id: 'compliance', label: 'Compliance', icon: Shield },
  ];

  const bottomOptions = [
    { id: 'api-keys', label: 'API Keys', icon: Key },
    { id: 'github', label: 'GitHub', icon: Github, link: 'https://github.com/masaic-ai-platform' },
    { id: 'discord', label: 'Discord', icon: MessageCircle, link: 'https://discord.com/channels/1335132819260702723/1354795442004820068' },
  ];

  // Add Sign Out option if authentication is enabled and user is authenticated
  const signOutOption = authEnabled && isAuthenticated ? 
    { id: 'sign-out', label: 'Sign Out', icon: LogOut, action: 'signout' } : null;

  return (
    <div className={cn("bg-background border-r border-border h-full flex flex-col", className)}>
      {/* Main Options */}
      <div className="flex-1 p-2 space-y-1">
        {mainOptions.map((option) => {
          const Icon = option.icon;
          const isDisabled = option.disabled;
          return (
            <Button
              key={option.id}
              variant={activeTab === option.id ? "secondary" : "ghost"}
              className={`w-full justify-start text-xs h-8 ${
                activeTab === option.id 
                  ? 'bg-accent text-accent-foreground' 
                  : isDisabled
                    ? 'text-muted-foreground cursor-not-allowed opacity-60'
                    : 'text-muted-foreground hover:text-foreground hover:bg-accent/50'
              }`}
              onClick={() => !isDisabled && onTabChange(option.id)}
              disabled={isDisabled}
            >
              <Icon className="h-3 w-3 mr-2" />
              {option.label}
            </Button>
          );
        })}

        {/* Static Completion Options */}
        <div className="space-y-1 mt-1">
          {staticCompletionOptions.map((option) => {
            const Icon = option.icon;

            // External link behaviour
            if ('link' in option && option.link) {
              return (
                <Button
                  key={option.id}
                  variant="ghost"
                  className="w-full justify-start text-xs h-8 text-muted-foreground hover:text-foreground hover:bg-accent/50"
                  onClick={() => window.open(option.link, '_blank')}
                >
                  <Icon className="h-3 w-3 mr-2" />
                  {option.label}
                </Button>
              );
            }

            // Clickable internal option (e.g., Masaic Mocky)
            if ('clickable' in option && option.clickable) {
              return (
                <Button
                  key={option.id}
                  variant={activeTab === option.id ? 'secondary' : 'ghost'}
                  className={`w-full justify-start text-xs h-8 ${
                    activeTab === option.id
                      ? 'bg-accent text-accent-foreground'
                      : 'text-muted-foreground hover:text-foreground hover:bg-accent/50'
                  }`}
                  onClick={() => onTabChange(option.id)}
                >
                  <Icon className="h-3 w-3 mr-2" />
                  {option.label}
                </Button>
              );
            }

            // Default disabled static option
            return (
              <Button
                key={option.id}
                variant="ghost"
                className="w-full justify-start text-xs h-8 text-muted-foreground opacity-50 cursor-default"
                disabled
              >
                <Icon className="h-3 w-3 mr-2" />
                {option.label}
              </Button>
            );
          })}
        </div>
      </div>

      {/* Bottom Options */}
      <div className="p-2 border-t border-border space-y-1">
        {bottomOptions.map((option) => {
          const Icon = option.icon;
          const hasLink = 'link' in option && option.link;
          
          if (hasLink) {
            return (
              <Button
                key={option.id}
                variant="ghost"
                className="w-full justify-start text-xs h-8 text-muted-foreground hover:text-foreground hover:bg-accent/50"
                onClick={() => window.open(option.link, '_blank')}
              >
                <Icon className="h-3 w-3 mr-2" />
                {option.label}
              </Button>
            );
          }
          
          return (
            <Button
              key={option.id}
              variant="ghost"
              className="w-full justify-start text-xs h-8 text-muted-foreground hover:text-foreground hover:bg-accent/50"
              onClick={() => onTabChange(option.id)}
            >
              <Icon className="h-3 w-3 mr-2" />
              {option.label}
            </Button>
          );
        })}
        
        {/* Sign Out Button - Only shown when authenticated */}
        {signOutOption && (
          <Button
            key={signOutOption.id}
            variant="ghost"
            className="w-full justify-start text-xs h-8 text-muted-foreground hover:text-red-600 hover:bg-red-50 dark:hover:bg-red-950/20"
            onClick={() => {
              logout();
            }}
          >
            <signOutOption.icon className="h-3 w-3 mr-2" />
            {signOutOption.label}
          </Button>
        )}
      </div>
    </div>
  );
};

export default PlaygroundSidebar; 