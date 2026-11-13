# syntax=docker/dockerfile:1
# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
# cache dependencies in their own layer
RUN mvn -B -q dependency:go-offline
COPY src ./src
# tests need Docker (Testcontainers); they are run by the CI / developer, not during the image build
RUN mvn -B -q -DskipTests package && cp target/NexoraFlow-*.jar /workspace/app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home nexora
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
RUN mkdir -p /app/data/docs /app/docs && chown -R nexora:nexora /app
USER nexora
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC" \
    NEXORA_DATA_DIR=/app/data \
    NEXORA_DOCS_DIR=/app/docs
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
