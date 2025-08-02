import React from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { GoogleLogin as GoogleLoginButton } from '@react-oauth/google';
import { Github, MessageCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface GoogleLoginProps {
  onSuccess?: () => void;
  onError?: (error: Error) => void;
  showApiError?: boolean;
}

const GoogleLogin: React.FC<GoogleLoginProps> = ({ onSuccess, onError, showApiError = false }) => {
  const { login } = useAuth();

  const handleGoogleSuccess = async (credentialResponse: any) => {
    try {
      if (credentialResponse.credential) {
        await login(credentialResponse.credential);
        onSuccess?.();
      } else {
        throw new Error('No credential received from Google');
      }
    } catch (error) {
      console.error('Google login failed:', error);
      onError?.(error as Error);
    }
  };

  const handleGoogleError = () => {
    const error = new Error('Google login was cancelled or failed');
    onError?.(error);
  };

  return (
    <div className="h-full w-full flex items-center justify-center bg-background relative">
      {/* API Connection Error - Positioned absolutely to not affect centering */}
      {showApiError && (
        <div className="absolute top-8 left-1/2 transform -translate-x-1/2 w-full max-w-md px-4">
          <div className="bg-red-50 dark:bg-red-950/20 border border-red-200 dark:border-red-800 rounded-md p-4">
            <div className="flex items-center">
              <div className="w-2 h-2 bg-red-500 rounded-full mr-2"></div>
              <h3 className="text-sm font-medium text-red-800 dark:text-red-200">
                API Connection Error
              </h3>
            </div>
            <div className="mt-2 text-sm text-red-700 dark:text-red-300">
              Cannot connect to API server.
              <br />
              Please check your API server connection and try again.
            </div>
          </div>
        </div>
      )}

      {/* Centered Login Content */}
      <div className="flex flex-col items-center justify-center space-y-6">
        {/* Google Login Button with Positive Trend Color */}
        <div className="flex justify-center">
          <div className="relative">
            <div className="absolute inset-0 bg-positive-trend rounded-lg opacity-10"></div>
            <div className="relative p-1 rounded-lg border border-positive-trend/20 hover:border-positive-trend/40 transition-colors duration-200">
              <GoogleLoginButton
                onSuccess={handleGoogleSuccess}
                onError={handleGoogleError}
                useOneTap={false}
                theme="outline"
                size="large"
                text="signin_with"
                shape="rectangular"
              />
            </div>
          </div>
        </div>

        {/* GitHub and Discord Links */}
        <div className="flex items-center space-x-4 mt-4">
          <Button
            variant="ghost"
            size="sm"
            className="text-muted-foreground hover:text-foreground"
            onClick={() => window.open('https://github.com/masaic-ai-platform', '_blank')}
          >
            <Github className="h-4 w-4 mr-2" />
            GitHub
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="text-muted-foreground hover:text-foreground"
            onClick={() => window.open('https://discord.com/channels/1335132819260702723/1354795442004820068', '_blank')}
          >
            <MessageCircle className="h-4 w-4 mr-2" />
            Discord
          </Button>
        </div>
      </div>
    </div>
  );
};

export default GoogleLogin; 