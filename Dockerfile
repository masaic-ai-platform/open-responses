# Stage 1: Get the Docker CLI from the official Docker image
FROM docker:20.10 as docker-cli

# Stage 2: Build your app image based on amazoncorretto
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Install dependencies required for ONNX runtime
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    libc6 libstdc++6 \
    && rm -rf /var/lib/apt/lists/*

# Copy the built JAR and configuration file into the image
COPY build/libs/openresponses-0.1.1-M2.jar /app/openresponses-0.1.1-M2.jar
COPY src/main/resources/mcp-servers-config.json /app/mcp-servers-config.json

# Copy the Docker CLI from the previous stage
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker

# Ensure docker is executable
RUN chmod +x /usr/local/bin/docker

# Start the Java application directly
ENTRYPOINT ["java", "-jar", "/app/openresponses-0.1.1-M2.jar"]
