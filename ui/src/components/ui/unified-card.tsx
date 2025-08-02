import React from 'react';
import { cn } from '@/lib/utils';
import MasaicBrandAccent from './masaic-brand-accent';

interface UnifiedCardProps {
  children: React.ReactNode;
  className?: string;
  semanticType?: 'positive' | 'negative' | 'critical' | 'opportunity' | 'neutral';
  showBrandAccent?: boolean;
  showBottomSpacer?: boolean;
  onClick?: () => void;
}

const UnifiedCard: React.FC<UnifiedCardProps> = ({ 
  children, 
  className = "", 
  semanticType = 'neutral',
  showBrandAccent = true,
  showBottomSpacer = true,
  onClick 
}) => {
  const getSemanticGlow = () => {
    switch (semanticType) {
      case 'positive':
        return 'rgba(46, 125, 50, 0.15)';
      case 'negative':
        return 'rgba(239, 108, 0, 0.15)';
      case 'critical':
        return 'rgba(198, 40, 40, 0.15)';
      case 'opportunity':
        return 'rgba(196, 155, 11, 0.15)';
      case 'neutral':
      default:
        return 'rgba(255, 255, 255, 0.08)';
    }
  };

  return (
    <div 
      className={cn(
        "relative group/card",
        "rounded-2xl border border-warm-whisper-grey/60 dark:border-soft-neutral-white/30 shadow-md transition-all duration-300 ease-in-out h-full flex flex-col",
        "group-hover/card:shadow-lg",
        "dark:group-hover/card:border-warm-whisper-grey/50 group-hover/card:border-cool-silver-grey/80",
        "group-hover/card:shadow-tile-glow-semantic group-focus-within/card:shadow-tile-glow-semantic",
        "group-hover/card:ring-1 group-hover/card:ring-inset group-hover/card:ring-tile-inner-border-highlight-color",
        onClick && "cursor-pointer",
        className
      )}
      onClick={onClick}
      style={{
        '--tile-glow-color-semantic': getSemanticGlow(),
        '--tile-inner-border-color-semantic': `${getSemanticGlow().replace('0.15', '0.2')}`,
      } as React.CSSProperties}
    >
      {showBrandAccent && <MasaicBrandAccent />}
      
      <div className="flex-1 flex flex-col">
        {children}
      </div>
      
      {/* Bottom spacer for interaction dock - only show when needed */}
      {showBottomSpacer && <div className="h-[50px] shrink-0"></div>}
    </div>
  );
};

export default UnifiedCard; 