import React from 'react';

interface MasaicBrandAccentProps {
  className?: string;
}

const MasaicBrandAccent: React.FC<MasaicBrandAccentProps> = ({ className = "" }) => {
  return (
    <div className={`absolute top-4 right-4 opacity-20 group-hover/card:opacity-40 transition-opacity duration-300 ${className}`}>
      <div className="relative w-3 h-3">
        {/* Outer gradient layer */}
        <div className="absolute inset-0 bg-gradient-to-br from-accent via-primary to-accent rounded-full animate-pulse-faint"></div>
        
        {/* Background separator */}
        <div className="absolute inset-0.5 bg-background rounded-full"></div>
        
        {/* Inner gradient core */}
        <div className="absolute inset-1 bg-gradient-to-br from-primary to-accent rounded-full"></div>
      </div>
    </div>
  );
};

export default MasaicBrandAccent; 