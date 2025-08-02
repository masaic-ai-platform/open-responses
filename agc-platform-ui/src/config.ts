export let API_URL: string;

if (typeof window !== 'undefined' && (window as any).__env__?.VITE_DASHBOARD_API_URL) {
  API_URL = (window as any).__env__.VITE_DASHBOARD_API_URL;
} else {
  API_URL = import.meta.env.VITE_DASHBOARD_API_URL || 'http://localhost:6644';
}