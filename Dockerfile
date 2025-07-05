# Stage 1: Get the Docker CLI from the official Docker image
FROM docker:20.10 as docker-cli

# Stage 2: Build your app image based on amazoncorretto
FROM bellsoft/liberica-openjre-alpine-musl:21

RUN addgroup -S app && adduser -S app -G app
USER app

WORKDIR /app

# Copy the built JAR and configuration file into the image
COPY open-responses-server/build/libs/openresponses-0.3.4.jar /app/openresponses-0.3.4.jar
COPY open-responses-server/src/main/resources/mcp-servers-config.json /app/mcp-servers-config.json

# Start the Java application directly
ENTRYPOINT ["java", "-jar", "/app/openresponses-0.3.4.jar"]
