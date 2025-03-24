#!/bin/sh
# Pull the required images from Docker Hub
docker pull mcp/github
docker pull mcp/brave-search

# Start the Java application
exec java -jar /app/openai-0.0.1-SNAPSHOT.jar