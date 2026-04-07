# Multi-stage build: compile jar, then assemble runtime image

# ---- Stage 1: Build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Copy gradle wrapper and build scripts first for layer caching
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle

# Pre-fetch dependencies (this layer is cached unless build.gradle changes)
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Copy sources and build
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:25-jre AS runtime

# Install only what the pipeline actually uses at runtime
# ffmpeg: segmentation + keyframe extraction
# tesseract-ocr: legacy triage (unused in current pipeline but small)
# Whisper is NOT installed — it's disabled by default. Enable via
# a separate --whisper variant image if needed.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Runtime config: ollama URL defaults to host.docker.internal so the
# container can reach an ollama instance running on the host machine.
# Override with APP_OLLAMA_URL env var if needed.
ENV APP_OLLAMA_URL="http://host.docker.internal:11434/api/chat"

# Persistent volumes: HLS output (served clips) and work dir
# (temp segments + SQLite benchmarks.db)
VOLUME ["/app/samples/hls", "/app/work"]

# Copy fat jar from build stage
COPY --from=build /src/build/libs/video-*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
