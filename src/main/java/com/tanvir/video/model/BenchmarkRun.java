package com.tanvir.video.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persistent record of a single benchmark/pipeline run.
 * Tracks config + accuracy + speed metrics for every clip processed.
 */
@Entity
@Table(name = "benchmark_runs")
public class BenchmarkRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_at", nullable = false)
    private Instant runAt = Instant.now();

    @Column(name = "clip_name", length = 255)
    private String clipName;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "keyframe_count")
    private Integer keyframeCount;

    @Column(name = "keyframe_width")
    private Integer keyframeWidth;

    @Column(name = "window_duration")
    private Integer windowDuration;

    @Column(name = "confidence_threshold")
    private Double confidenceThreshold;

    @Column(name = "tp")
    private Integer tp;

    @Column(name = "fp")
    private Integer fp;

    @Column(name = "fn")
    private Integer fn;

    @Column(name = "precision_score")
    private Double precision;

    @Column(name = "recall_score")
    private Double recall;

    @Column(name = "f1_score")
    private Double f1;

    @Column(name = "total_time_sec")
    private Double totalTimeSec;

    @Column(name = "avg_window_time_ms")
    private Integer avgWindowTimeMs;

    @Column(name = "windows_processed")
    private Integer windowsProcessed;

    @Column(name = "git_sha", length = 40)
    private String gitSha;

    @Column(name = "notes", length = 1000)
    private String notes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }
    public String getClipName() { return clipName; }
    public void setClipName(String clipName) { this.clipName = clipName; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getKeyframeCount() { return keyframeCount; }
    public void setKeyframeCount(Integer keyframeCount) { this.keyframeCount = keyframeCount; }
    public Integer getKeyframeWidth() { return keyframeWidth; }
    public void setKeyframeWidth(Integer keyframeWidth) { this.keyframeWidth = keyframeWidth; }
    public Integer getWindowDuration() { return windowDuration; }
    public void setWindowDuration(Integer windowDuration) { this.windowDuration = windowDuration; }
    public Double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(Double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }
    public Integer getTp() { return tp; }
    public void setTp(Integer tp) { this.tp = tp; }
    public Integer getFp() { return fp; }
    public void setFp(Integer fp) { this.fp = fp; }
    public Integer getFn() { return fn; }
    public void setFn(Integer fn) { this.fn = fn; }
    public Double getPrecision() { return precision; }
    public void setPrecision(Double precision) { this.precision = precision; }
    public Double getRecall() { return recall; }
    public void setRecall(Double recall) { this.recall = recall; }
    public Double getF1() { return f1; }
    public void setF1(Double f1) { this.f1 = f1; }
    public Double getTotalTimeSec() { return totalTimeSec; }
    public void setTotalTimeSec(Double totalTimeSec) { this.totalTimeSec = totalTimeSec; }
    public Integer getAvgWindowTimeMs() { return avgWindowTimeMs; }
    public void setAvgWindowTimeMs(Integer avgWindowTimeMs) { this.avgWindowTimeMs = avgWindowTimeMs; }
    public Integer getWindowsProcessed() { return windowsProcessed; }
    public void setWindowsProcessed(Integer windowsProcessed) { this.windowsProcessed = windowsProcessed; }
    public String getGitSha() { return gitSha; }
    public void setGitSha(String gitSha) { this.gitSha = gitSha; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
