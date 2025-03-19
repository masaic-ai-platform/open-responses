FROM eclipse-temurin:21-jdk-alpine as build

WORKDIR /workspace/app

COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY src src

RUN ./gradlew build -x test
RUN mkdir -p build/dependency && (cd build/dependency; jar -xf ../libs/*.jar)

FROM eclipse-temurin:21-jre-alpine

VOLUME /tmp

ARG DEPENDENCY=/workspace/app/build/dependency

# Copy project dependencies
COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app

ENV OPENAI_API_BASE_URL=""

# Expose the application port
EXPOSE ${SERVER_PORT}

ENTRYPOINT ["java", "-cp", "app:app/lib/*", "com.masaic.openai.OpenaiApplicationKt"] 