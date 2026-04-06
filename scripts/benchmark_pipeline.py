"""
Benchmark the event detection pipeline against test clips.

Runs the pipeline components directly (no Spring Boot needed):
1. ffmpeg segments the clip into 30s windows
2. Extracts 3 keyframes per window (512px wide)
3. Sends keyframes to ollama qwen3.5:9b for classification

Measures: segmentation time, keyframe extraction time, LLM time per window,
total pipeline time, and detected events vs ground truth.

Usage:
  python scripts/benchmark_pipeline.py [--clip 01_goal_2_arsenal] [--all]
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from urllib import request

PROJECT_ROOT = Path(__file__).resolve().parent.parent
HLS_DIR = PROJECT_ROOT / "samples" / "hls"
WORK_DIR = PROJECT_ROOT / "work" / "benchmark"
OLLAMA_URL = "http://localhost:11434/api/chat"
MODEL = "qwen3.5:9b"
WINDOW_DURATION = 30
KEYFRAME_COUNT = 3
KEYFRAME_WIDTH = 512

PROMPT_TEMPLATE = (PROJECT_ROOT / "src" / "main" / "resources" / "prompts" / "classify_event.txt").read_text()


def segment(clip_path, work_dir):
    """Segment a clip into 30s windows."""
    work_dir.mkdir(parents=True, exist_ok=True)
    start = time.time()
    subprocess.run([
        "ffmpeg", "-y", "-i", str(clip_path),
        "-c", "copy", "-f", "segment",
        "-segment_time", str(WINDOW_DURATION),
        "-reset_timestamps", "1",
        str(work_dir / "window_%03d.ts")
    ], capture_output=True)
    elapsed = time.time() - start

    windows = sorted(work_dir.glob("window_*.ts"))
    return windows, elapsed


def extract_keyframes(video_path, work_dir):
    """Extract N evenly-spaced keyframes, resized."""
    frames = []
    for i in range(KEYFRAME_COUNT):
        ts = 2.0 + (WINDOW_DURATION - 4.0) * i / (KEYFRAME_COUNT - 1)
        frame_path = work_dir / f"kf_{i}_{ts:.0f}.png"
        subprocess.run([
            "ffmpeg", "-y", "-i", str(video_path),
            "-ss", str(ts), "-frames:v", "1",
            "-vf", f"scale={KEYFRAME_WIDTH}:-1",
            str(frame_path)
        ], capture_output=True)
        if frame_path.exists() and frame_path.stat().st_size > 0:
            frames.append(frame_path)
    return frames


def classify_window(frame_paths):
    """Send keyframes to ollama for classification."""
    images = []
    for fp in frame_paths:
        images.append(base64.b64encode(fp.read_bytes()).decode())

    prompt = PROMPT_TEMPLATE.replace("{transcript}", "No commentary transcript available.")

    payload = json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt, "images": images}],
        "stream": False,
        "think": False,
        "options": {"num_predict": 200, "temperature": 0.1}
    })

    start = time.time()
    req = request.Request(OLLAMA_URL, data=payload.encode(),
                          headers={"Content-Type": "application/json"})
    resp = request.urlopen(req, timeout=120)
    data = json.loads(resp.read())
    elapsed = time.time() - start

    content = data["message"]["content"]
    tokens = data.get("eval_count", 0)

    # Parse events
    try:
        cleaned = content.strip()
        if cleaned.startswith("```"):
            cleaned = cleaned.split("\n", 1)[1].rsplit("```", 1)[0].strip()
        events = json.loads(cleaned).get("events", [])
    except (json.JSONDecodeError, KeyError):
        events = []

    return events, elapsed, tokens


def load_ground_truth(clip_dir):
    gt_path = clip_dir / "ground_truth.json"
    if not gt_path.exists():
        return []
    data = json.loads(gt_path.read_text())
    return [e for e in data.get("events", [])
            if e["label"] in ("Goal", "Penalty", "Red card", "Yellow card", "Shots on target")]


def run_clip(clip_name):
    clip_dir = HLS_DIR / clip_name
    master = clip_dir / "master.m3u8"
    if not master.exists():
        print(f"  ERROR: {master} not found")
        return None

    work_dir = WORK_DIR / clip_name
    if work_dir.exists():
        import shutil
        shutil.rmtree(work_dir)

    ground_truth = load_ground_truth(clip_dir)
    print(f"\n{'='*60}")
    print(f"Clip: {clip_name}")
    print(f"Ground truth: {len(ground_truth)} high-value events")
    for gt in ground_truth:
        print(f"  {gt['offset_sec']:6.1f}s  {gt['label']:20s}  {gt['game_time']}")

    # Segment
    windows, seg_time = segment(master, work_dir)
    print(f"\nSegmented into {len(windows)} windows ({seg_time:.1f}s)")

    # Process each window
    total_kf_time = 0
    total_llm_time = 0
    total_tokens = 0
    all_events = []

    for i, window in enumerate(windows):
        win_dir = work_dir / f"w{i}"
        win_dir.mkdir(exist_ok=True)

        # Extract keyframes
        kf_start = time.time()
        frames = extract_keyframes(window, win_dir)
        kf_elapsed = time.time() - kf_start
        total_kf_time += kf_elapsed

        # Classify
        events, llm_elapsed, tokens = classify_window(frames)
        total_llm_time += llm_elapsed
        total_tokens += tokens

        status = "EVENTS" if events else "quiet"
        print(f"  Window {i:2d}: {status:6s} ({kf_elapsed:.1f}s kf + {llm_elapsed:.1f}s llm = {kf_elapsed+llm_elapsed:.1f}s)")

        for ev in events:
            event_time = i * WINDOW_DURATION + WINDOW_DURATION / 2
            print(f"           -> {ev['type']} (conf={ev['confidence']:.2f}): {ev.get('description', '')[:60]}")
            all_events.append({**ev, "window": i, "timestamp": event_time})

    total_time = seg_time + total_kf_time + total_llm_time
    print(f"\n--- Timing ---")
    print(f"  Segmentation:  {seg_time:.1f}s")
    print(f"  Keyframes:     {total_kf_time:.1f}s ({total_kf_time/len(windows):.1f}s/window)")
    print(f"  LLM:           {total_llm_time:.1f}s ({total_llm_time/len(windows):.1f}s/window)")
    print(f"  Total:         {total_time:.1f}s for {len(windows)*WINDOW_DURATION/60:.1f}min of video")
    print(f"  Tokens:        {total_tokens} ({total_tokens/len(windows):.0f}/window)")

    print(f"\n--- Results ---")
    print(f"  Detected {len(all_events)} events, ground truth has {len(ground_truth)}")
    for ev in all_events:
        print(f"  [{ev['window']:2d}] {ev['timestamp']:5.0f}s {ev['type']:20s} conf={ev['confidence']:.2f}")

    return {
        "clip": clip_name,
        "windows": len(windows),
        "seg_time": seg_time,
        "kf_time": total_kf_time,
        "llm_time": total_llm_time,
        "total_time": total_time,
        "detected": all_events,
        "ground_truth": ground_truth,
    }


def main():
    parser = argparse.ArgumentParser(description="Benchmark event detection pipeline")
    parser.add_argument("--clip", help="Specific clip name to test")
    parser.add_argument("--all", action="store_true", help="Run all clips with ground truth")
    parser.add_argument("--first", type=int, default=3, help="Run first N clips (default: 3)")
    args = parser.parse_args()

    if args.clip:
        clips = [args.clip]
    elif args.all:
        clips = sorted([d.name for d in HLS_DIR.iterdir()
                        if d.is_dir() and (d / "ground_truth.json").exists()])
    else:
        clips = sorted([d.name for d in HLS_DIR.iterdir()
                        if d.is_dir() and (d / "ground_truth.json").exists()])[:args.first]

    if not clips:
        print("No clips found with ground_truth.json in samples/hls/")
        return

    print(f"Benchmarking {len(clips)} clips with model={MODEL}, "
          f"windows={WINDOW_DURATION}s, keyframes={KEYFRAME_COUNT}@{KEYFRAME_WIDTH}px")

    results = []
    for clip in clips:
        result = run_clip(clip)
        if result:
            results.append(result)

    if len(results) > 1:
        print(f"\n{'='*60}")
        print(f"SUMMARY ({len(results)} clips)")
        total_windows = sum(r["windows"] for r in results)
        total_time = sum(r["total_time"] for r in results)
        total_detected = sum(len(r["detected"]) for r in results)
        total_gt = sum(len(r["ground_truth"]) for r in results)
        print(f"  Total windows: {total_windows}")
        print(f"  Total time: {total_time:.1f}s")
        print(f"  Avg per window: {total_time/total_windows:.1f}s")
        print(f"  Total detected: {total_detected}, ground truth: {total_gt}")


if __name__ == "__main__":
    main()
