import React, { createContext, useContext, useState, useEffect } from 'react';
import { API_URL } from '@/config';
import { apiClient } from '@/lib/api';

interface ModelSettings {
  settingsType: string;
  apiKey: string;
  model: string;
  bearerToken: string;
  qualifiedModelName: string;
}

interface VectorStoreInfo {
  isEnabled: boolean;
}

interface PlatformInfo {
  version: string;
  modelSettings: ModelSettings;
  vectorStoreInfo: VectorStoreInfo;
}

interface PlatformContextType {
  platformInfo: PlatformInfo | null;
  isLoading: boolean;
  error: string | null;
  refetchPlatformInfo: () => Promise<void>;
}

const defaultPlatformInfo: PlatformInfo = {
  version: 'UNKNOWN',
  modelSettings: {
    settingsType: 'RUNTIME',
    apiKey: '',
    model: '',
    bearerToken: 'Bearer ',
    qualifiedModelName: ''
  },
  vectorStoreInfo: {
    isEnabled: true
  }
};

const PlatformContext = createContext<PlatformContextType>({
  platformInfo: defaultPlatformInfo,
  isLoading: false,
  error: null,
  refetchPlatformInfo: async () => {}
});

export const usePlatformInfo = () => {
  const context = useContext(PlatformContext);
  if (!context) {
    throw new Error('usePlatformInfo must be used within a PlatformProvider');
  }
  return context;
};

interface PlatformProviderProps {
  children: React.ReactNode;
}

export const PlatformProvider: React.FC<PlatformProviderProps> = ({ children }) => {
  const [platformInfo, setPlatformInfo] = useState<PlatformInfo | null>(defaultPlatformInfo);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchPlatformInfo = async () => {
    setIsLoading(true);
    setError(null);
    
    try {
      const response = await apiClient.rawRequest('/v1/dashboard/platform/info');
      
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      
      const data = await response.json();
      setPlatformInfo(data);
    } catch (err) {
      console.error('Error fetching platform info:', err);
      setError(err instanceof Error ? err.message : 'Failed to fetch platform info');
      // Keep default values on error
      setPlatformInfo(defaultPlatformInfo);
    } finally {
      setIsLoading(false);
    }
  };

  // Fetch platform info on mount and page visibility change (refresh detection)
  useEffect(() => {
    fetchPlatformInfo();

    // Listen for page visibility changes (to detect page refresh/focus)
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        fetchPlatformInfo();
      }
    };

    // Listen for focus events (when user returns to the tab)
    const handleFocus = () => {
      fetchPlatformInfo();
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('focus', handleFocus);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.removeEventListener('focus', handleFocus);
    };
  }, []);

  const refetchPlatformInfo = async () => {
    await fetchPlatformInfo();
  };

  return (
    <PlatformContext.Provider
      value={{
        platformInfo,
        isLoading,
        error,
        refetchPlatformInfo
      }}
    >
      {children}
    </PlatformContext.Provider>
  );
};

export default PlatformContext; 