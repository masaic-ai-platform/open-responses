# Agentic E-commerce Demo

[![Agentic E-commerce Demo Video](https://img.youtube.com/vi/5ro_l4RJoHY/maxresdefault.jpg)](https://www.youtube.com/watch?v=5ro_l4RJoHY)

**🎥 [Watch the Demo Video](https://www.youtube.com/watch?v=5ro_l4RJoHY)**

This directory contains a comprehensive demonstration of agentic e-commerce capabilities including product search, scene generation, and payment confirmation using AI agents and the Model Context Protocol (MCP).

## Features

- **Agentic Product Search**: AI-powered product discovery and recommendations
- **Scene Generation**: Visual content creation for products using AI models
- **Payment Confirmation**: Automated payment processing workflow
- **Customizable Models**: Support for different AI models based on your needs

## Prerequisites

- Docker and Docker Compose
- Git
- OpenAI API Key
- Web browser for Jupyter Lab access

## Setup Instructions

### 1. Clone the Repository

```bash
git clone https://github.com/masaic-ai-platform/misc.git
```

### 2. Navigate to the Directory

```bash
cd examples/agentic-ecommerce
```

### 3. Set Environment Variables

Set your OpenAI API key as an environment variable:

**Linux/macOS:**
```bash
export OPENAI_API_KEY="your-openai-api-key-here"
```

**Windows (Command Prompt):**
```cmd
set OPENAI_API_KEY=your-openai-api-key-here
```

**Windows (PowerShell):**
```powershell
$env:OPENAI_API_KEY="your-openai-api-key-here"
```

### 4. Start the Services

Launch all required services using Docker Compose:

```bash
docker-compose up
```

This will start:
- **Open Responses Server** on port 8080 - The main backend API server
- **Demo MCP Server** on port 8086 - For image processing and scene generation
- **Jupyter Lab Server** on port 8890 - Interactive notebook environment

### 5. Access the Notebook

Once the services are running, open your web browser and navigate to:

```
http://localhost:8890/lab?
```

This will open Jupyter Lab where you can access the `agentic_ecommerce_journey.ipynb` notebook.

## Usage

### Running the Notebook

1. Open the `agentic_ecommerce_journey.ipynb` notebook in Jupyter Lab
2. Follow the step-by-step cells to explore:
   - **Product Search**: Search and discover products using AI agents
   - **Scene Generation**: Generate visual content and product scenes
   - **Payment Processing**: Complete payment confirmation workflows

### Customizing Models

The notebook demonstrates using different AI models for each step of the e-commerce journey. You can customize these according to your needs:

**Current Model Configuration:**
- **Step 1 (Product Search)**: `togetherai@meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8`
- **Step 2 (Scene Generation)**: `openai@gpt-4.1-mini`  
- **Step 3 (Payment Confirmation)**: `claude@claude-sonnet-4-20250514`

**Required API Keys:**
Update these variables in the notebook's first cell:
```python
# API Key Configuration for Different Models
LLAMA_API_KEY = "YOUR_TOGETHERAI_API_KEY"     # For Step 1
OPENAI_API_KEY = "YOUR_OPENAI_API_KEY"        # For Step 2
CLAUDE_API_KEY = "YOUR_ANTHROPIC_API_KEY"     # For Step 3
RAZORPAY_API_KEY = "YOUR_RAZORPAY_API_KEY"    # For payment processing
```

**Changing Models:**
To use different models, modify the `model` parameter in each step's function:
```python
# Example: Change Step 2 to use a different OpenAI model
stream = openai_client.responses.create(
    model="openai@gpt-4o",  # Changed from gpt-4.1-mini
    tools=[...],
    ...
)
```

## Additional Resources

### Related Projects

- **Demo MCP Server**: The image processing MCP server used in this demo is available at:
  [https://github.com/masaic-ai-platform/demo-mcp-server](https://github.com/masaic-ai-platform/demo-mcp-server)

- **Agentic E-commerce UI**: For UI inspiration and reference implementation:
  [https://github.com/masaic-ai-platform/agentic-ecommerce](https://github.com/masaic-ai-platform/agentic-ecommerce)

### Architecture

This demo utilizes:
- **Model Context Protocol (MCP)** for tool integration
- **Docker containers** for service orchestration
- **Jupyter notebooks** for interactive exploration
- **OpenAI APIs** for AI model access

## Files in this Directory

- `agentic_ecommerce_journey.ipynb`: Main demonstration notebook
- `docker-compose.yml`: Service configuration and orchestration
- `README.md`: This documentation file

## Troubleshooting

### Common Issues

1. **Port Already in Use**: If port 8890 is already in use, modify the port mapping in `docker-compose.yml`

2. **OpenAI API Errors**: 
   - Verify your API key is correctly set
   - Check your OpenAI account has sufficient credits
   - Ensure the API key has the necessary permissions

3. **Docker Issues**:
   - Ensure Docker is running
   - Try `docker-compose down` followed by `docker-compose up` to restart services

4. **Notebook Access**: If you can't access the notebook, check that all containers are running:
   ```bash
   docker-compose ps
   ```

### Getting Help

- Check the container logs: `docker-compose logs`
- Ensure all environment variables are properly set
- Verify your network connection for API calls

## Contributing

Feel free to contribute improvements, bug fixes, or additional features to this demo. Please follow the existing code structure and documentation patterns.

## License

This project is part of the Masaic AI Platform demonstrations and follows the same licensing terms. 
