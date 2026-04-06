"""
Extract HLS clips from SoccerNet games based on selections.json.

Reads samples/hls/selections.json (produced by analyze_events.py),
extracts each window as a 720p HLS stream, and writes ground truth
alongside each clip.

Usage:
  python scripts/analyze_events.py       # first: generate selections.json
  python scripts/extract_clips.py        # then: extract the clips

Rerun safely — existing clips are skipped unless --force is used.
"""

import argparse
import json
import subprocess
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SOCCERNET_DIR = PROJECT_ROOT / "soccernet"
HLS_DIR = PROJECT_ROOT / "samples" / "hls"


def make_slug(idx, selection):
    """Create a directory name from selection metadata."""
    game_short = selection["game"].split("/")[-1]
    # Extract team names from "2015-02-21 - 18-00 Crystal Palace 1 - 2 Arsenal"
    parts = game_short.split(" - ")
    teams = parts[-1].strip().replace(" ", "_").lower()[:25] if len(parts) >= 3 else "unknown"
    top_event = max(selection["events"], key=lambda e: e["weight"])["label"]
    top_event_slug = top_event.replace(" ", "_").lower()
    return f"{idx:02d}_{top_event_slug}_{teams}"


def extract_clip(selection, slug, force=False):
    """Extract a single HLS clip."""
    clip_dir = HLS_DIR / slug
    playlist = clip_dir / "playlist.m3u8"

    if playlist.exists() and not force:
        print(f"  Skipping {slug} — already exists (use --force to re-extract)")
        return True

    clip_dir.mkdir(parents=True, exist_ok=True)
    mkv = SOCCERNET_DIR / selection["game"] / f"{selection['half']}_720p.mkv"

    if not mkv.exists():
        print(f"  ERROR: {mkv} not found")
        return False

    duration = selection["end"] - selection["start"]

    print(f"  Extracting {slug} ({duration}s from {mkv.name} at {selection['start']}s)...")
    result = subprocess.run([
        "ffmpeg", "-y",
        "-ss", str(selection["start"]),
        "-i", str(mkv),
        "-t", str(duration),
        "-map", "0:v:0", "-map", "0:a:0",
        "-enc_time_base", "0",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
        "-c:a", "aac", "-b:a", "128k",
        "-bsf:v", "h264_mp4toannexb",
        "-hls_time", "6", "-hls_list_size", "0",
        "-hls_segment_filename", str(clip_dir / "segment_%03d.ts"),
        str(playlist),
    ], capture_output=True, text=True)

    if result.returncode != 0:
        print(f"  ERROR: ffmpeg failed\n{result.stderr[-300:]}")
        return False

    # Write master playlist (single variant, consistent format)
    (clip_dir / "master.m3u8").write_text(
        "#EXTM3U\n"
        "#EXT-X-STREAM-INF:BANDWIDTH=2628000,RESOLUTION=1280x720\n"
        "playlist.m3u8\n"
    )

    # Write ground truth
    (clip_dir / "ground_truth.json").write_text(json.dumps({
        "source": {
            "game": selection["game"],
            "half": selection["half"],
            "clip_start_sec": selection["start"],
            "clip_end_sec": selection["end"],
        },
        "score": selection["score"],
        "events": selection["events"],
    }, indent=2))

    return True


def clean(keep_test_videos=True):
    """Remove all extracted clips, selections, and manifest. Never touches soccernet/."""
    import shutil
    if not HLS_DIR.exists():
        return
    for item in HLS_DIR.iterdir():
        if keep_test_videos and item.name in ("blue30", "green45"):
            continue
        if item.is_dir():
            shutil.rmtree(item)
        else:
            item.unlink()
    print("Cleaned samples/hls/ (kept test videos, preserved soccernet/)")


def main():
    parser = argparse.ArgumentParser(description="Extract HLS clips from SoccerNet selections")
    parser.add_argument("--force", action="store_true", help="Re-extract existing clips")
    parser.add_argument("--clean", action="store_true", help="Remove extracted clips and intermediate files (never touches soccernet/)")
    args = parser.parse_args()

    if args.clean:
        clean()
        return

    selections_path = HLS_DIR / "selections.json"
    if not selections_path.exists():
        print(f"No selections found at {selections_path}")
        print("Run: python scripts/analyze_events.py")
        return

    selections = json.loads(selections_path.read_text())
    print(f"=== Extracting {len(selections)} clips ===\n")

    manifest = []
    for i, sel in enumerate(selections):
        slug = make_slug(i + 1, sel)
        print(f"[{i+1}/{len(selections)}] {slug}")

        success = extract_clip(sel, slug, force=args.force)
        if success:
            manifest.append({
                "slug": slug,
                "game": sel["game"],
                "half": sel["half"],
                "start": sel["start"],
                "end": sel["end"],
                "score": sel["score"],
                "top_event": max(sel["events"], key=lambda e: e["weight"])["label"],
                "num_events": len(sel["events"]),
            })

    # Write manifest
    manifest_path = HLS_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"\n=== Done: {len(manifest)} clips extracted ===")
    print(f"Manifest: {manifest_path}")
    print("Run: ./gradlew bootRun → http://localhost:8080")


if __name__ == "__main__":
    main()
