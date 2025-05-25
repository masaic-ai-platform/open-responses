# Shopping Assistant Demo

This example demonstrates how to run the Shopping Assistant with Open Responses.

## Components

- **Shopping Assistant**: A demo application showcasing AI-assisted shopping experiences
- **Open Responses**: A service for handling AI responses and evaluation

## Source Code

The Shopping Assistant codebase is available at:
- https://github.com/masaic-ai-platform/shopper-cart-flow-ui

## Getting Started

### Prerequisites

- Docker and Docker Compose installed on your machine
- Internet connection to pull the required Docker images

### Running the Demo

1. Navigate to this directory:
   ```bash
   cd examples/shopping-assistant
   ```

2. Start the services using Docker Compose:
   ```bash
   docker compose up
   ```

3. Access the Shopping Assistant interface at:
   ```
   http://localhost:8888
   ```

4. The Open Responses service will be running at:
   ```
   http://localhost:8080
   ```

## Configuration

The Docker Compose setup automatically configures:
- Network connectivity between services
- Port mappings for local access
- Dependencies to ensure proper startup order

## Troubleshooting

If you encounter issues:

1. Check that both services are running:
   ```bash
   docker compose ps
   ```

2. View logs for more information:
   ```bash
   docker compose logs
   ```

3. To view logs for a specific service:
   ```bash
   docker compose logs open-responses
   # or
   docker compose logs app
   ```

## Stopping the Demo

To stop all services:
```bash
docker compose down
``` 
