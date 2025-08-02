/**
 * Frontend authentication configuration
 */
export const AUTH_CONFIG = {
  googleClientId: import.meta.env.VITE_GOOGLE_CLIENT_ID || '',
} as const;

export interface GoogleUser {
  userId: string;
  email: string;
  name?: string;
  picture?: string;
}

export interface AuthConfig {
  enabled: boolean;
} 