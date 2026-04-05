# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 3.5.13 microservice for video-related functionality. Java 25 via Gradle toolchain. Uses PostgreSQL for persistence, Kafka for messaging, Thymeleaf for server-side rendering, and Actuator for health/metrics.

## Build Commands

```bash
./gradlew build              # Full build (compile + tests)
./gradlew bootRun            # Run the application
./gradlew test               # Run all tests
./gradlew test --tests 'com.tanvir.video.SomeTest'           # Run a specific test class
./gradlew test --tests 'com.tanvir.video.SomeTest.methodName' # Run a single test method
./gradlew clean build        # Clean rebuild
```

## Local Development

Docker Compose provides a local PostgreSQL instance (port 5432, db: `mydatabase`, user: `myuser`, password: `secret`). Spring Boot DevTools is configured for hot reload. Spring Boot Docker Compose support auto-manages the compose.yaml services on `bootRun`.

## Architecture

- **Package root:** `com.tanvir.video`
- **Entry point:** `VideoApplication.java` (`@SpringBootApplication`)
- **Config:** `src/main/resources/application.yaml`
- **Tests:** `src/test/java/com/tanvir/video/` — JUnit 5 via Spring Boot Test; Kafka test support available

## Key Dependencies

- **Spring Data JPA** — ORM/repository layer over PostgreSQL
- **Spring Kafka** — async message production/consumption
- **Thymeleaf** — server-side HTML templates in `src/main/resources/templates/`
- **Lombok** — annotation-driven boilerplate reduction (`@Data`, `@Builder`, etc.)
- **Spring Boot Actuator** — `/actuator/health` and metrics endpoints
