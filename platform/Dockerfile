# Stage 1: Build your app image based on amazoncorretto
FROM bellsoft/liberica-openjre-alpine-musl:21

RUN addgroup -S app && adduser -S app -G app
USER app

WORKDIR /app

# Copy the built JAR and configuration file into the image
COPY open-responses-server/build/libs/openresponses-0.4.7.jar /app/openresponses-0.4.7.jar
COPY open-responses-server/src/main/resources/mcp-servers-config.json /app/mcp-servers-config.json

EXPOSE 6644
# Start the Java application directly
ENTRYPOINT ["java", "-jar", "/app/openresponses-0.4.7.jar"]
