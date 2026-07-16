# syntax=docker/dockerfile:1

# ---- Build stage: compile and package the Spring Boot jar with the bundled Maven wrapper ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the wrapper and pom first so dependency resolution is cached across source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests run in CI, not in the image build.
RUN ./mvnw -B -q -DskipTests clean package \
    && cp target/agent-backend-demo-*.jar app.jar

# ---- Runtime stage: minimal JRE, non-root user, healthcheck ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl is used by the container HEALTHCHECK; install and clean up in one layer.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 appgroup \
    && useradd --system --uid 10001 --gid appgroup --home /app appuser

COPY --from=build /workspace/app.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser
EXPOSE 8080

# Public liveness probe; returns UP/DOWN only.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/healthz || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
