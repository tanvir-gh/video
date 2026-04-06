"""
Download SoccerNet games and extract random 10-minute HLS samples.

Usage:
  1. Set SOCCERNET_PASSWORD in .env
  2. pip install SoccerNet
  3. python scripts/download_soccernet.py

Full MKV files are kept in soccernet/ for resampling.
Random 10-minute clips are extracted as 720p HLS in samples/hls/.

Rerun with --resample to generate new random clips from existing downloads.
"""

import argparse
import json
import os
import random
import subprocess
import sys
from pathlib import Path

# --- Config ---
PROJECT_ROOT = Path(__file__).resolve().parent.parent
SOCCERNET_DIR = PROJECT_ROOT / "soccernet"
SAMPLES_DIR = PROJECT_ROOT / "samples"
HLS_DIR = SAMPLES_DIR / "hls"

SAMPLES_PER_GAME = 10
SAMPLE_DURATION = 600  # 10 minutes in seconds
MIN_START = 60         # skip first minute (pre-match)

# Games to process (auto-discovered if empty)
GAMES = []


def load_env():
    """Load .env files: project .env then ~/.env for shared keys."""
    for env_path in [PROJECT_ROOT / ".env", Path.home() / ".env"]:
        if env_path.exists():
            for line in env_path.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, val = line.split("=", 1)
                    os.environ.setdefault(key.strip(), val.strip())


def get_video_duration(path):
    """Get video duration in seconds using ffprobe."""
    result = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)],
        capture_output=True, text=True
    )
    if result.returncode != 0 or not result.stdout.strip():
        print(f"  WARNING: ffprobe failed for {path}, skipping")
        return None
    return float(result.stdout.strip())


def download_games():
    """Download game files from SoccerNet."""
    password = os.environ.get("SOCCERNET_PASSWORD", "")
    if not password:
        print("Error: Set SOCCERNET_PASSWORD in .env")
        sys.exit(1)

    from SoccerNet.Downloader import SoccerNetDownloader

    downloader = SoccerNetDownloader(LocalDirectory=str(SOCCERNET_DIR))
    downloader.password = password

    for game in GAMES:
        print(f"\nDownloading: {game}")
        downloader.downloadGame(
            files=["1_720p.mkv", "2_720p.mkv", "Labels-v2.json"],
            game=game,
        )


def find_mkv_files():
    """Find all downloaded MKV files."""
    mkvs = []
    for mkv in sorted(SOCCERNET_DIR.rglob("*_720p.mkv")):
        game = str(mkv.parent.relative_to(SOCCERNET_DIR))
        half = mkv.name
        mkvs.append((game, half, mkv))
    return mkvs


def make_slug(game, half, start_sec):
    """Create a short directory name for a sample."""
    match_info = game.split("/")[-1]  # "2017-04-18 - 21-45 Real Madrid 4 - 2 Bayern Munich"
    # Extract teams portion
    parts = match_info.split(" - ")
    teams = parts[-1].strip() if len(parts) >= 3 else match_info
    teams_short = teams.replace(" ", "_").lower()[:30]
    half_num = half[0]  # "1" or "2"
    minutes = start_sec // 60
    return f"{teams_short}_h{half_num}_{minutes:03d}m"


def extract_samples(mkvs, seed=None, samples_per_game=SAMPLES_PER_GAME):
    """Extract random 10-minute clips as 720p HLS."""
    if seed is not None:
        random.seed(seed)

    manifest = []

    for game, half, mkv_path in mkvs:
        duration = get_video_duration(mkv_path)
        if duration is None:
            continue
        max_start = duration - SAMPLE_DURATION - 10  # buffer at end

        if max_start <= MIN_START:
            print(f"Skipping {mkv_path.name} — too short ({duration:.0f}s)")
            continue

        # Generate random start times
        starts = sorted(random.sample(
            range(MIN_START, int(max_start)),
            min(samples_per_game, int(max_start - MIN_START) // SAMPLE_DURATION)
        ))

        # Avoid overlapping clips
        filtered = [starts[0]]
        for s in starts[1:]:
            if s - filtered[-1] >= SAMPLE_DURATION:
                filtered.append(s)
            if len(filtered) >= samples_per_game:
                break
        starts = filtered

        print(f"\n=== {game} / {half} ({duration:.0f}s) — extracting {len(starts)} clips ===")

        for start_sec in starts:
            slug = make_slug(game, half, start_sec)
            clip_dir = HLS_DIR / slug
            clip_dir.mkdir(parents=True, exist_ok=True)

            print(f"  [{slug}] {start_sec}s - {start_sec + SAMPLE_DURATION}s")

            # Extract and encode as 720p HLS directly
            try:
                subprocess.run([
                    "ffmpeg", "-y",
                    "-ss", str(start_sec),
                    "-i", str(mkv_path),
                    "-t", str(SAMPLE_DURATION),
                    "-map", "0:v:0", "-map", "0:a:0",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                    "-bsf:v", "h264_mp4toannexb",
                    "-hls_time", "6", "-hls_list_size", "0",
                    "-hls_segment_filename", str(clip_dir / "segment_%03d.ts"),
                    str(clip_dir / "playlist.m3u8"),
                ], check=True, capture_output=True, text=True)
            except subprocess.CalledProcessError as e:
                print(f"  ERROR: ffmpeg failed for {slug}\n{e.stderr[-500:]}")
                continue

            # Write master playlist (single variant, but keeps format consistent)
            (clip_dir / "master.m3u8").write_text(
                "#EXTM3U\n"
                "#EXT-X-STREAM-INF:BANDWIDTH=2628000,RESOLUTION=1280x720\n"
                "playlist.m3u8\n"
            )

            manifest.append({
                "slug": slug,
                "game": game,
                "half": half,
                "start_sec": start_sec,
                "duration_sec": SAMPLE_DURATION,
                "hls_path": str(clip_dir.relative_to(PROJECT_ROOT)),
            })

    # Save manifest for reference
    manifest_path = HLS_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"\nManifest saved: {manifest_path}")
    print(f"Total clips: {len(manifest)}")

    # Copy labels alongside clips
    for game in GAMES:
        labels_src = SOCCERNET_DIR / game / "Labels-v2.json"
        if labels_src.exists():
            labels_dst = HLS_DIR / f"{game.split('/')[-1][:40].replace(' ', '_').lower()}_labels.json"
            import shutil
            shutil.copy2(labels_src, labels_dst)
            print(f"Labels copied: {labels_dst.name}")


def main():
    parser = argparse.ArgumentParser(description="Download SoccerNet and extract HLS samples")
    parser.add_argument("--resample", action="store_true",
                        help="Skip download, regenerate clips from existing MKVs")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed for reproducible sampling (default: 42)")
    parser.add_argument("--samples", type=int, default=SAMPLES_PER_GAME,
                        help=f"Clips per game half (default: {SAMPLES_PER_GAME})")
    args = parser.parse_args()

    load_env()

    samples_per_game = args.samples

    if not args.resample:
        download_games()

    mkvs = find_mkv_files()
    if not mkvs:
        print("No MKV files found. Run without --resample first.")
        sys.exit(1)

    print(f"\nFound {len(mkvs)} video files")
    extract_samples(mkvs, seed=args.seed, samples_per_game=samples_per_game)

    print("\n=== Done ===")
    print("Run: ./gradlew bootRun")
    print("Open: http://localhost:8080")


if __name__ == "__main__":
    main()
