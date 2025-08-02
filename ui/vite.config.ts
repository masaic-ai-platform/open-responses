import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";
import { readFileSync } from "fs";

// Read package.json for version
const packageJson = JSON.parse(readFileSync('./package.json', 'utf8'));

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
  server: {
    host: "::",
    port: 6645,
  },
  plugins: [
    react(),
  ].filter(Boolean),
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  define: {
    // Inject version information at build time
    'import.meta.env.VITE_APP_VERSION': JSON.stringify(packageJson.version),
  },
}));
