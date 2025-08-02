// Version information
export interface VersionInfo {
  version: string;
  environment: string;
}

// Get version from package.json (injected by Vite)
export const VERSION = import.meta.env.VITE_APP_VERSION || '0.0.1';

// Get environment
export const ENVIRONMENT = import.meta.env.MODE || 'development';

// Create version info object
export const getVersionInfo = (): VersionInfo => ({
  version: VERSION,
  environment: ENVIRONMENT,
});

// Format version for display
export const formatVersion = (version: string = VERSION): string => {
  return `v${version}`;
}; 