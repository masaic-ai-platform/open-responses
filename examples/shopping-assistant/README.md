# Shopping Assistant Demo

This example demonstrates how to run the Shopping Assistant with Open Responses.

<a href="https://www.youtube.com/watch?v=C4pHvhuGlog" target="_blank">
  <img src="https://img.youtube.com/vi/C4pHvhuGlog/0.jpg" alt="Shopping Assistant Demo" width="560" height="315" border="10" />
</a>

## Components
- **Open Responses**: 
- **Jupyter Notebook**: An interactive notebook demonstrating OpenResponses Agent orchestration

## Source Code

For inspiration, the Shopping Assistant UI codebase is available at:
- https://github.com/masaic-ai-platform/shopper-cart-flow-ui

## Features
This demo showcases the integration with Shopify MCP, which allows:

- Searching product catalogs in real-time
- Adding items to carts programmatically
- Generating checkout links
- Providing a conversational shopping experience

The Jupyter notebook (`shopify_mcp_demo.ipynb`) demonstrates how to use the OpenAI Python SDK to interact with OpenResponse orchestration of MCP tools.

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
   http://localhost:6644
   ```

5. The Jupyter notebook interface will be available at:
   ```
   http://localhost:8890
   ```

### Using the Jupyter Notebook

The included notebook demonstrates how to:

1. Connect to the Open Responses service using the OpenAI SDK
2. Execute MCP calls to Shopify for catalog search and cart operations
3. Parse and display responses from the assistant

To run the notebook:
1. Open the Jupyter interface at http://localhost:8890
2. Open the `shopify_mcp_demo.ipynb` file

## Troubleshooting

If you encounter issues:

1. Check that all services are running:
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
   # or 
   docker compose logs notebook
   ```

## Stopping the Demo

To stop all services:
```bash
docker compose down
``` 
