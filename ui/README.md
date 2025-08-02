# Agc Platform UI

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
| `VITE_DASHBOARD_API_URL` | `http://localhost:6644` | API Server endpoint                |
| `MODE` | `development` | Build mode (development/production) |

## Available Scripts

- `npm run dev` - Start development server
- `npm run build` - Build for production
- `npm run preview` - Preview production build
