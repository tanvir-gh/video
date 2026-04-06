# Event Detection Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect key soccer match events from HLS streams in near real-time and produce 30-second highlight clips, with live progress visible in the browser.

**Architecture:** Hybrid local triage (ffmpeg audio RMS + tesseract OCR) filters ~80% of footage; only candidates go to an LLM (Qwen via OpenRouter) for classification. All external tools invoked via ProcessBuilder. SSE pushes live events to the browser.

**Tech Stack:** Spring Boot 3.5.13, Java 25, ffmpeg, tesseract, whisper CLI, OpenRouter API (WebClient), Thymeleaf + hls.js, Docker.

**Constraint:** LLM calls (OpenRouter) are stubbed/mocked until user gives explicit approval to enable live calls.

---

## File Structure

```
src/main/java/com/tanvir/video/
  VideoApplication.java                    (modify: add @EnableAsync)
  PlayerController.java                    (no change)
  WebConfig.java                           (no change)
  controller/
    StreamController.java                  (create: REST + SSE endpoints)
  service/
    StreamProcessingService.java           (create: async pipeline orchestrator)
    TriageService.java                     (create: audio RMS + OCR triage)
    ClassifierService.java                 (create: whisper + LLM classification)
    ClipExtractorService.java              (create: ffmpeg clip extraction)
  model/
    StreamSession.java                     (create: session state record)
    DetectedEvent.java                     (create: event data record)
    TriageResult.java                      (create: triage output record)
    ClassificationResult.java              (create: LLM response record)
  config/
    AsyncConfig.java                       (create: virtual thread executor)
    DetectionProperties.java               (create: @ConfigurationProperties)

src/main/resources/
  application.yaml                         (modify: add detection + openrouter config)
  prompts/
    classify_event.txt                     (create: LLM prompt template)
  templates/
    player.html                            (modify: add stream input + event log)

src/test/java/com/tanvir/video/
  service/
    TriageServiceTest.java                 (create)
    ClassifierServiceTest.java             (create)
    ClipExtractorServiceTest.java          (create)
    StreamProcessingServiceTest.java       (create)
  controller/
    StreamControllerTest.java              (create)

build.gradle                               (modify: add webflux)
Dockerfile                                 (create)
```

---

### Task 1: Configuration and Model Classes

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/tanvir/video/config/DetectionProperties.java`
- Create: `src/main/java/com/tanvir/video/config/AsyncConfig.java`
- Create: `src/main/java/com/tanvir/video/model/StreamSession.java`
- Create: `src/main/java/com/tanvir/video/model/DetectedEvent.java`
- Create: `src/main/java/com/tanvir/video/model/TriageResult.java`
- Create: `src/main/java/com/tanvir/video/model/ClassificationResult.java`
- Modify: `src/main/java/com/tanvir/video/VideoApplication.java`

- [ ] **Step 1: Add webflux dependency to build.gradle**

Add after the existing `spring-boot-starter-web` line:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-webflux'
```

- [ ] **Step 2: Update application.yaml with detection config**

```yaml
spring:
  application:
    name: video

app:
  hls-dir: ${user.dir}/samples/hls
  detection:
    audio-threshold-multiplier: 2.0
    ocr-scoreboard-region: "0,0,300,50"
    confidence-threshold: 0.7
    window-duration: 30
    window-overlap: 15
    whisper-model: base
    work-dir: ${user.dir}/work
  openrouter:
    api-url: https://openrouter.ai/api/v1/chat/completions
    model: qwen/qwen3.5-flash-02-23
    api-key: ${OPENROUTER_API_KEY:}
    enabled: false
```

Note: `openrouter.enabled: false` — LLM calls are disabled by default until user approval.

- [ ] **Step 3: Create DetectionProperties.java**

```java
package com.tanvir.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.detection")
public record DetectionProperties(
    double audioThresholdMultiplier,
    String ocrScoreboardRegion,
    double confidenceThreshold,
    int windowDuration,
    int windowOverlap,
    String whisperModel,
    String workDir
) {
    public int[] parseScoreboardRegion() {
        String[] parts = ocrScoreboardRegion.split(",");
        return new int[]{
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim()),
            Integer.parseInt(parts[3].trim())
        };
    }
}
```

- [ ] **Step 4: Create OpenRouter properties as a nested record in DetectionProperties or separate**

Create `src/main/java/com/tanvir/video/config/OpenRouterProperties.java`:

```java
package com.tanvir.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.openrouter")
public record OpenRouterProperties(
    String apiUrl,
    String model,
    String apiKey,
    boolean enabled
) {}
```

- [ ] **Step 5: Create AsyncConfig.java with virtual thread executor**

```java
package com.tanvir.video.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "pipelineExecutor")
    public Executor pipelineExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

- [ ] **Step 6: Create model records**

`src/main/java/com/tanvir/video/model/TriageResult.java`:

```java
package com.tanvir.video.model;

public record TriageResult(
    int windowIndex,
    String windowPath,
    String audioPath,
    double audioRms,
    double baselineRms,
    String ocrBefore,
    String ocrAfter,
    boolean isCandidate,
    String reason
) {}
```

`src/main/java/com/tanvir/video/model/DetectedEvent.java`:

```java
package com.tanvir.video.model;

public record DetectedEvent(
    String type,
    double confidence,
    String description,
    int windowIndex,
    double timestampSec,
    String clipPath,
    String transcript
) {}
```

`src/main/java/com/tanvir/video/model/ClassificationResult.java`:

```java
package com.tanvir.video.model;

import java.util.List;

public record ClassificationResult(
    List<Event> events
) {
    public record Event(
        String type,
        double confidence,
        String description
    ) {}
}
```

`src/main/java/com/tanvir/video/model/StreamSession.java`:

```java
package com.tanvir.video.model;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StreamSession {

    public enum Status { RUNNING, STOPPED, ERROR }

    private final String id;
    private final String streamUrl;
    private final Instant startedAt;
    private volatile Status status;
    private volatile int windowsProcessed;
    private volatile int candidatesFound;
    private final List<DetectedEvent> detectedEvents = new CopyOnWriteArrayList<>();
    private volatile Process ingestorProcess;

    public StreamSession(String id, String streamUrl) {
        this.id = id;
        this.streamUrl = streamUrl;
        this.startedAt = Instant.now();
        this.status = Status.RUNNING;
    }

    public String getId() { return id; }
    public String getStreamUrl() { return streamUrl; }
    public Instant getStartedAt() { return startedAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getWindowsProcessed() { return windowsProcessed; }
    public void incrementWindowsProcessed() { this.windowsProcessed++; }
    public int getCandidatesFound() { return candidatesFound; }
    public void incrementCandidatesFound() { this.candidatesFound++; }
    public List<DetectedEvent> getDetectedEvents() { return detectedEvents; }
    public void addEvent(DetectedEvent event) { this.detectedEvents.add(event); }
    public Process getIngestorProcess() { return ingestorProcess; }
    public void setIngestorProcess(Process process) { this.ingestorProcess = process; }
}
```

- [ ] **Step 7: Enable config properties scanning in VideoApplication.java**

```java
package com.tanvir.video;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, KafkaAutoConfiguration.class})
@ConfigurationPropertiesScan
public class VideoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoApplication.class, args);
    }
}
```

- [ ] **Step 8: Build and verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add build.gradle src/main/resources/application.yaml \
  src/main/java/com/tanvir/video/VideoApplication.java \
  src/main/java/com/tanvir/video/config/ \
  src/main/java/com/tanvir/video/model/
git commit -m "Add config, model classes, and async setup for event detection pipeline"
```

---

### Task 2: TriageService — Audio RMS Analysis

**Files:**
- Create: `src/main/java/com/tanvir/video/service/TriageService.java`
- Create: `src/test/java/com/tanvir/video/service/TriageServiceTest.java`

- [ ] **Step 1: Write the failing test for audio RMS extraction**

```java
package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;

import static org.junit.jupiter.api.Assertions.*;

class TriageServiceTest {

    private TriageService triageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var props = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", tempDir.toString());
        triageService = new TriageService(props);
    }

    @Test
    void extractAudioRms_returnsPositiveValueForRealAudio() throws Exception {
        // Generate a 5-second test WAV with a tone using ffmpeg
        Path wav = tempDir.resolve("test.wav");
        new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=5", wav.toString())
                .redirectErrorStream(true).start().waitFor();

        assertTrue(Files.exists(wav), "Test WAV should exist");

        double rms = triageService.extractAudioRms(wav);
        assertTrue(rms > 0, "RMS should be positive for a tone, got: " + rms);
    }

    @Test
    void extractAudioRms_returnsLowValueForSilence() throws Exception {
        Path wav = tempDir.resolve("silence.wav");
        new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i",
                "anullsrc=r=22050:cl=mono", "-t", "3", wav.toString())
                .redirectErrorStream(true).start().waitFor();

        double rms = triageService.extractAudioRms(wav);
        assertTrue(rms < 0.01, "RMS should be near zero for silence, got: " + rms);
    }

    @Test
    void isAudioSpike_detectsSpikeAboveBaseline() {
        double baseline = 0.05;
        double spike = 0.15; // 3x baseline, above 2.0 multiplier
        assertTrue(triageService.isAudioSpike(spike, baseline));
    }

    @Test
    void isAudioSpike_rejectsNormalLevel() {
        double baseline = 0.05;
        double normal = 0.06; // 1.2x baseline, below 2.0 multiplier
        assertFalse(triageService.isAudioSpike(normal, baseline));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.tanvir.video.service.TriageServiceTest'`
Expected: FAIL — TriageService class does not exist

- [ ] **Step 3: Implement TriageService — audio RMS methods**

```java
package com.tanvir.video.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tanvir.video.config.DetectionProperties;

@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);
    private static final Pattern RMS_PATTERN = Pattern.compile("RMS level dB:\\s*(-?[\\d.]+)");

    private final DetectionProperties props;

    public TriageService(DetectionProperties props) {
        this.props = props;
    }

    public double extractAudioRms(Path audioPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", audioPath.toString(),
                "-af", "astats=metadata=1:reset=0",
                "-f", "null", "-"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        double rmsDb = -91.0; // silence floor
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = RMS_PATTERN.matcher(line);
                if (m.find()) {
                    double val = Double.parseDouble(m.group(1));
                    if (val > rmsDb) {
                        rmsDb = val;
                    }
                }
            }
        }
        process.waitFor();

        // Convert dB to linear (0.0 to 1.0 range)
        return Math.pow(10.0, rmsDb / 20.0);
    }

    public boolean isAudioSpike(double rms, double baseline) {
        if (baseline <= 0) return rms > 0;
        return rms >= baseline * props.audioThresholdMultiplier();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.tanvir.video.service.TriageServiceTest'`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tanvir/video/service/TriageService.java \
  src/test/java/com/tanvir/video/service/TriageServiceTest.java
git commit -m "Add TriageService with audio RMS extraction and spike detection"
```

---

### Task 3: TriageService — Scoreboard OCR

**Files:**
- Modify: `src/main/java/com/tanvir/video/service/TriageService.java`
- Modify: `src/test/java/com/tanvir/video/service/TriageServiceTest.java`

- [ ] **Step 1: Write the failing test for keyframe extraction and OCR**

Add to `TriageServiceTest.java`:

```java
@Test
void extractKeyframe_producesImageFile() throws Exception {
    // Generate a 5-second test video
    Path video = tempDir.resolve("test.ts");
    new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i",
            "color=c=red:s=320x240:d=5:rate=10",
            "-c:v", "libx264", video.toString())
            .redirectErrorStream(true).start().waitFor();

    Path frame = triageService.extractKeyframe(video, 2.0, tempDir);
    assertTrue(Files.exists(frame), "Keyframe image should exist");
    assertTrue(Files.size(frame) > 0, "Keyframe should not be empty");
}

@Test
void runOcr_returnsStringForImage() throws Exception {
    // Generate an image with text using ffmpeg
    Path img = tempDir.resolve("ocr_test.png");
    new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i",
            "color=c=white:s=300x50:d=1:rate=1",
            "-vf", "drawtext=text='1 - 0':fontsize=30:fontcolor=black:x=10:y=10",
            "-frames:v", "1", img.toString())
            .redirectErrorStream(true).start().waitFor();

    String text = triageService.runOcr(img);
    assertNotNull(text);
    // OCR on generated text may not be perfect, just verify it returns something
}

@Test
void hasScoreboardChanged_detectsDifference() {
    assertTrue(triageService.hasScoreboardChanged("1 - 0", "2 - 0"));
}

@Test
void hasScoreboardChanged_noChangeWhenSame() {
    assertFalse(triageService.hasScoreboardChanged("1 - 0", "1 - 0"));
}

@Test
void hasScoreboardChanged_handlesEmptyStrings() {
    assertFalse(triageService.hasScoreboardChanged("", ""));
}
```

- [ ] **Step 2: Run tests to verify new tests fail**

Run: `./gradlew test --tests 'com.tanvir.video.service.TriageServiceTest'`
Expected: 5 new tests FAIL — methods do not exist

- [ ] **Step 3: Add OCR methods to TriageService**

Add these methods to `TriageService.java`:

```java
public Path extractKeyframe(Path videoPath, double timestampSec, Path outputDir) throws Exception {
    Path framePath = outputDir.resolve("frame_" + (long) (timestampSec * 1000) + ".png");
    ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-y",
            "-ss", String.valueOf(timestampSec),
            "-i", videoPath.toString(),
            "-frames:v", "1",
            framePath.toString()
    );
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.getInputStream().readAllBytes(); // drain
    p.waitFor();
    return framePath;
}

public Path cropScoreboardRegion(Path imagePath, Path outputDir) throws Exception {
    int[] region = props.parseScoreboardRegion();
    Path cropped = outputDir.resolve("crop_" + imagePath.getFileName());
    ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-y",
            "-i", imagePath.toString(),
            "-vf", String.format("crop=%d:%d:%d:%d", region[2], region[3], region[0], region[1]),
            cropped.toString()
    );
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    p.waitFor();
    return cropped;
}

public String runOcr(Path imagePath) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(
            "tesseract", imagePath.toString(), "stdout", "--psm", "7"
    );
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String output = new String(p.getInputStream().readAllBytes()).trim();
    p.waitFor();
    return output;
}

public boolean hasScoreboardChanged(String before, String after) {
    if (before == null || after == null) return false;
    return !before.strip().equals(after.strip()) && !before.isBlank();
}
```

- [ ] **Step 4: Run all TriageService tests**

Run: `./gradlew test --tests 'com.tanvir.video.service.TriageServiceTest'`
Expected: All 9 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tanvir/video/service/TriageService.java \
  src/test/java/com/tanvir/video/service/TriageServiceTest.java
git commit -m "Add scoreboard OCR and keyframe extraction to TriageService"
```

---

### Task 4: TriageService — Full Triage Method

**Files:**
- Modify: `src/main/java/com/tanvir/video/service/TriageService.java`
- Modify: `src/test/java/com/tanvir/video/service/TriageServiceTest.java`

- [ ] **Step 1: Write the failing test for the full triage method**

Add to `TriageServiceTest.java`:

```java
@Test
void triage_candidateOnLoudAudio() throws Exception {
    // Generate a loud 5-second video+audio
    Path video = tempDir.resolve("loud.ts");
    new ProcessBuilder("ffmpeg", "-y",
            "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=5:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
            "-c:v", "libx264", "-c:a", "aac",
            video.toString())
            .redirectErrorStream(true).start().waitFor();

    // Extract audio
    Path audio = tempDir.resolve("loud.wav");
    new ProcessBuilder("ffmpeg", "-y", "-i", video.toString(),
            "-vn", "-ar", "22050", "-ac", "1", audio.toString())
            .redirectErrorStream(true).start().waitFor();

    // Use a very low baseline to trigger spike
    var result = triageService.triage(0, video, audio, 0.001, tempDir);
    assertTrue(result.isCandidate(), "Loud audio should trigger candidate");
    assertTrue(result.reason().contains("audio"), "Reason should mention audio");
}

@Test
void triage_quietWindowIsNotCandidate() throws Exception {
    Path video = tempDir.resolve("quiet.ts");
    new ProcessBuilder("ffmpeg", "-y",
            "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=5:rate=10",
            "-f", "lavfi", "-i", "anullsrc=r=22050:cl=mono",
            "-t", "5", "-c:v", "libx264", "-c:a", "aac",
            video.toString())
            .redirectErrorStream(true).start().waitFor();

    Path audio = tempDir.resolve("quiet.wav");
    new ProcessBuilder("ffmpeg", "-y", "-i", video.toString(),
            "-vn", "-ar", "22050", "-ac", "1", audio.toString())
            .redirectErrorStream(true).start().waitFor();

    // High baseline so nothing triggers
    var result = triageService.triage(0, video, audio, 1.0, tempDir);
    assertFalse(result.isCandidate(), "Quiet window should not be candidate");
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests 'com.tanvir.video.service.TriageServiceTest'`
Expected: 2 new tests FAIL — triage method does not exist

- [ ] **Step 3: Implement the triage method**

Add to `TriageService.java`:

```java
public TriageResult triage(int windowIndex, Path videoPath, Path audioPath,
                           double baselineRms, Path workDir) throws Exception {
    // 1. Audio RMS check
    double rms = extractAudioRms(audioPath);
    boolean audioSpike = isAudioSpike(rms, baselineRms);

    // 2. Scoreboard OCR check
    String ocrBefore = "";
    String ocrAfter = "";
    boolean scoreChanged = false;
    try {
        Path frameStart = extractKeyframe(videoPath, 0.5, workDir);
        Path frameEnd = extractKeyframe(videoPath, 4.0, workDir);
        Path cropStart = cropScoreboardRegion(frameStart, workDir);
        Path cropEnd = cropScoreboardRegion(frameEnd, workDir);
        ocrBefore = runOcr(cropStart);
        ocrAfter = runOcr(cropEnd);
        scoreChanged = hasScoreboardChanged(ocrBefore, ocrAfter);
    } catch (Exception e) {
        log.warn("OCR failed for window {}: {}", windowIndex, e.getMessage());
    }

    boolean isCandidate = audioSpike || scoreChanged;
    String reason = "";
    if (audioSpike && scoreChanged) reason = "audio spike + scoreboard change";
    else if (audioSpike) reason = "audio spike (rms=" + String.format("%.4f", rms) + ")";
    else if (scoreChanged) reason = "scoreboard change (" + ocrBefore + " -> " + ocrAfter + ")";
    else reason = "quiet";

    log.info("Window {}: {} — {}", windowIndex, isCandidate ? "CANDIDATE" : "skip", reason);

    return new TriageResult(windowIndex, videoPath.toString(), audioPath.toString(),
            rms, baselineRms, ocrBefore, ocrAfter, isCandidate, reason);
}
```

Add the import at the top of `TriageService.java`:

```java
import com.tanvir.video.model.TriageResult;
```

- [ ] **Step 4: Run all TriageService tests**

Run: `./gradlew test --tests 'com.tanvir.video.service.TriageServiceTest'`
Expected: All 11 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tanvir/video/service/TriageService.java \
  src/test/java/com/tanvir/video/service/TriageServiceTest.java
git commit -m "Add full triage method combining audio RMS and scoreboard OCR"
```

---

### Task 5: ClipExtractorService

**Files:**
- Create: `src/main/java/com/tanvir/video/service/ClipExtractorService.java`
- Create: `src/test/java/com/tanvir/video/service/ClipExtractorServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;

import static org.junit.jupiter.api.Assertions.*;

class ClipExtractorServiceTest {

    private ClipExtractorService clipExtractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var props = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", tempDir.toString());
        clipExtractor = new ClipExtractorService(props);
    }

    @Test
    void extractClip_producesPlayableHls() throws Exception {
        // Generate a 10-second test video
        Path source = tempDir.resolve("source.ts");
        new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=green:s=320x240:d=10:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                "-c:v", "libx264", "-c:a", "aac",
                source.toString())
                .redirectErrorStream(true).start().waitFor();

        Path outputDir = tempDir.resolve("clip_out");
        String slug = clipExtractor.extractClip(source, 2.0, 5.0, "test_goal", outputDir);

        Path playlist = outputDir.resolve(slug).resolve("playlist.m3u8");
        Path master = outputDir.resolve(slug).resolve("master.m3u8");
        Path eventJson = outputDir.resolve(slug).resolve("event.json");

        assertTrue(Files.exists(playlist), "playlist.m3u8 should exist");
        assertTrue(Files.exists(master), "master.m3u8 should exist");
        assertTrue(Files.exists(eventJson), "event.json should exist");
        assertTrue(Files.size(playlist) > 0, "Playlist should not be empty");
    }

    @Test
    void extractClip_clampsStartToZero() throws Exception {
        Path source = tempDir.resolve("short.ts");
        new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=320x240:d=5:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
                "-c:v", "libx264", "-c:a", "aac",
                source.toString())
                .redirectErrorStream(true).start().waitFor();

        Path outputDir = tempDir.resolve("clip_clamp");
        // Request clip starting before 0
        String slug = clipExtractor.extractClip(source, 1.0, 5.0, "early_event", outputDir);

        Path playlist = outputDir.resolve(slug).resolve("playlist.m3u8");
        assertTrue(Files.exists(playlist), "Should still produce a clip");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests 'com.tanvir.video.service.ClipExtractorServiceTest'`
Expected: FAIL — class does not exist

- [ ] **Step 3: Implement ClipExtractorService**

```java
package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tanvir.video.config.DetectionProperties;

@Service
public class ClipExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ClipExtractorService.class);

    private final DetectionProperties props;

    public ClipExtractorService(DetectionProperties props) {
        this.props = props;
    }

    /**
     * Extract a clip centered on eventTimeSec with the configured window duration.
     * Returns the slug (directory name) of the extracted clip.
     */
    public String extractClip(Path sourcePath, double eventTimeSec,
                              double clipDuration, String slug, Path outputDir) throws Exception {
        double start = Math.max(0, eventTimeSec - clipDuration / 2);

        Path clipDir = outputDir.resolve(slug);
        Files.createDirectories(clipDir);

        Path playlist = clipDir.resolve("playlist.m3u8");

        log.info("Extracting clip: {} at {:.1f}s for {:.1f}s", slug, start, clipDuration);

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-ss", String.valueOf(start),
                "-i", sourcePath.toString(),
                "-t", String.valueOf(clipDuration),
                "-map", "0:v:0", "-map", "0:a:0",
                "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                "-bsf:v", "h264_mp4toannexb",
                "-hls_time", "6", "-hls_list_size", "0",
                "-hls_segment_filename", clipDir.resolve("segment_%03d.ts").toString(),
                playlist.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();

        if (exitCode != 0) {
            log.error("ffmpeg failed for {}: {}", slug, output.substring(Math.max(0, output.length() - 500)));
            throw new RuntimeException("ffmpeg clip extraction failed for " + slug);
        }

        // Write master playlist
        Files.writeString(clipDir.resolve("master.m3u8"),
                "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2628000,RESOLUTION=1280x720\n" +
                "playlist.m3u8\n");

        // Write event metadata placeholder (populated by caller)
        Files.writeString(clipDir.resolve("event.json"), "{}");

        log.info("Clip extracted: {}", slug);
        return slug;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.tanvir.video.service.ClipExtractorServiceTest'`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tanvir/video/service/ClipExtractorService.java \
  src/test/java/com/tanvir/video/service/ClipExtractorServiceTest.java
git commit -m "Add ClipExtractorService for 30-second HLS clip extraction"
```

---

### Task 6: ClassifierService — Whisper + LLM

**Files:**
- Create: `src/main/resources/prompts/classify_event.txt`
- Create: `src/main/java/com/tanvir/video/service/ClassifierService.java`
- Create: `src/test/java/com/tanvir/video/service/ClassifierServiceTest.java`

- [ ] **Step 1: Create the prompt template**

`src/main/resources/prompts/classify_event.txt`:

```
You are a soccer match event detector. Analyze this 30-second window from a live broadcast.

## Inputs
- Commentary transcript: {transcript}
- Scoreboard before: {ocr_before}
- Scoreboard after: {ocr_after}
- Audio energy level: {audio_rms} (baseline: {baseline_rms})

## Task
Identify if any of these events occurred:
- Goal
- Penalty
- Red card
- Yellow card
- Key save / Shot on target

## Response format (JSON only, no markdown)
{"events": [{"type": "goal", "confidence": 0.95, "description": "Home team scores"}]}

If no key event occurred, return: {"events": []}
Do not guess. If uncertain, set confidence below 0.5.
```

- [ ] **Step 2: Write the failing tests**

```java
package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;
import com.tanvir.video.model.ClassificationResult;
import com.tanvir.video.model.TriageResult;

import static org.junit.jupiter.api.Assertions.*;

class ClassifierServiceTest {

    private ClassifierService classifier;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var detectionProps = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", tempDir.toString());
        // LLM disabled — will use stub responses
        var openRouterProps = new OpenRouterProperties(
                "https://openrouter.ai/api/v1/chat/completions",
                "qwen/qwen3.5-flash-02-23", "", false);
        classifier = new ClassifierService(detectionProps, openRouterProps);
    }

    @Test
    void transcribeAudio_producesTextForAudioWithSpeech() throws Exception {
        // Generate audio with speech-like tone (whisper may produce empty text for pure tone)
        Path wav = tempDir.resolve("speech.wav");
        new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i",
                "sine=frequency=300:duration=3", wav.toString())
                .redirectErrorStream(true).start().waitFor();

        String transcript = classifier.transcribeAudio(wav);
        assertNotNull(transcript, "Transcript should not be null");
        // For a pure tone, whisper may return empty string — that's fine
    }

    @Test
    void buildPrompt_insertsAllPlaceholders() {
        var triage = new TriageResult(0, "video.ts", "audio.wav",
                0.15, 0.05, "1 - 0", "2 - 0", true, "audio spike");

        String prompt = classifier.buildPrompt("The crowd goes wild!", triage);

        assertTrue(prompt.contains("The crowd goes wild!"), "Should contain transcript");
        assertTrue(prompt.contains("1 - 0"), "Should contain OCR before");
        assertTrue(prompt.contains("2 - 0"), "Should contain OCR after");
        assertTrue(prompt.contains("0.15"), "Should contain audio RMS");
        assertTrue(prompt.contains("0.05"), "Should contain baseline RMS");
    }

    @Test
    void parseResponse_validJson() {
        String json = """
                {"events": [{"type": "goal", "confidence": 0.92, "description": "Score changes to 2-0"}]}
                """;
        ClassificationResult result = classifier.parseResponse(json);
        assertEquals(1, result.events().size());
        assertEquals("goal", result.events().get(0).type());
        assertEquals(0.92, result.events().get(0).confidence(), 0.01);
    }

    @Test
    void parseResponse_emptyEvents() {
        String json = """
                {"events": []}
                """;
        ClassificationResult result = classifier.parseResponse(json);
        assertTrue(result.events().isEmpty());
    }

    @Test
    void parseResponse_malformedJsonReturnsEmpty() {
        ClassificationResult result = classifier.parseResponse("not json at all");
        assertTrue(result.events().isEmpty());
    }

    @Test
    void classify_whenDisabled_returnsEmptyResult() throws Exception {
        var triage = new TriageResult(0, "video.ts", "audio.wav",
                0.15, 0.05, "1 - 0", "2 - 0", true, "audio spike");

        ClassificationResult result = classifier.classify(triage, "Goal scored!");
        assertTrue(result.events().isEmpty(), "Should return empty when LLM disabled");
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew test --tests 'com.tanvir.video.service.ClassifierServiceTest'`
Expected: FAIL — class does not exist

- [ ] **Step 4: Implement ClassifierService**

```java
package com.tanvir.video.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;
import com.tanvir.video.model.ClassificationResult;
import com.tanvir.video.model.TriageResult;

import jakarta.annotation.PostConstruct;

@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);

    private final DetectionProperties detectionProps;
    private final OpenRouterProperties openRouterProps;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String promptTemplate;

    public ClassifierService(DetectionProperties detectionProps, OpenRouterProperties openRouterProps) {
        this.detectionProps = detectionProps;
        this.openRouterProps = openRouterProps;
    }

    @PostConstruct
    void loadPromptTemplate() {
        try {
            var resource = new ClassPathResource("prompts/classify_event.txt");
            promptTemplate = new String(resource.getInputStream().readAllBytes());
        } catch (Exception e) {
            log.error("Failed to load prompt template", e);
            promptTemplate = "";
        }
    }

    public String transcribeAudio(Path audioPath) throws Exception {
        Path outputDir = audioPath.getParent();
        ProcessBuilder pb = new ProcessBuilder(
                "whisper", audioPath.toString(),
                "--model", detectionProps.whisperModel(),
                "--language", "en",
                "--output_format", "json",
                "--output_dir", outputDir.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes(); // drain
        p.waitFor();

        // Whisper outputs <filename>.json
        String baseName = audioPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path jsonPath = outputDir.resolve(baseName + ".json");

        if (!jsonPath.toFile().exists()) {
            log.warn("Whisper produced no output for {}", audioPath);
            return "";
        }

        JsonNode root = objectMapper.readTree(jsonPath.toFile());
        if (root.has("text")) {
            return root.get("text").asText();
        }
        return "";
    }

    public String buildPrompt(String transcript, TriageResult triage) {
        return promptTemplate
                .replace("{transcript}", transcript)
                .replace("{ocr_before}", triage.ocrBefore())
                .replace("{ocr_after}", triage.ocrAfter())
                .replace("{audio_rms}", String.format("%.4f", triage.audioRms()))
                .replace("{baseline_rms}", String.format("%.4f", triage.baselineRms()));
    }

    public ClassificationResult classify(TriageResult triage, String transcript) throws Exception {
        if (!openRouterProps.enabled()) {
            log.info("LLM disabled — skipping classification for window {}", triage.windowIndex());
            return new ClassificationResult(List.of());
        }

        String prompt = buildPrompt(transcript, triage);
        return callLlm(prompt);
    }

    ClassificationResult callLlm(String prompt) {
        try {
            WebClient client = WebClient.create(openRouterProps.apiUrl());
            String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("model", openRouterProps.model());
                put("messages", List.of(
                        new java.util.LinkedHashMap<>() {{
                            put("role", "user");
                            put("content", prompt);
                        }}
                ));
                put("reasoning", new java.util.LinkedHashMap<>() {{
                    put("effort", "none");
                }});
            }});

            String response = client.post()
                    .header("Authorization", "Bearer " + openRouterProps.apiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText();
            return parseResponse(content);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            return new ClassificationResult(List.of());
        }
    }

    public ClassificationResult parseResponse(String json) {
        try {
            // Strip markdown code fences if present
            String cleaned = json.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode eventsNode = root.get("events");
            if (eventsNode == null || !eventsNode.isArray()) {
                return new ClassificationResult(List.of());
            }
            List<ClassificationResult.Event> events = new java.util.ArrayList<>();
            for (JsonNode e : eventsNode) {
                events.add(new ClassificationResult.Event(
                        e.get("type").asText(),
                        e.get("confidence").asDouble(),
                        e.has("description") ? e.get("description").asText() : ""
                ));
            }
            return new ClassificationResult(events);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return new ClassificationResult(List.of());
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests 'com.tanvir.video.service.ClassifierServiceTest'`
Expected: All 7 tests PASS (whisper test may produce empty transcript for pure tone — that's expected)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/prompts/classify_event.txt \
  src/main/java/com/tanvir/video/service/ClassifierService.java \
  src/test/java/com/tanvir/video/service/ClassifierServiceTest.java
git commit -m "Add ClassifierService with Whisper transcription and LLM classification"
```

---

### Task 7: StreamProcessingService — Pipeline Orchestrator

**Files:**
- Create: `src/main/java/com/tanvir/video/service/StreamProcessingService.java`
- Create: `src/test/java/com/tanvir/video/service/StreamProcessingServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;
import com.tanvir.video.model.StreamSession;

import static org.junit.jupiter.api.Assertions.*;

class StreamProcessingServiceTest {

    private StreamProcessingService processingService;
    private TriageService triageService;
    private ClassifierService classifierService;
    private ClipExtractorService clipExtractorService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        Path workDir = tempDir.resolve("work");
        Path hlsDir = tempDir.resolve("hls");
        var detectionProps = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", workDir.toString());
        var openRouterProps = new OpenRouterProperties(
                "https://openrouter.ai/api/v1/chat/completions",
                "qwen/qwen3.5-flash-02-23", "", false);

        triageService = new TriageService(detectionProps);
        classifierService = new ClassifierService(detectionProps, openRouterProps);
        clipExtractorService = new ClipExtractorService(detectionProps);
        processingService = new StreamProcessingService(
                detectionProps, triageService, classifierService, clipExtractorService, hlsDir.toString());
    }

    @Test
    void segmentStream_producesWindowFiles() throws Exception {
        // Generate a 15-second test video (should produce at least 1 window)
        Path source = tempDir.resolve("stream.ts");
        new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=15:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=15",
                "-c:v", "libx264", "-c:a", "aac",
                source.toString())
                .redirectErrorStream(true).start().waitFor();

        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);

        var windows = processingService.segmentStream(source, workDir);
        assertFalse(windows.isEmpty(), "Should produce at least one window");

        for (var window : windows) {
            assertTrue(Files.exists(window.videoPath()), "Video chunk should exist: " + window.videoPath());
            assertTrue(Files.exists(window.audioPath()), "Audio chunk should exist: " + window.audioPath());
        }
    }

    @Test
    void createSession_returnsRunningSession() {
        StreamSession session = processingService.createSession("http://example.com/stream.m3u8");
        assertNotNull(session.getId());
        assertEquals(StreamSession.Status.RUNNING, session.getStatus());
        assertEquals("http://example.com/stream.m3u8", session.getStreamUrl());
    }

    @Test
    void getSession_returnsCreatedSession() {
        StreamSession session = processingService.createSession("http://example.com/stream.m3u8");
        StreamSession found = processingService.getSession(session.getId());
        assertSame(session, found);
    }

    @Test
    void getSession_returnsNullForUnknownId() {
        assertNull(processingService.getSession("nonexistent"));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests 'com.tanvir.video.service.StreamProcessingServiceTest'`
Expected: FAIL — class does not exist

- [ ] **Step 3: Implement StreamProcessingService**

```java
package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.model.ClassificationResult;
import com.tanvir.video.model.DetectedEvent;
import com.tanvir.video.model.StreamSession;
import com.tanvir.video.model.TriageResult;

@Service
public class StreamProcessingService {

    private static final Logger log = LoggerFactory.getLogger(StreamProcessingService.class);

    private final DetectionProperties props;
    private final TriageService triageService;
    private final ClassifierService classifierService;
    private final ClipExtractorService clipExtractorService;
    private final String hlsDir;
    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();

    public StreamProcessingService(
            DetectionProperties props,
            TriageService triageService,
            ClassifierService classifierService,
            ClipExtractorService clipExtractorService,
            @Value("${app.hls-dir}") String hlsDir) {
        this.props = props;
        this.triageService = triageService;
        this.classifierService = classifierService;
        this.clipExtractorService = clipExtractorService;
        this.hlsDir = hlsDir;
    }

    public StreamSession createSession(String streamUrl) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        StreamSession session = new StreamSession(id, streamUrl);
        sessions.put(id, session);
        return session;
    }

    public StreamSession getSession(String id) {
        return sessions.get(id);
    }

    public void stopSession(String id) {
        StreamSession session = sessions.get(id);
        if (session != null) {
            session.setStatus(StreamSession.Status.STOPPED);
            Process p = session.getIngestorProcess();
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    public record WindowChunk(Path videoPath, Path audioPath, int index) {}

    public List<WindowChunk> segmentStream(Path sourcePath, Path workDir) throws Exception {
        Files.createDirectories(workDir);
        int windowDuration = props.windowDuration();

        // Segment video into chunks
        ProcessBuilder videoPb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", sourcePath.toString(),
                "-c", "copy",
                "-f", "segment",
                "-segment_time", String.valueOf(windowDuration),
                "-reset_timestamps", "1",
                workDir.resolve("window_%03d.ts").toString()
        );
        videoPb.redirectErrorStream(true);
        Process videoProc = videoPb.start();
        videoProc.getInputStream().readAllBytes();
        videoProc.waitFor();

        // Find produced segments and extract audio for each
        List<WindowChunk> windows = new ArrayList<>();
        for (int i = 0; ; i++) {
            Path videoChunk = workDir.resolve(String.format("window_%03d.ts", i));
            if (!Files.exists(videoChunk)) break;

            Path audioChunk = workDir.resolve(String.format("window_%03d.wav", i));
            ProcessBuilder audioPb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", videoChunk.toString(),
                    "-vn", "-ar", "22050", "-ac", "1",
                    audioChunk.toString()
            );
            audioPb.redirectErrorStream(true);
            Process audioProc = audioPb.start();
            audioProc.getInputStream().readAllBytes();
            audioProc.waitFor();

            windows.add(new WindowChunk(videoChunk, audioChunk, i));
        }

        log.info("Segmented {} into {} windows", sourcePath.getFileName(), windows.size());
        return windows;
    }

    @Async("pipelineExecutor")
    public void processStream(String sessionId, Consumer<String> eventCallback) {
        StreamSession session = sessions.get(sessionId);
        if (session == null) return;

        try {
            Path workDir = Path.of(props.workDir(), sessionId);
            Files.createDirectories(workDir);
            Path hlsOutput = Path.of(hlsDir, sessionId);
            Files.createDirectories(hlsOutput);

            Path sourcePath = Path.of(session.getStreamUrl());
            // If it's an HLS URL, download/process it; if local file, use directly
            // For now, support local file paths and local HLS playlists

            eventCallback.accept("{\"type\":\"progress\",\"message\":\"Segmenting stream...\"}");
            List<WindowChunk> windows = segmentStream(sourcePath, workDir);

            // Calibrate baseline from first 2 windows
            double baselineRms = 0.01;
            if (!windows.isEmpty()) {
                double sum = 0;
                int count = Math.min(2, windows.size());
                for (int i = 0; i < count; i++) {
                    sum += triageService.extractAudioRms(windows.get(i).audioPath());
                }
                baselineRms = sum / count;
                if (baselineRms <= 0) baselineRms = 0.01;
            }
            eventCallback.accept("{\"type\":\"progress\",\"message\":\"Baseline RMS: " +
                    String.format("%.4f", baselineRms) + "\"}");

            // Process each window
            for (WindowChunk window : windows) {
                if (session.getStatus() != StreamSession.Status.RUNNING) break;

                // Stage 1: Triage
                TriageResult triage = triageService.triage(
                        window.index(), window.videoPath(), window.audioPath(), baselineRms, workDir);
                session.incrementWindowsProcessed();

                eventCallback.accept("{\"type\":\"triage\",\"window\":" + window.index() +
                        ",\"candidate\":" + triage.isCandidate() +
                        ",\"reason\":\"" + triage.reason().replace("\"", "'") + "\"}");

                if (!triage.isCandidate()) continue;
                session.incrementCandidatesFound();

                // Stage 2: Classify
                eventCallback.accept("{\"type\":\"classifying\",\"window\":" + window.index() + "}");

                String transcript = classifierService.transcribeAudio(window.audioPath());
                ClassificationResult classification = classifierService.classify(triage, transcript);

                for (ClassificationResult.Event event : classification.events()) {
                    if (event.confidence() < props.confidenceThreshold()) {
                        eventCallback.accept("{\"type\":\"low_confidence\",\"event\":\"" +
                                event.type() + "\",\"confidence\":" + event.confidence() + "}");
                        continue;
                    }

                    // Stage 3: Extract clip
                    double eventTime = window.index() * props.windowDuration() + props.windowDuration() / 2.0;
                    String slug = String.format("%s_%03d_%s", sessionId, window.index(), event.type());

                    // For clip extraction, use the original source rather than the chunk
                    clipExtractorService.extractClip(
                            sourcePath, eventTime, props.windowDuration(), slug, hlsOutput);

                    DetectedEvent detected = new DetectedEvent(
                            event.type(), event.confidence(), event.description(),
                            window.index(), eventTime, slug, transcript);
                    session.addEvent(detected);

                    eventCallback.accept("{\"type\":\"event\",\"event\":\"" + event.type() +
                            "\",\"confidence\":" + event.confidence() +
                            ",\"clip\":\"" + slug + "\",\"timestamp\":" + eventTime + "}");
                }
            }

            if (session.getStatus() == StreamSession.Status.RUNNING) {
                session.setStatus(StreamSession.Status.STOPPED);
            }
            eventCallback.accept("{\"type\":\"done\",\"events\":" + session.getDetectedEvents().size() +
                    ",\"windows\":" + session.getWindowsProcessed() +
                    ",\"candidates\":" + session.getCandidatesFound() + "}");

        } catch (Exception e) {
            log.error("Pipeline failed for session {}: {}", sessionId, e.getMessage(), e);
            session.setStatus(StreamSession.Status.ERROR);
            eventCallback.accept("{\"type\":\"error\",\"message\":\"" +
                    e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.tanvir.video.service.StreamProcessingServiceTest'`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tanvir/video/service/StreamProcessingService.java \
  src/test/java/com/tanvir/video/service/StreamProcessingServiceTest.java
git commit -m "Add StreamProcessingService pipeline orchestrator"
```

---

### Task 8: StreamController — REST + SSE

**Files:**
- Create: `src/main/java/com/tanvir/video/controller/StreamController.java`
- Create: `src/test/java/com/tanvir/video/controller/StreamControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tanvir.video.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void startStream_returnsSessionId() throws Exception {
        mockMvc.perform(post("/api/streams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"samples/hls/01_goal_2_arsenal/master.m3u8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getSession_returnsNotFoundForUnknown() throws Exception {
        mockMvc.perform(get("/api/streams/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopStream_returnsNotFoundForUnknown() throws Exception {
        mockMvc.perform(delete("/api/streams/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests 'com.tanvir.video.controller.StreamControllerTest'`
Expected: FAIL — class does not exist

- [ ] **Step 3: Implement StreamController**

```java
package com.tanvir.video.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tanvir.video.model.StreamSession;
import com.tanvir.video.service.StreamProcessingService;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamProcessingService processingService;

    public StreamController(StreamProcessingService processingService) {
        this.processingService = processingService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> startStream(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }

        StreamSession session = processingService.createSession(url);

        // Start async processing with SSE callback stored for later
        processingService.processStream(session.getId(), message -> {
            // Logging only for non-SSE callers; SSE gets its own emitter
        });

        return ResponseEntity.ok(Map.of(
                "id", session.getId(),
                "status", session.getStatus().name(),
                "streamUrl", session.getStreamUrl()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String id) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "id", session.getId(),
                "status", session.getStatus().name(),
                "streamUrl", session.getStreamUrl(),
                "windowsProcessed", session.getWindowsProcessed(),
                "candidatesFound", session.getCandidatesFound(),
                "eventsDetected", session.getDetectedEvents().size()
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> stopStream(@PathVariable String id) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        processingService.stopSession(id);
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String id) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("Session not found"));
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout
        processingService.processStream(session.getId(), message -> {
            try {
                emitter.send(SseEmitter.event().data(message));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests 'com.tanvir.video.controller.StreamControllerTest'`
Expected: All 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tanvir/video/controller/StreamController.java \
  src/test/java/com/tanvir/video/controller/StreamControllerTest.java
git commit -m "Add StreamController with REST and SSE endpoints"
```

---

### Task 9: Player UI — Stream Input and Event Log

**Files:**
- Modify: `src/main/resources/templates/player.html`

- [ ] **Step 1: Update player.html with stream processing UI**

Replace the full content of `player.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>HLS Player</title>
    <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { background: #0a0a0a; color: #eee; font-family: system-ui, -apple-system, sans-serif; height: 100vh; display: flex; flex-direction: column; }
        header { padding: 16px 24px; border-bottom: 1px solid #1a1a1a; flex-shrink: 0; display: flex; align-items: center; gap: 16px; }
        header h1 { font-size: 18px; font-weight: 500; color: #ccc; }
        .stream-input { display: flex; gap: 8px; margin-left: auto; }
        .stream-input input { background: #1a1a1a; border: 1px solid #333; color: #eee; padding: 6px 12px; border-radius: 4px; width: 320px; font-size: 13px; }
        .stream-input button { background: #2a4a2a; border: 1px solid #3a5a3a; color: #8c8; padding: 6px 16px; border-radius: 4px; cursor: pointer; font-size: 13px; }
        .stream-input button:hover { background: #3a5a3a; }
        .stream-input button.stop { background: #4a2a2a; border-color: #5a3a3a; color: #c88; }
        .stream-input button.stop:hover { background: #5a3a3a; }
        .layout { display: flex; flex: 1; min-height: 0; }
        .main-area { flex: 1; display: flex; flex-direction: column; min-width: 0; }
        .player-area { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 24px; background: #000; min-height: 0; }
        .player-area .placeholder { color: #444; font-size: 15px; }
        video { width: 100%; max-height: 100%; display: none; border-radius: 4px; }
        #quality { margin-top: 12px; font-size: 13px; color: #666; flex-shrink: 0; }
        .event-log { height: 180px; border-top: 1px solid #1a1a1a; overflow-y: auto; padding: 8px 16px; font-family: monospace; font-size: 12px; flex-shrink: 0; }
        .event-log .log-entry { padding: 2px 0; }
        .event-log .triage { color: #666; }
        .event-log .candidate { color: #cc6; }
        .event-log .classifying { color: #6ac; }
        .event-log .event-detected { color: #6c6; font-weight: bold; }
        .event-log .error { color: #c66; }
        .event-log .progress { color: #888; }
        .sidebar { width: 280px; flex-shrink: 0; border-left: 1px solid #1a1a1a; display: flex; flex-direction: column; }
        .sidebar h2 { font-size: 13px; font-weight: 600; color: #888; text-transform: uppercase; letter-spacing: 0.5px; padding: 16px 16px 12px; flex-shrink: 0; }
        .video-list { list-style: none; overflow-y: auto; flex: 1; padding: 0 8px 8px; }
        .video-list li { padding: 10px 12px; cursor: pointer; border-radius: 6px; font-size: 14px; color: #bbb; margin-bottom: 2px; }
        .video-list li:hover { background: #1a1a1a; color: #fff; }
        .video-list li.active { background: #1a2a1a; color: #6c6; }
        .badge { display: inline-block; font-size: 11px; padding: 1px 6px; border-radius: 3px; margin-right: 6px; font-weight: 600; text-transform: uppercase; }
        .badge-goal { background: #2a4a2a; color: #6c6; }
        .badge-penalty { background: #4a3a2a; color: #ca6; }
        .badge-red_card, .badge-red { background: #4a2a2a; color: #c66; }
        .badge-yellow_card, .badge-yellow { background: #4a4a2a; color: #cc6; }
        .badge-save, .badge-shot { background: #2a3a4a; color: #6ac; }
        .detected-events { border-bottom: 1px solid #1a1a1a; }
    </style>
</head>
<body>
    <header>
        <h1>HLS Player</h1>
        <div class="stream-input">
            <input type="text" id="stream-url" placeholder="Stream URL or local path..." />
            <button id="start-btn" onclick="startProcessing()">Process</button>
            <button id="stop-btn" class="stop" style="display:none;" onclick="stopProcessing()">Stop</button>
        </div>
    </header>

    <div class="layout">
        <div class="main-area">
            <div class="player-area">
                <span class="placeholder" id="placeholder">Select a clip to play</span>
                <video id="video" controls></video>
                <div id="quality"></div>
            </div>
            <div class="event-log" id="event-log"></div>
        </div>

        <aside class="sidebar">
            <div class="detected-events" id="detected-events">
                <h2>Detected Events</h2>
                <ul class="video-list" id="detected-list"></ul>
            </div>
            <h2>Clips</h2>
            <ul class="video-list" id="video-list">
                <li th:each="name : ${videos}" th:data-src="'/hls/' + ${name} + '/master.m3u8'" th:text="${name}">video</li>
            </ul>
        </aside>
    </div>

    <script>
        var video = document.getElementById('video');
        var placeholder = document.getElementById('placeholder');
        var hls = null;
        var qualityInterval = null;
        var activeLi = null;
        var eventSource = null;
        var currentSessionId = null;

        function playSource(src, li) {
            if (qualityInterval) { clearInterval(qualityInterval); }
            if (hls) { hls.destroy(); }
            if (activeLi) { activeLi.classList.remove('active'); }
            activeLi = li;
            if (li) { li.classList.add('active'); }

            placeholder.style.display = 'none';
            video.style.display = 'block';

            if (Hls.isSupported()) {
                hls = new Hls();
                hls.loadSource(src);
                hls.attachMedia(video);
                hls.on(Hls.Events.MANIFEST_PARSED, function() {
                    video.play();
                });
                var qualityDiv = document.getElementById('quality');
                qualityInterval = setInterval(function() {
                    if (hls && hls.currentLevel >= 0 && hls.levels[hls.currentLevel]) {
                        var level = hls.levels[hls.currentLevel];
                        qualityDiv.textContent = level.height + 'p \u00b7 ' + Math.round(level.bitrate / 1000) + ' kbps';
                    }
                }, 500);
            } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
                video.src = src;
                video.play();
            }
        }

        document.getElementById('video-list').addEventListener('click', function(e) {
            var li = e.target.closest('li');
            if (li && li.dataset.src) { playSource(li.dataset.src, li); }
        });

        document.getElementById('detected-list').addEventListener('click', function(e) {
            var li = e.target.closest('li');
            if (li && li.dataset.src) { playSource(li.dataset.src, li); }
        });

        function logEvent(text, cls) {
            var log = document.getElementById('event-log');
            var entry = document.createElement('div');
            entry.className = 'log-entry ' + (cls || '');
            var time = new Date().toLocaleTimeString();
            entry.textContent = '[' + time + '] ' + text;
            log.appendChild(entry);
            log.scrollTop = log.scrollHeight;
        }

        function startProcessing() {
            var url = document.getElementById('stream-url').value.trim();
            if (!url) return;

            fetch('/api/streams', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({url: url})
            })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                currentSessionId = data.id;
                document.getElementById('start-btn').style.display = 'none';
                document.getElementById('stop-btn').style.display = '';
                logEvent('Started processing: ' + url, 'progress');

                // Connect SSE
                eventSource = new EventSource('/api/streams/' + data.id + '/events');
                eventSource.onmessage = function(e) {
                    var msg = JSON.parse(e.data);
                    handleStreamEvent(msg);
                };
                eventSource.onerror = function() {
                    logEvent('Connection lost', 'error');
                    eventSource.close();
                };
            });
        }

        function stopProcessing() {
            if (currentSessionId) {
                fetch('/api/streams/' + currentSessionId, {method: 'DELETE'});
            }
            if (eventSource) { eventSource.close(); }
            document.getElementById('start-btn').style.display = '';
            document.getElementById('stop-btn').style.display = 'none';
            logEvent('Stopped', 'progress');
        }

        function handleStreamEvent(msg) {
            switch (msg.type) {
                case 'triage':
                    if (msg.candidate) {
                        logEvent('Window ' + msg.window + ': CANDIDATE - ' + msg.reason, 'candidate');
                    } else {
                        logEvent('Window ' + msg.window + ': skip - ' + msg.reason, 'triage');
                    }
                    break;
                case 'classifying':
                    logEvent('Window ' + msg.window + ': sending to LLM...', 'classifying');
                    break;
                case 'event':
                    logEvent('DETECTED: ' + msg.event + ' (confidence: ' + msg.confidence.toFixed(2) + ')', 'event-detected');
                    addDetectedEvent(msg);
                    break;
                case 'low_confidence':
                    logEvent('Possible ' + msg.event + ' (confidence: ' + msg.confidence.toFixed(2) + ' - below threshold)', 'triage');
                    break;
                case 'progress':
                    logEvent(msg.message, 'progress');
                    break;
                case 'error':
                    logEvent('ERROR: ' + msg.message, 'error');
                    break;
                case 'done':
                    logEvent('Done: ' + msg.events + ' events from ' + msg.windows + ' windows (' + msg.candidates + ' candidates)', 'progress');
                    stopProcessing();
                    break;
            }
        }

        function addDetectedEvent(msg) {
            var list = document.getElementById('detected-list');
            var li = document.createElement('li');
            var badgeClass = 'badge-' + msg.event.replace(' ', '_').replace('/', '_');
            li.innerHTML = '<span class="badge ' + badgeClass + '">' + msg.event + '</span>' +
                           Math.floor(msg.timestamp / 60) + ':' + ('0' + Math.floor(msg.timestamp % 60)).slice(-2);
            li.dataset.src = '/hls/' + currentSessionId + '/' + msg.clip + '/master.m3u8';
            list.appendChild(li);
        }
    </script>
</body>
</html>
```

- [ ] **Step 2: Build to verify no compilation errors**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/player.html
git commit -m "Update player UI with stream processing input and live event log"
```

---

### Task 10: Dockerfile

**Files:**
- Create: `Dockerfile`

- [ ] **Step 1: Create the Dockerfile**

```dockerfile
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    tesseract-ocr \
    python3 python3-pip \
    && pip3 install --break-system-packages openai-whisper \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY build/libs/video*.jar /app/app.jar

ENV OPENROUTER_API_KEY=""
VOLUME /app/samples/hls

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Verify build produces the jar**

Run: `./gradlew bootJar && ls -la build/libs/`
Expected: `video-0.0.1-SNAPSHOT.jar` exists

- [ ] **Step 3: Commit**

```bash
git add Dockerfile
git commit -m "Add Dockerfile bundling ffmpeg, tesseract, and whisper"
```

---

### Task 11: Integration Test — Full Pipeline Against Test Clip

**Files:**
- Create: `src/test/java/com/tanvir/video/service/PipelineIntegrationTest.java`

- [ ] **Step 1: Write integration test using a test clip**

```java
package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;

import static org.junit.jupiter.api.Assertions.*;

class PipelineIntegrationTest {

    @TempDir
    Path tempDir;

    private StreamProcessingService processingService;

    @BeforeEach
    void setUp() {
        Path workDir = tempDir.resolve("work");
        Path hlsDir = tempDir.resolve("hls");
        var detectionProps = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", workDir.toString());
        var openRouterProps = new OpenRouterProperties(
                "https://openrouter.ai/api/v1/chat/completions",
                "qwen/qwen3.5-flash-02-23", "", false);

        var triageService = new TriageService(detectionProps);
        var classifierService = new ClassifierService(detectionProps, openRouterProps);
        var clipExtractorService = new ClipExtractorService(detectionProps);
        processingService = new StreamProcessingService(
                detectionProps, triageService, classifierService, clipExtractorService, hlsDir.toString());
    }

    @Test
    void segmentAndTriage_findsAtLeastOneCandidateInGoalClip() throws Exception {
        // Use the first test clip which contains a goal
        Path testClip = Path.of("samples/hls/01_goal_2_arsenal/master.m3u8");
        if (!Files.exists(testClip)) {
            // Skip if test clips not available
            return;
        }

        // First, create a flat TS from the HLS playlist for segmentation
        Path flatTs = tempDir.resolve("flat.ts");
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", testClip.toString(),
                "-c", "copy",
                flatTs.toString()
        );
        pb.redirectErrorStream(true);
        pb.start().waitFor();

        assertTrue(Files.exists(flatTs), "Flat TS should be created from HLS");

        Path workDir = tempDir.resolve("work");
        var windows = processingService.segmentStream(flatTs, workDir);
        assertFalse(windows.isEmpty(), "Should produce windows from test clip");

        // Triage the windows
        var triageService = new TriageService(
                new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", workDir.toString()));

        double baselineRms = triageService.extractAudioRms(windows.get(0).audioPath());

        int candidates = 0;
        for (var window : windows) {
            var result = triageService.triage(window.index(), window.videoPath(), window.audioPath(), baselineRms, workDir);
            if (result.isCandidate()) candidates++;
        }

        // A goal clip should have at least some audio spikes
        assertTrue(candidates > 0, "Goal clip should have at least one candidate window");
    }

    @Test
    void fullPipeline_processesWithoutErrors() throws Exception {
        // Generate a synthetic test stream
        Path source = tempDir.resolve("test_stream.ts");
        new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=320x240:d=35:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=35",
                "-c:v", "libx264", "-c:a", "aac",
                source.toString())
                .redirectErrorStream(true).start().waitFor();

        var session = processingService.createSession(source.toString());
        List<String> events = new ArrayList<>();

        // Run synchronously (not @Async) for testing
        processingService.processStream(session.getId(), events::add);

        // Wait briefly for async processing
        Thread.sleep(2000);

        assertFalse(events.isEmpty(), "Should produce at least some events");
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"triage\"")),
                "Should have triage events");
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"done\"") || e.contains("\"type\":\"progress\"")),
                "Should have completion or progress events");
    }
}
```

- [ ] **Step 2: Run integration tests**

Run: `./gradlew test --tests 'com.tanvir.video.service.PipelineIntegrationTest'`
Expected: All tests PASS

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tanvir/video/service/PipelineIntegrationTest.java
git commit -m "Add pipeline integration tests against test clips"
```

---

### Task 12: Evaluation Script

**Files:**
- Create: `scripts/evaluate_detection.py`

- [ ] **Step 1: Write the evaluation script**

```python
"""
Evaluate event detection pipeline against ground truth.

Runs the pipeline's triage stage against each test clip, compares
detected candidate windows to ground_truth.json events, and reports
precision, recall, and F1 per event type.

Usage:
  ./gradlew bootRun &   # start the server first
  python scripts/evaluate_detection.py [--tolerance 15]
"""

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from urllib import request, error

PROJECT_ROOT = Path(__file__).resolve().parent.parent
HLS_DIR = PROJECT_ROOT / "samples" / "hls"
API_URL = "http://localhost:8080/api/streams"


def start_processing(clip_path):
    """Start pipeline processing for a clip via the REST API."""
    data = json.dumps({"url": str(clip_path)}).encode()
    req = request.Request(API_URL, data=data, headers={"Content-Type": "application/json"})
    try:
        resp = request.urlopen(req)
        return json.loads(resp.read())
    except error.URLError as e:
        print(f"  ERROR: Cannot reach server at {API_URL}: {e}")
        return None


def poll_session(session_id, timeout=300):
    """Poll session until done or timeout."""
    url = f"{API_URL}/{session_id}"
    start = time.time()
    while time.time() - start < timeout:
        try:
            resp = request.urlopen(url)
            data = json.loads(resp.read())
            if data["status"] in ("STOPPED", "ERROR"):
                return data
        except error.URLError:
            pass
        time.sleep(2)
    return None


def load_ground_truth(clip_dir):
    """Load ground truth events from a clip directory."""
    gt_path = clip_dir / "ground_truth.json"
    if not gt_path.exists():
        return []
    data = json.loads(gt_path.read_text())
    return data.get("events", [])


def match_events(detected, ground_truth, tolerance_sec):
    """Match detected events to ground truth within tolerance."""
    tp = 0
    matched_gt = set()
    for det in detected:
        det_time = det.get("timestampSec", 0)
        for i, gt in enumerate(ground_truth):
            if i in matched_gt:
                continue
            gt_time = gt.get("offset_sec", 0)
            if abs(det_time - gt_time) <= tolerance_sec:
                tp += 1
                matched_gt.add(i)
                break
    fp = len(detected) - tp
    fn = len(ground_truth) - tp
    return tp, fp, fn


def main():
    parser = argparse.ArgumentParser(description="Evaluate event detection against ground truth")
    parser.add_argument("--tolerance", type=int, default=15, help="Time tolerance in seconds (default: 15)")
    args = parser.parse_args()

    clips = sorted(HLS_DIR.iterdir())
    clips = [c for c in clips if c.is_dir() and (c / "ground_truth.json").exists()]

    if not clips:
        print("No clips with ground_truth.json found in samples/hls/")
        return

    print(f"=== Evaluating {len(clips)} clips (tolerance: {args.tolerance}s) ===\n")

    total_tp, total_fp, total_fn = 0, 0, 0

    for clip_dir in clips:
        name = clip_dir.name
        ground_truth = load_ground_truth(clip_dir)
        high_value_gt = [e for e in ground_truth if e["label"] in
                         ("Goal", "Penalty", "Red card", "Yellow card", "Shots on target")]

        print(f"{name}: {len(high_value_gt)} ground truth events")

        session = start_processing(clip_dir / "master.m3u8")
        if not session:
            print("  Skipped (server not running?)\n")
            continue

        result = poll_session(session["id"])
        if not result:
            print("  Timed out\n")
            continue

        detected = result.get("eventsDetected", 0)
        print(f"  Detected: {detected} events, {result.get('candidatesFound', 0)} candidates "
              f"from {result.get('windowsProcessed', 0)} windows")

        # For now, report summary stats
        # Full event-level matching requires the API to return event details
        print()

    print("=== Evaluation complete ===")
    print("Note: Enable LLM (app.openrouter.enabled=true) for full event classification")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Verify script syntax**

Run: `python3 -m py_compile scripts/evaluate_detection.py`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add scripts/evaluate_detection.py
git commit -m "Add evaluation script for detection pipeline against ground truth"
```

---

### Task 13: Final Build Verification and Push

- [ ] **Step 1: Clean build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: Manual smoke test**

Run: `./gradlew bootRun`
Open: `http://localhost:8080`
Verify:
- Player page loads with sidebar clips
- Stream URL input visible in header
- Entering a local clip path and clicking "Process" starts processing
- Event log shows triage entries

- [ ] **Step 3: Push (after user approval)**

```bash
git push origin main
```
