// server.js
const http = require('http');
const fs = require('fs');
const path = require('path');

const dist = path.join(__dirname, 'dist');
const port = process.env.PORT || 80;

const mimes = {
  '.js':  'application/javascript',
  '.css': 'text/css',
  '.html':'text/html',
  '.json':'application/json',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const server = http.createServer((req, res) => {
  // Simple health-check endpoint
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    return res.end('OK');
  }
  if (req.url === '/env.js') {
    res.writeHead(200, { 'Content-Type': 'application/javascript' });
    const env = {
      VITE_DASHBOARD_API_URL: process.env.VITE_DASHBOARD_API_URL || ''
    };
    return res.end(`window.__env__ = ${JSON.stringify(env)};`);
  }

  const urlPath = (req.url === '/' ? '/index.html' : req.url).split('?')[0];
  const filePath = path.join(dist, urlPath);

  if (!fs.existsSync(filePath)) {
    res.writeHead(404);
    return res.end('Not found');
  }

  const ext = path.extname(filePath);
  res.writeHead(200, { 'Content-Type': mimes[ext] || 'application/octet-stream' });
  res.end(fs.readFileSync(filePath));
});

server.listen(port, () => {
  console.log(`Server running at http://0.0.0.0:${port}`);
});