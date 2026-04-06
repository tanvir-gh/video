package com.tanvir.video;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PlayerController {

    private final Path hlsDir;

    public PlayerController(@Value("${app.hls-dir}") String hlsDir) {
        this.hlsDir = Path.of(hlsDir);
    }

    @GetMapping("/")
    public String player(Model model) throws IOException {
        List<String> videos = List.of();
        if (Files.isDirectory(hlsDir)) {
            try (Stream<Path> dirs = Files.list(hlsDir)) {
                videos = dirs.filter(Files::isDirectory)
                             .filter(d -> Files.exists(d.resolve("master.m3u8")))
                             .map(d -> d.getFileName().toString())
                             .sorted()
                             .toList();
            }
        }
        model.addAttribute("videos", videos);
        return "player";
    }
}
