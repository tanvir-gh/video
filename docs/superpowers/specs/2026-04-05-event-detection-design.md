# Event Detection Pipeline — Hybrid Local Triage + LLM Classification

## Goal

Detect key soccer match events from live HLS streams in near real-time and produce 30-second highlight clips. The system runs as a Spring Boot server: users point it at a stream URL, watch processing progress live, and get playable clips seconds after each event is detected.

## Target Events (Top 5)

| Event | Weight | Primary signals |
|-------|--------|-----------------|
| Goal | 10 | Crowd roar, scoreboard change, replay sequence, commentator exclamation |
| Penalty | 9 | Distinct camera angle, commentator callout, crowd reaction |
| Red card | 8 | Referee gesture, graphic overlay, crowd reaction |
| Yellow card | 5 | Referee card graphic overlay, commentator mention |
| Key save / Shot on target | 4 | Crowd gasp/roar, camera movement, replay follows |

## Architecture Overview

Two-stage hybrid pipeline: fast local triage filters ~80% of uninteresting footage, LLM classifies only candidate windows.

```
┌─────────────────────────────────────────────────────┐
│ Docker Container                                    │
│                                                     │
│  apt: ffmpeg, tesseract-ocr, whisper (pip)          │
│  app: Spring Boot fat JAR                           │
│                                                     │
│  All external tools called via ProcessBuilder       │
│  LLM called via WebClient -> OpenRouter API         │
│  Prompts versioned as Java resource files            │
│                                                     │
│  ENV: OPENROUTER_API_KEY                            │
│  Volume: /app/samples/hls (output)                  │
└─────────────────────────────────────────────────────┘
```

All external tools (ffmpeg, tesseract, whisper) are invoked uniformly via `ProcessBuilder`. No Java native bindings. Same timeout handling, stderr capture, and error reporting for all.

## Pipeline Stages

### Stage 1: Stream Ingestor

**Responsibility:** Consume a live HLS stream and produce overlapping 30-second chunks.

**How it works:**
- ffmpeg reads the HLS stream URL continuously
- Outputs 30-second video chunks to a work directory, overlapping by 15 seconds (windows at 0-30s, 15-45s, 30-60s, ...)
- Also extracts a raw audio WAV per chunk for loudness analysis
- Runs as a long-lived ffmpeg process managed by `StreamProcessingService`

**ffmpeg approach:**
- `-f segment -segment_time 30` for chunking
- `-af` filter to extract audio in parallel
- Overlap achieved by running two staggered segment processes offset by 15 seconds

**Output per window:**
- `window_{n}.ts` — 30s video chunk
- `window_{n}.wav` — audio for loudness analysis

### Stage 2: Local Triage (TriageService)

**Responsibility:** Cheaply determine if a 30-second window might contain a key event. Fast, local, no API calls.

**Two parallel signals:**

1. **Audio energy (ffmpeg):** Run `ffmpeg -af loudnorm,astats` on the audio WAV. Extract peak and RMS levels. A spike above a configurable threshold (calibrated per-stream during the first 60 seconds as baseline) flags the window as a candidate. Crowd roars, commentator exclamations, and whistles all produce energy spikes.

2. **Scoreboard OCR (tesseract):** Extract 2 keyframes from the window (first and last second). Crop a configurable scoreboard region (top-left for most EPL broadcasts). Run `tesseract --psm 7` on the crop. Diff the OCR text between the two frames. Any change in detected digits = score change candidate.

**Triage decision:**
- Audio spike above threshold → candidate
- Scoreboard OCR diff → candidate (high priority — likely goal)
- Neither → skip window, log "quiet"

**Expected filter rate:** ~80% of windows skipped, ~20% sent to LLM.

### Stage 3: LLM Classifier (ClassifierService)

**Responsibility:** Classify candidate windows into specific event types with confidence scores.

**Inputs assembled per candidate window:**
1. **Whisper transcript:** Run `whisper --model base --language en --output_format json` on the 30s audio. Extract the text segments with timestamps.
2. **Keyframes:** Extract 3-4 evenly spaced frames from the 30s chunk via ffmpeg (`-vf fps=1/8` gives ~4 frames). If the model supports vision, encode as base64 image content. Otherwise, describe frames via OCR text and scene metadata only. Model selection should prefer vision-capable models when available.
3. **Scoreboard OCR delta:** The before/after OCR text from triage stage.
4. **Triage metadata:** Which signals triggered (audio spike level, OCR change detected).

**LLM call:**
- API: OpenRouter (`https://openrouter.ai/api/v1/chat/completions`)
- Model: `qwen/qwen3.5-flash-02-23` (fast, cheap) with `"reasoning": {"effort": "none"}`
- Request via Spring `WebClient` (non-blocking)
- Prompts stored as files in `src/main/resources/prompts/` and loaded at startup

**Prompt structure (versioned in `src/main/resources/prompts/classify_event.txt`):**

```
You are a soccer match event detector. Analyze this 30-second window from a live broadcast.

## Inputs
- Commentary transcript: {transcript}
- Scoreboard before: {ocr_before}
- Scoreboard after: {ocr_after}
- Audio energy level: {audio_rms} (baseline: {baseline_rms})
- Keyframes: [attached images]

## Task
Identify if any of these events occurred:
- Goal
- Penalty
- Red card
- Yellow card
- Key save / Shot on target

## Response format (JSON only)
{
  "events": [
    {"type": "goal", "confidence": 0.95, "description": "Home team scores, scoreboard changes from 0-1 to 1-1"}
  ]
}

If no key event occurred, return: {"events": []}
Do not guess. If uncertain, set confidence below 0.5.
```

**Decision threshold:** Events with confidence >= 0.7 are confirmed. Between 0.5-0.7, log as "possible" but don't clip. Below 0.5, discard.

### Stage 4: Clip Extractor (ClipExtractorService)

**Responsibility:** Produce a playable 30-second HLS clip for each confirmed event.

**How it works:**
- ffmpeg stream copy from the source (no re-encoding for video, AAC transcode for audio if needed — same approach as current extract_clips.py)
- `-map 0:v:0 -map 0:a:0 -c:v copy -c:a aac -b:a 128k`
- Output to `samples/hls/{stream_id}/{event_slug}/` with `master.m3u8`
- Write `event.json` alongside the clip with: event type, confidence, timestamp, transcript snippet, source stream

**Clip timing:** Center the 30-second clip on the detected event moment. If the event is at the edge of a window, use adjacent window data.

## Server Components

### StreamController (REST + SSE)

```
POST /api/streams          — Start processing a stream {url: "...m3u8"}
DELETE /api/streams/{id}   — Stop processing
GET /api/streams/{id}      — Status (running/stopped, events found, etc.)
GET /api/streams/{id}/events — SSE: live event stream
```

SSE event types pushed to the browser:
- `triage` — window analyzed, candidate or quiet (for the log)
- `classifying` — candidate sent to LLM (in-progress indicator)
- `event` — confirmed event with type, confidence, clip URL
- `error` — processing error
- `progress` — current position in stream

### StreamProcessingService

- Orchestrates the pipeline for a given stream
- Runs on virtual threads (`@Async` with virtual thread executor)
- Manages the long-lived ffmpeg ingestor process
- Feeds windows through triage → classifier → extractor
- Publishes SSE events via `SseEmitter`
- Tracks state (running, position, events found)

### Player UI Updates

Extend the existing `player.html`:
- Add a "Process Stream" input + button (enter HLS URL, click start)
- Live event log panel below the player (scrolling, color-coded by event type)
- Detected events appear in the sidebar with badges (goal icon, card icon, etc.)
- Clicking a detected event plays the 30-second clip
- Progress indicator showing current stream position

## Development Approach

### Using Existing Clips as Test Fixtures

The 10 extracted SoccerNet clips with ground truth serve as the development test bed:
- Run the pipeline against each clip's `master.m3u8` (treated as a "stream")
- Compare detected events against `ground_truth.json`
- Measure: precision (are detected events real?), recall (did we catch the events in ground truth?), latency (time from event to clip available)

### Evaluation Script

A Python evaluation script (`scripts/evaluate_detection.py`) that:
- Runs the pipeline against all 10 test clips
- Compares detections to ground truth with a configurable time tolerance (default: 15 seconds)
- Reports precision, recall, F1 per event type
- Reports average detection latency

This stays in Python because it's offline batch evaluation, not a server feature.

## Configuration

All in `application.yaml`:

```yaml
app:
  hls-dir: ${user.dir}/samples/hls
  detection:
    audio-threshold-multiplier: 2.0   # spike = N * baseline RMS
    ocr-scoreboard-region: "0,0,300,50"  # x,y,w,h crop for scoreboard
    confidence-threshold: 0.7
    window-duration: 30
    window-overlap: 15
    whisper-model: base
  openrouter:
    api-url: https://openrouter.ai/api/v1/chat/completions
    model: qwen/qwen3.5-flash-02-23
    # API key from OPENROUTER_API_KEY env var
```

## Dockerfile

```dockerfile
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    tesseract-ocr \
    python3 python3-pip \
    && pip3 install --break-system-packages openai-whisper \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY build/libs/video.jar /app/app.jar

ENV OPENROUTER_API_KEY=""
VOLUME /app/samples/hls

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Dependencies to Add

```gradle
// build.gradle additions
implementation 'org.springframework.boot:spring-boot-starter-webflux'  // WebClient for OpenRouter
```

No other new dependencies. Everything else is ProcessBuilder + existing Spring Web.

## Out of Scope (for now)

- Multiple simultaneous streams
- Persistent storage of events (in-memory only for now)
- User authentication
- Custom scoreboard region detection (manual config for now)
- Non-English commentary support
