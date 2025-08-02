import React from 'react';
import Navigation from './Navigation';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  return (
    <div className="flex flex-col h-screen bg-background">
      <Navigation />
      <div className="flex-1 overflow-hidden">
        {children}
      </div>
    </div>
  );
};

export default Layout; 