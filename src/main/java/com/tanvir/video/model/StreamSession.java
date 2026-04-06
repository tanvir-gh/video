package com.tanvir.video.model;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class StreamSession {

    public enum Status { RUNNING, STOPPED, ERROR }

    private final String id;
    private final String streamUrl;
    private final Instant startedAt;
    private volatile Status status;
    private final AtomicInteger windowsProcessed = new AtomicInteger();
    private final AtomicInteger candidatesFound = new AtomicInteger();
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
    public int getWindowsProcessed() { return windowsProcessed.get(); }
    public void incrementWindowsProcessed() { windowsProcessed.incrementAndGet(); }
    public int getCandidatesFound() { return candidatesFound.get(); }
    public void incrementCandidatesFound() { candidatesFound.incrementAndGet(); }
    public List<DetectedEvent> getDetectedEvents() { return detectedEvents; }
    public void addEvent(DetectedEvent event) { this.detectedEvents.add(event); }
    public Process getIngestorProcess() { return ingestorProcess; }
    public void setIngestorProcess(Process process) { this.ingestorProcess = process; }
}
