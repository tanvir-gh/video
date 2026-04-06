# HLS Tutorial Design — Incremental Learning Path

## Goal

Teach HLS video streaming from zero using real code in this Spring Boot codebase. Each stage introduces one concept, is fully functional on its own, and requires explicit user approval before proceeding to the next.

## Prerequisites

- FFmpeg installed locally
- espeak-ng installed (for TTS audio in test video)

## Generating the Test Video

The sample video and HLS segments are not committed to the repo (see `.gitignore`). Regenerate them as follows:

```bash
# Create directories
mkdir -p samples/tts samples/hls

# Generate TTS audio clips (voice announces every 5 seconds)
for sec in 5 10 15 20 25 30; do
  case $sec in
    5) word="five";; 10) word="ten";; 15) word="fifteen";;
    20) word="twenty";; 25) word="twenty five";; 30) word="thirty";;
  esac
  espeak-ng -w samples/tts/${sec}.wav "$word"
done

# Generate 30-second test video: black background, white seconds counter, TTS voice
ffmpeg -y \
  -f lavfi -i "color=c=black:s=1280x720:d=30:rate=30" \
  -i samples/tts/5.wav -i samples/tts/10.wav -i samples/tts/15.wav \
  -i samples/tts/20.wav -i samples/tts/25.wav \
  -filter_complex " \
    [0:v]drawtext=text='%{eif\:t\:d}':fontsize=300:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2[v]; \
    anullsrc=r=22050:cl=mono[silence]; \
    [1:a]adelay=5000|5000[a5]; \
    [2:a]adelay=10000|10000[a10]; \
    [3:a]adelay=15000|15000[a15]; \
    [4:a]adelay=20000|20000[a20]; \
    [5:a]adelay=25000|25000[a25]; \
    [silence][a5][a10][a15][a20][a25]amix=inputs=6:duration=longest[a]" \
  -map "[v]" -map "[a]" -t 30 -c:v libx264 -c:a aac samples/sample.mp4

# Chop into HLS segments
ffmpeg -y -i samples/sample.mp4 \
  -hls_time 6 -hls_list_size 0 \
  -hls_segment_filename 'samples/hls/segment_%03d.ts' \
  samples/hls/playlist.m3u8
```

This produces:
- `samples/sample.mp4` — 30s test video (black, seconds counter center screen, voice at 5s intervals)
- `samples/hls/playlist.m3u8` — HLS playlist
- `samples/hls/segment_*.ts` — 4 video segments (~8-10s each, split at keyframe boundaries)

## Stage 1 — Understand HLS by Hand

**Concept:** What HLS is at the file level.

**What we do:**
- Install FFmpeg if not already present
- Use FFmpeg CLI to chop a sample .mp4 into .ts segments and a .m3u8 playlist
- Walk through every line of the generated .m3u8 file to understand the format
- Examine .ts segment files (what they contain, why they're this size)

**What we learn:**
- HLS = a playlist (.m3u8) pointing to a sequence of small video chunks (.ts)
- Key playlist directives: #EXTM3U, #EXT-X-VERSION, #EXT-X-TARGETDURATION, #EXTINF, #EXT-X-ENDLIST
- Segment duration, sequence numbering, and why chunk size matters for latency vs. efficiency

**No code written.** Pure understanding of the format.

**Done when:** User can read a .m3u8 file and explain what each line does.

---

## Stage 2 — Serve HLS from Spring Boot

**Concept:** HLS is just HTTP. Serve static files, add a player.

**What we do:**
- Place pre-generated .m3u8 and .ts files in a Spring Boot static resource location
- Configure Spring Boot to serve these with correct MIME types (application/vnd.apple.mpegurl for .m3u8, video/mp2t for .ts)
- Create a Thymeleaf page with an hls.js player that points to the playlist URL
- Open browser, watch the video play
- Use browser DevTools Network tab to observe the player fetching the playlist then each segment

**What we build:**
- `WebMvcConfigurer` for MIME type configuration
- `src/main/resources/templates/player.html` — Thymeleaf page with hls.js
- A controller to serve the player page

**What we learn:**
- HLS requires no special server protocol — it's plain HTTP GET requests
- The player drives everything: it fetches the playlist, parses it, requests segments in order
- MIME types matter — wrong types break playback

**Done when:** Video plays in browser and user has inspected network requests.

---

## Stage 3 — Adaptive Bitrate Streaming by Hand

**Concept:** Multiple quality levels from one source, master playlists, player auto-switches.

**What we do:**
- Use FFmpeg CLI to encode the same source video into multiple variants (e.g. 360p, 720p, 1080p)
- Each variant gets its own .m3u8 playlist and .ts segments
- Hand-write a master playlist (.m3u8) that references each variant with BANDWIDTH and RESOLUTION metadata
- Place all variants in the Spring Boot static resource location
- Serve via the same player from Stage 2 — hls.js handles ABR automatically, no player code changes
- Add a quality indicator to the player UI so user can see switches happening
- Simulate bandwidth throttling in browser DevTools to observe quality switching

**What we learn:**
- Master vs. variant playlists — the two-level playlist hierarchy
- BANDWIDTH and RESOLUTION tags in master playlists
- FFmpeg flags for controlling output resolution and bitrate
- How ABR algorithms decide when to switch quality
- Why encoding multiple variants is the standard for production streaming

**No new Java services.** FFmpeg by hand, master playlist by hand, same Spring Boot server.

**Done when:** User can throttle bandwidth in DevTools and see the player switch between qualities.

---

## Process Rules

1. Each stage is completed fully before moving to the next
2. User gives explicit approval before proceeding to next stage
3. No commits or pushes without explicit user approval
4. Code is written incrementally within each stage — not all at once
5. Each concept is explained before being implemented
