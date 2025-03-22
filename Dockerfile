# Stage 1: Get the Docker CLI from the official Docker image
FROM docker:20.10 as docker-cli

# Stage 2: Build your app image based on amazoncorretto
FROM amazoncorretto:21

WORKDIR /app

# Copy the built JAR and config
COPY build/libs/openai-0.0.1-SNAPSHOT.jar /app/openai-0.0.1-SNAPSHOT.jar
COPY src/main/resources/mcp-servers-config.json /app/mcp-servers-config.json

# Copy the Docker CLI from the previous stage
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker

# (Optional) Make sure docker is executable
RUN chmod +x /usr/local/bin/docker

# Copy the entrypoint script into the container
COPY run_service.sh /app/run_service.sh
RUN chmod +x /app/run_service.sh

# Set the entry point
ENTRYPOINT ["/app/run_service.sh"]