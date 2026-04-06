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
import com.tanvir.video.model.TriageResult;

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

        double rmsDb = -91.0;
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

        return Math.pow(10.0, rmsDb / 20.0);
    }

    public boolean isAudioSpike(double rms, double baseline) {
        if (baseline <= 0) return rms > 0;
        return rms >= baseline * props.audioThresholdMultiplier();
    }

    public Path extractKeyframe(Path videoPath, double timestampSec, Path outputDir) throws Exception {
        Path framePath = outputDir.resolve("frame_" + (long) (timestampSec * 1000) + ".png");
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", videoPath.toString(),
                "-ss", String.valueOf(timestampSec),
                "-frames:v", "1",
                framePath.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
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

    public TriageResult triage(int windowIndex, Path videoPath, Path audioPath,
                               double baselineRms, Path workDir) throws Exception {
        double rms = extractAudioRms(audioPath);
        boolean audioSpike = isAudioSpike(rms, baselineRms);

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
}
