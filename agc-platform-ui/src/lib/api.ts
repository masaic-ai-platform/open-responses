import { API_URL } from '@/config';

class ApiClient {
  private getModelApiKey(): string | null {
    // This would get the model API key from your existing logic
    // For now, return null to maintain existing behavior
    return null;
  }

  private getGoogleToken(): string | null {
    return localStorage.getItem('google_token');
  }

  private async isAuthEnabled(): Promise<boolean> {
    try {
      const response = await fetch(`${API_URL}/v1/dashboard/platform/info`);
      const platformInfo = await response.json();
      return platformInfo.authConfig?.enabled || false;
    } catch {
      return false;
    }
  }

              private async getHeaders(): Promise<HeadersInit> {
                const headers: HeadersInit = {
                  'Content-Type': 'application/json',
                };

                // Add model API key (existing behavior)
                const modelApiKey = this.getModelApiKey();
                if (modelApiKey) {
                  headers['Authorization'] = `Bearer ${modelApiKey}`;
                }

                // Add Google token if auth is enabled
                const authEnabled = await this.isAuthEnabled();
                if (authEnabled) {
                  const googleToken = this.getGoogleToken();
                  if (googleToken) {
                    headers['X-Google-Token'] = googleToken;
                  }
                }

                return headers;
              }

  private handleAuthError() {
    // Clear invalid token
    localStorage.removeItem('google_token');
    // Instead of reloading, dispatch a custom event to trigger login screen
    window.dispatchEvent(new CustomEvent('auth:token-expired'));
  }

  async request(endpoint: string, options: RequestInit = {}): Promise<Response> {
    const headers = { ...await this.getHeaders(), ...options.headers };

    try {
      const response = await fetch(`${API_URL}${endpoint}`, {
        ...options,
        headers,
      });

      if (response.status === 401) {
        // Handle authentication error
        this.handleAuthError();
        throw new Error('Authentication required');
      }

      return response;
    } catch (error) {
      // If it's an authentication error, re-throw it
      if (error instanceof Error && error.message === 'Authentication required') {
        throw error;
      }
      
      // For other errors (network, etc.), throw a generic error
      throw new Error('Network error');
    }
  }

  // Helper method for JSON requests
  async jsonRequest<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const response = await this.request(endpoint, options);
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    
    return response.json();
  }

  // Helper method for non-JSON requests (like file uploads)
  async rawRequest(endpoint: string, options: RequestInit = {}): Promise<Response> {
    return this.request(endpoint, options);
  }
}

// Export singleton instance
export const apiClient = new ApiClient(); 