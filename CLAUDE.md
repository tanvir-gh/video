# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.5.13 app for near real-time soccer match event detection from HLS video streams. Java 25 via Gradle toolchain. Uses vision-capable LLM (ollama qwen3.5:9b) to classify keyframes from 30-second windows. Produces 30-second highlight clips for detected events (goals, penalties, cards, key saves).

Player UI (Bootstrap 5 + hls.js) allows clicking a clip to play AND process it simultaneously, with live event log via SSE.

JPA, Kafka, and Docker Compose are in `build.gradle` as scaffolding for future use but are currently excluded via `@SpringBootApplication(exclude = ...)`.

## Build & Run

```bash
./gradlew build              # Full build (compile + unit tests, ~10s)
./gradlew bootRun            # Web mode at http://localhost:8080
./gradlew test               # Unit tests only (no ffmpeg/ollama needed)
./gradlew test -PincludeTags=integration  # Integration tests (needs ffmpeg+ollama)
./gradlew clean build        # Clean rebuild

# CLI mode — process a single clip directly (benchmark)
./gradlew bootRun --args="--pipeline.input=samples/hls/01_goal_2_arsenal/master.m3u8 --spring.main.web-application-type=none"

# Override config on CLI
./gradlew bootRun --args="--pipeline.input=... --app.detection.keyframe-count=5 --app.ollama.model=qwen3.5:4b"
```

## Architecture 1: k8s Job per stream (production deployment)

Each live stream runs in its own dedicated Kubernetes Job. Detected events
are published to a Kafka topic (`detected-events`) with deterministic event
IDs. On pod restart, the new pod reads the topic to find already-published
events and skips duplicates (idempotent writer pattern).

```bash
# 1. Start local stack (Kafka)
docker compose up -d kafka

# 2. Create kind cluster, install image
kind create cluster --config k8s/kind-config.yaml
./gradlew bootJar && docker build -t video-detector:latest .
kind load docker-image video-detector:latest --name video-detector

# 3. Start the launcher API on the host
./gradlew bootRun --args="--spring.main.web-application-type=servlet"

# 4. Launch a stream as a k8s Job
curl -s -X POST http://localhost:8080/api/streams/launch \
  -H "Content-Type: application/json" \
  -d '{"url":"samples/hls/01_goal_2_arsenal/master.m3u8"}'

# 5. Watch progress
~/.local/bin/kubectl get jobs,pods
~/.local/bin/kubectl logs -f pod/stream-<sessionId>-<random>

# 6. Inspect detected events in Kafka
docker exec video-kafka-1 kafka-console-consumer \
  --bootstrap-server 172.18.0.1:9092 --topic detected-events \
  --from-beginning --timeout-ms 5000

# 7. Stop a running stream
curl -s -X DELETE http://localhost:8080/api/streams/launch/<sessionId>
```

**Recovery on failure:** when a Job's pod crashes, k8s creates a new pod
with the same `STREAM_SESSION_ID` env var. The new pod queries Kafka for
events already published with that sessionId, builds a dedup set, and skips
re-publishing them. Deterministic event IDs (`{sessionId}-w{N}-{type}`) make
this safe.

## Benchmarking (REQUIRED for any pipeline change)

**Every change to detection logic, prompts, model, or config MUST be benchmarked.**
Results are persisted to PostgreSQL `benchmark_runs` table automatically.

```bash
# Benchmark a single clip (TP/FP/FN vs ground truth, persisted to DB)
./gradlew bootRun --args="--pipeline.input=samples/hls/01_goal_2_arsenal/master.m3u8 --spring.main.web-application-type=none"

# Benchmark all clips
./gradlew bootRun --args="--pipeline.benchmark --spring.main.web-application-type=none"

# Query benchmark history (SQLite file in work/benchmarks.db)
sqlite3 work/benchmarks.db \
  "SELECT id, clip_name, model, keyframe_count, keyframe_width, tp, fp, fn, ROUND(precision_score, 2) AS p, ROUND(recall_score, 2) AS r, ROUND(f1_score, 2) AS f1, ROUND(total_time_sec, 1) AS time_s, git_sha FROM benchmark_runs ORDER BY run_at DESC LIMIT 20;"

# Best F1 per clip
sqlite3 work/benchmarks.db \
  "SELECT clip_name, MAX(f1_score) AS best_f1 FROM benchmark_runs GROUP BY clip_name;"
```

**Workflow rule:** Before claiming a change improved the pipeline, run a benchmark
and verify the new row in `benchmark_runs` shows the improvement vs the previous run.

## Prerequisites

- `ffmpeg` and `ffprobe` on PATH
- `ollama` running with `qwen3.5:9b` model pulled

## Docker

Multi-stage Dockerfile builds a lean runtime image with Java 25 JRE and ffmpeg.
Ollama runs on the host (reached via `host.docker.internal:11434`).

```bash
# Start the video container
docker compose up -d

# Tail logs
docker compose logs -f

# Stop
docker compose down

# Build the image only (without running)
docker build -t video-detector:latest .
```

Ollama must be running on the host (the container reaches it via
`host.docker.internal:11434`). SQLite benchmark DB lives in the
`video_work` volume inside the container.

## SoccerNet Pipeline (offline batch)

Python scripts in `scripts/` for sourcing real match footage:

```bash
python scripts/analyze_events.py --top 10    # Find densest 10-min event windows
python scripts/extract_clips.py              # Extract selected windows as HLS clips
python scripts/extract_clips.py --clean      # Remove extracted clips (keeps test videos)
python scripts/download_soccernet.py         # Download games (needs SOCCERNET_PASSWORD in .env)
```

## Architecture

```
com.tanvir.video/
  VideoApplication.java           — entry point, @ConfigurationPropertiesScan
  PipelineRunner.java             — CLI mode (--pipeline.input=path)
  config/
    AsyncConfig.java              — virtual thread executor for async pipeline
    DetectionProperties.java      — detection config (window size, keyframes, threshold)
    OllamaProperties.java         — ollama config (url, model, tokens, temperature)
    WebConfig.java                — HLS MIME types + resource handler
  controller/
    PlayerController.java         — serves player page, scans for master.m3u8 dirs
    StreamController.java         — REST (start/stop/status) + SSE live events
  service/
    ClassifierService.java        — keyframe extraction + ollama vision classification
    ClipExtractorService.java     — ffmpeg re-encode for 30s event clips
    StreamProcessingService.java  — pipeline orchestrator (segment → classify → clip)
  model/
    StreamSession.java            — session state (thread-safe counters)
    DetectedEvent.java            — event record (type, confidence, timestamp, clip)
    ClassificationResult.java     — LLM response record
    BenchmarkRun.java             — persisted benchmark row (JPA entity)
```

## Event Detection Pipeline

1. **Segment** — ffmpeg splits stream into 30s `.ts` windows
2. **Classify** — 10 keyframes (768px) per window extracted in a single ffmpeg pass,
   sent to ollama qwen3.5:9b vision with context from prior windows
3. **Clip** — confirmed events (confidence >= 0.5) get 30s HLS clips via ffmpeg re-encode

Every window goes through the LLM. ~17-20s per window at 768px = comfortably real-time.

## Configuration (application.yaml)

```yaml
app:
  hls-dir: ${user.dir}/samples/hls     # where clips are served from
  detection:
    window-duration: 30                  # seconds per analysis window
    confidence-threshold: 0.5            # minimum confidence to create clip
    keyframe-count: 10                   # frames per window
    keyframe-width: 768                  # px (512 too small for scoreboard)
    work-dir: ${user.dir}/work           # temp files + SQLite benchmarks.db
  ollama:
    url: http://localhost:11434/api/chat
    model: qwen3.5:9b                    # vision-capable
    max-tokens: 500
    temperature: 0.1
```

## Key Dependencies

- **Spring Web + WebFlux** — REST endpoints + WebClient for ollama API
- **Thymeleaf + Bootstrap 5 + hls.js** — player UI with live event log
- **ollama** — local LLM inference (qwen3.5:9b with vision)
- **ffmpeg** — video segmentation, keyframe extraction, clip creation
- **Lombok** — annotation-driven boilerplate reduction
- **Spring Boot Actuator** — `/actuator/health` and metrics
