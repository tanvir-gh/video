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

    public String extractClip(Path sourcePath, double eventTimeSec,
                              double clipDuration, String slug, Path outputDir) throws Exception {
        double start = Math.max(0, eventTimeSec - clipDuration / 2);

        Path clipDir = outputDir.resolve(slug);
        Files.createDirectories(clipDir);

        Path playlist = clipDir.resolve("playlist.m3u8");

        log.info("Extracting clip: {} at {}s for {}s", slug,
                String.format("%.1f", start), String.format("%.1f", clipDuration));

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

        Files.writeString(clipDir.resolve("master.m3u8"),
                "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=2628000,RESOLUTION=1280x720\n" +
                "playlist.m3u8\n");

        Files.writeString(clipDir.resolve("event.json"), "{}");

        log.info("Clip extracted: {}", slug);
        return slug;
    }
}
