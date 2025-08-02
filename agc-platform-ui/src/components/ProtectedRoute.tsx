import React from 'react';
import { useAuth } from '@/contexts/AuthContext';
import GoogleLogin from './GoogleLogin';
import { Loader2 } from 'lucide-react';

interface ProtectedRouteProps {
  children: React.ReactNode;
}

const ProtectedRoute: React.FC<ProtectedRouteProps> = ({ children }) => {
  const { isAuthenticated, isLoading, authEnabled, apiError, tokenExpired } = useAuth();

  // If auth is disabled, render children directly
  if (!authEnabled) {
    return <>{children}</>;
  }

  // If loading, show spinner
  if (isLoading) {
    return (
      <div className="h-full w-full flex items-center justify-center">
        <div className="flex items-center space-x-2">
          <Loader2 className="h-6 w-6 animate-spin" />
          <span>Loading...</span>
        </div>
      </div>
    );
  }

  // If not authenticated or token expired, show login (with API error if present)
  if (!isAuthenticated || tokenExpired) {
    return <GoogleLogin showApiError={apiError} />;
  }

  // If authenticated, render children
  return <>{children}</>;
};

export default ProtectedRoute; 