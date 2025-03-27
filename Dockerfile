# Stage 1: Get the Docker CLI from the official Docker image
FROM docker:20.10 as docker-cli

# Stage 2: Build your app image based on amazoncorretto
FROM amazoncorretto:21

WORKDIR /app

# Copy the built JAR and configuration file into the image
COPY build/libs/openresponses-0.0.1-alpha.jar /app/openresponses-0.0.1-alpha.jar
COPY src/main/resources/mcp-servers-config.json /app/mcp-servers-config.json

# Copy the Docker CLI from the previous stage
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker

# Ensure docker is executable
RUN chmod +x /usr/local/bin/docker

# Start the Java application directly
ENTRYPOINT ["java", "-jar", "/app/openresponses-0.0.1-alpha.jar"]
