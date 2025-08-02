import React from 'react';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { getVersionInfo, formatVersion } from '@/lib/version';
import { usePlatformInfo } from '@/contexts/PlatformContext';

interface VersionBadgeProps {
  showTooltip?: boolean;
  className?: string;
}

const VersionBadge: React.FC<VersionBadgeProps> = ({ 
  showTooltip = true, 
  className = '' 
}) => {
  const versionInfo = getVersionInfo();
  const { platformInfo, isLoading } = usePlatformInfo();
  
  const uiVersion = formatVersion(versionInfo.version);
  const platformVersion = platformInfo?.version || 'UNKNOWN';

  const versionDisplay = (
    <div 
      className={`text-xs text-muted-foreground font-mono ${className}`}
      aria-label={`UI Version ${versionInfo.version}, Platform Version ${platformVersion}`}
    >
      <div className="flex items-center gap-2">
        <span>UI:</span>
        <span>{uiVersion}</span>
      </div>
      <div className="flex items-center gap-2">
        <span>PV:</span>
        <span>{platformVersion}</span>
      </div>
    </div>
  );

  if (!showTooltip) {
    return versionDisplay;
  }

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          {versionDisplay}
        </TooltipTrigger>
        <TooltipContent side="bottom" className="max-w-xs">
          <div className="space-y-1">
            <div className="font-medium">Version Information</div>
            <div className="text-xs text-muted-foreground space-y-1">
              <div>UI Version: {versionInfo.version}</div>
              <div>Platform Version: {platformVersion}</div>
              <div>Environment: {versionInfo.environment}</div>
              {isLoading && <div className="text-blue-400">Loading platform info...</div>}
            </div>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
};

export default VersionBadge; 