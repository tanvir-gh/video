# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.5.13 HLS video streaming app. Java 25 via Gradle toolchain. Serves HLS video clips via Thymeleaf player UI with hls.js. Python scripts handle SoccerNet match data processing (event analysis, clip extraction).

JPA, Kafka, and Docker Compose are in `build.gradle` as scaffolding for future use but are currently excluded via `@SpringBootApplication(exclude = ...)`.

## Build Commands

```bash
./gradlew build              # Full build (compile + tests)
./gradlew bootRun            # Run the application (serves at http://localhost:8080)
./gradlew test               # Run all tests
./gradlew test --tests 'com.tanvir.video.SomeTest'           # Run a specific test class
./gradlew test --tests 'com.tanvir.video.SomeTest.methodName' # Run a single test method
./gradlew clean build        # Clean rebuild
```

## SoccerNet Pipeline

Python scripts in `scripts/` for sourcing real match footage:

```bash
python scripts/analyze_events.py --top 10    # Find densest 10-min event windows
python scripts/extract_clips.py              # Extract selected windows as HLS clips
python scripts/extract_clips.py --clean      # Remove extracted clips (keeps test videos)
python scripts/download_soccernet.py         # Download games from SoccerNet (needs SOCCERNET_PASSWORD in .env)
```

Clips use ffmpeg stream copy (`-c copy`) — no re-encoding. Requires `ffmpeg` and `ffprobe` on PATH.

## Architecture

- **Package root:** `com.tanvir.video`
- **Entry point:** `VideoApplication.java` (`@SpringBootApplication`)
- **Config:** `src/main/resources/application.yaml` — `app.hls-dir` controls HLS content path
- **HLS serving:** `WebConfig.java` (resource handler + MIME types), `PlayerController.java` (scans for `master.m3u8` dirs)
- **Player:** `templates/player.html` — hls.js with ABR quality indicator
- **Tests:** `src/test/java/com/tanvir/video/` — JUnit 5 via Spring Boot Test

## Key Dependencies

- **Spring Web + Thymeleaf** — serves HLS content and player UI
- **hls.js** (CDN) — browser-side HLS player with adaptive bitrate
- **Lombok** — annotation-driven boilerplate reduction (`@Data`, `@Builder`, etc.)
- **Spring Boot Actuator** — `/actuator/health` and metrics endpoints
