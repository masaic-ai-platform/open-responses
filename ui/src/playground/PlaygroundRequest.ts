export interface PlaygroundRequest {
  method: 'POST' | 'GET';
  url: '/v1/responses' | '/v1/chat';
  headers: Record<string, string>;
  body: Record<string, unknown> | null;
} 