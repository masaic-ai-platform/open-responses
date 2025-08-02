# Masaic Dev Platform UI
[![Discord](https://img.shields.io/static/v1?label=Discord&message=Join%20Us&color=5865F2&logo=discord&logoColor=white)](https://discord.com/channels/1335132819260702723/1354795442004820068)
[![Discussions](https://img.shields.io/static/v1?label=Discussions&message=Community&color=3FB950&logo=github&logoColor=white)](https://github.com/orgs/masaic-ai-platform/discussions)
[![Docker Hub Version](https://img.shields.io/docker/v/masaicai/platform-ui?label=version&sort=semver)](https://hub.docker.com/r/masaicai/platform-ui/tags)

This is user interface to access Masaic's Agent development platform.

## Local Setup

1. Install dependencies:
   ```bash
   npm install
   ```

2. Start development server:
   ```bash
   npm run dev
   ```

3. Open [http://localhost:6645](http://localhost:6645)

## Environment Properties

| Property | Default | Description                        |
|----------|---------|------------------------------------|
| `VITE_APP_VERSION` | `0.0.1` | Application version                |
| `VITE_DASHBOARD_API_URL` | `http://localhost:6644` | API Server endpoint                |
| `MODE` | `development` | Build mode (development/production) |

## Available Scripts

- `npm run dev` - Start development server
- `npm run build` - Build for production
- `npm run preview` - Preview production build
