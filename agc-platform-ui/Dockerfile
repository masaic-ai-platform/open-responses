# ─── BUILD STAGE ───────────────────────────────────────────────────────────────
FROM node:18-alpine AS build
WORKDIR /app

# Set environment variables for build
ENV VITE_APP_VERSION=0.0.4

# Install dependencies for build
COPY package*.json ./
RUN npm ci --legacy-peer-deps

# Copy source code
COPY . .

# Build the frontend
RUN npm run build -- --mode prod

# ─── PRODUCTION STAGE ──────────────────────────────────────────────────────────
FROM gcr.io/distroless/nodejs18-debian12 AS production
WORKDIR /app

# Set environment variables for runtime
ENV APP_VERSION=0.0.4

# Copy built frontend
COPY --from=build /app/dist ./dist

# Copy server entrypoint
COPY server.cjs .

# Expose port 80
EXPOSE 80

# Start the server
CMD ["server.cjs"]