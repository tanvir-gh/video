package com.tanvir.video.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class StreamSession {

    public enum Status { RUNNING, SEGMENTING, READY, STOPPED, ERROR }

    private final String id;
    private final String streamUrl;
    private final Instant startedAt;
    private volatile Status status;
    private final AtomicInteger windowsProcessed = new AtomicInteger();
    private final AtomicInteger candidatesFound = new AtomicInteger();
    private final List<DetectedEvent> detectedEvents = new CopyOnWriteArrayList<>();
    private volatile Process ingestorProcess;

    // Segmented windows and per-window result cache
    private volatile List<WindowInfo> windows;
    private final Map<Integer, ClassificationResult> windowCache = new ConcurrentHashMap<>();
    private volatile Path workDir;
    private volatile Path hlsOutput;
    private volatile Path sourcePath;

    // Context tracking across windows
    private final StringBuilder runningContext = new StringBuilder();
    private volatile String lastEventType = "";
    private volatile int lastEventWindow = -10;
    private volatile int targetWindow = -1; // most recently requested window

    // Set of event IDs already published to Kafka for this session (for dedup on recovery)
    private volatile Set<String> recoveredEventIds = Collections.emptySet();

    // Window index to resume from after Kafka recovery (highest seen + 1)
    private volatile int resumeFromWindow = 0;

    public record WindowInfo(Path videoPath, int index) {}

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
    public int getWindowsProcessed() { return windowsProcessed.get(); }
    public void incrementWindowsProcessed() { windowsProcessed.incrementAndGet(); }
    public int getCandidatesFound() { return candidatesFound.get(); }
    public void incrementCandidatesFound() { candidatesFound.incrementAndGet(); }
    public List<DetectedEvent> getDetectedEvents() { return detectedEvents; }
    public void addEvent(DetectedEvent event) { this.detectedEvents.add(event); }
    public Process getIngestorProcess() { return ingestorProcess; }
    public void setIngestorProcess(Process process) { this.ingestorProcess = process; }

    public List<WindowInfo> getWindows() { return windows; }
    public void setWindows(List<WindowInfo> windows) { this.windows = windows; }
    public Map<Integer, ClassificationResult> getWindowCache() { return windowCache; }
    public boolean isWindowCached(int index) { return windowCache.containsKey(index); }

    public Path getWorkDir() { return workDir; }
    public void setWorkDir(Path workDir) { this.workDir = workDir; }
    public Path getHlsOutput() { return hlsOutput; }
    public void setHlsOutput(Path hlsOutput) { this.hlsOutput = hlsOutput; }
    public Path getSourcePath() { return sourcePath; }
    public void setSourcePath(Path sourcePath) { this.sourcePath = sourcePath; }

    public StringBuilder getRunningContext() { return runningContext; }
    public String getLastEventType() { return lastEventType; }
    public void setLastEventType(String type) { this.lastEventType = type; }
    public int getLastEventWindow() { return lastEventWindow; }
    public void setLastEventWindow(int window) { this.lastEventWindow = window; }

    public int getTargetWindow() { return targetWindow; }
    public void setTargetWindow(int window) { this.targetWindow = window; }
    public int getWindowCount() { return windows != null ? windows.size() : 0; }

    public Set<String> getRecoveredEventIds() { return recoveredEventIds; }
    public void setRecoveredEventIds(Set<String> ids) {
        this.recoveredEventIds = ids != null ? ids : Collections.emptySet();
    }

    public int getResumeFromWindow() { return resumeFromWindow; }
    public void setResumeFromWindow(int window) { this.resumeFromWindow = window; }
}
