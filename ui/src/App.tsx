import { Toaster } from "@/components/ui/toaster";
import { Toaster as Sonner } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { PlatformProvider } from "./contexts/PlatformContext";
import { AuthProvider } from "./contexts/AuthContext";
import { GoogleOAuthProvider } from "@react-oauth/google";
import ProtectedRoute from "./components/ProtectedRoute";
import Layout from "./components/Layout";
import Chat from "./pages/Chat";
import NotFound from "./pages/NotFound";
import { AUTH_CONFIG } from "./config/auth";

const queryClient = new QueryClient();

const App = () => (
  <QueryClientProvider client={queryClient}>
    <PlatformProvider>
      <GoogleOAuthProvider clientId={AUTH_CONFIG.googleClientId}>
        <AuthProvider>
          <TooltipProvider>
            <Toaster />
            <Sonner />
            <BrowserRouter>
              <Layout>
                <ProtectedRoute>
                  <Routes>
                    <Route path="/" element={<Chat />} />
                    {/* ADD ALL CUSTOM ROUTES ABOVE THE CATCH-ALL "*" ROUTE */}
                    <Route path="*" element={<NotFound />} />
                  </Routes>
                </ProtectedRoute>
              </Layout>
            </BrowserRouter>
          </TooltipProvider>
        </AuthProvider>
      </GoogleOAuthProvider>
    </PlatformProvider>
  </QueryClientProvider>
);

export default App;
